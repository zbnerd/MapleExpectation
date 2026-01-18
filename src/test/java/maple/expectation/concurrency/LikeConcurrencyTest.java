package maple.expectation.concurrency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.impl.RealNexonApiClient;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.repository.v2.RedisBufferRepository;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.service.v2.LikeProcessor;
import maple.expectation.service.v2.LikeSyncService;
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import maple.expectation.support.AbstractContainerBaseTest;
import maple.expectation.support.EnableTimeLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import eu.rekawek.toxiproxy.model.ToxicDirection;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 계층형 쓰기 지연 및 장애 복원력 테스트
 *
 * <p>AbstractContainerBaseTest 상속 (Toxiproxy 장애 주입 필요)
 *
 * <p>Note: 좋아요 버퍼링 검증이 목적이므로 LikeProcessor를 직접 사용
 *
 * <p>Flaky Test 방지 (CLAUDE.md Section 23-24):
 * <ul>
 *   <li>shutdown() 후 awaitTermination() 필수 호출</li>
 *   <li>CountDownLatch + awaitTermination 조합</li>
 *   <li>테스트 간 상태 격리</li>
 *   <li>MockBeans로 ApplicationContext 캐싱 일관성 확보</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"nexon.api.key=dummy-test-key"})
@EnableTimeLogging
@Tag("chaos")
@Execution(ExecutionMode.SAME_THREAD)  // CLAUDE.md Section 24: Toxiproxy 공유 상태 충돌 방지
public class LikeConcurrencyTest extends AbstractContainerBaseTest {

    private static final int LATCH_TIMEOUT_SECONDS = 30;
    private static final int TERMINATION_TIMEOUT_SECONDS = 10;

    // -------------------------------------------------------------------------
    // [Mock 구역] 외부 연동 Mock (ApplicationContext 캐싱 일관성)
    // -------------------------------------------------------------------------
    @MockitoBean private RealNexonApiClient nexonApiClient;
    @MockitoBean private DiscordAlertService discordAlertService;

    // -------------------------------------------------------------------------
    // [Real Bean 구역] 실제 DB/Redis 작동 확인용
    // -------------------------------------------------------------------------
    @Autowired private GameCharacterService gameCharacterService;
    @Autowired private GameCharacterRepository gameCharacterRepository;
    @Autowired private RedisBufferRepository redisBufferRepository;
    @Autowired private LikeProcessor likeProcessor;
    @Autowired private LikeSyncService likeSyncService;
    @Autowired private LikeBufferStorage likeBufferStorage;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;

    private String targetUserIgn;

    @BeforeEach
    void setUp() {
        targetUserIgn = "TestUser_" + UUID.randomUUID().toString().substring(0, 8);
        transactionTemplate.execute(status -> {
            gameCharacterRepository.save(new GameCharacter(targetUserIgn, "fake-ocid-" + UUID.randomUUID()));
            return null;
        });
        // 저장 후 영속성 컨텍스트를 비워야 이후 조회 시 DB에서 새로 가져옵니다.
        entityManager.clear();
    }

    @AfterEach
    void tearDown() {
        // CLAUDE.md Section 24: 테스트 간 상태 격리 강화
        // 1. 로컬 버퍼(L1) 정리 - 다음 테스트에 영향 방지
        likeBufferStorage.getCache().invalidateAll();

        // 2. Redis 장애 복구 (Toxiproxy toxic 제거)
        recoverMaster();

        // 3. 싱글톤 DB 공유를 위해 데이터 정리
        gameCharacterRepository.deleteAllInBatch();
        entityManager.clear();
    }

    @Test
    @DisplayName("계층형 쓰기 지연 검증: L1->L2->L3 동기화 확인")
    void hierarchicalLikePerformanceTest() throws InterruptedException {
        // [Given]
        int userCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(userCount);

        // [When] 동시에 좋아요 요청 발사
        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    likeProcessor.processLike(targetUserIgn);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Step 1: 모든 작업이 finally 블록까지 도달 대기
        boolean latchCompleted = latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(latchCompleted)
                .as("모든 좋아요 작업이 타임아웃 내에 완료되어야 함")
                .isTrue();

        // Step 2: Executor 종료 및 완료 대기 (스레드 리소스 정리 보장)
        executorService.shutdown();
        boolean terminated = executorService.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(terminated)
                .as("ExecutorService가 정상 종료되어야 함")
                .isTrue();

        // [Then] 동기화 실행
        likeSyncService.flushLocalToRedis();
        likeSyncService.syncRedisToDatabase();

        // Assertion 전 JPA 1차 캐시 무효화
        entityManager.clear();
        GameCharacter character = gameCharacterService.getCharacterOrThrow(targetUserIgn);

        assertEquals(userCount, character.getLikeCount(),
                "L1->L2->L3 동기화 후 정확한 좋아요 수가 반영되어야 함");
    }

    @Test
    @DisplayName("Redis 장애 시나리오: L2 전송 실패 시 Fallback 동작 확인")
    void redisFailureFallbackTest() throws Exception {
        // [Given] Redis 연결 안정화 대기 (Toxiproxy 상태 확인)
        org.awaitility.Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    try {
                        redisBufferRepository.getTotalPendingCount();
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                });

        // CLAUDE.md Section 24: Toxiproxy toxic으로 즉각적인 장애 주입
        redisProxy.toxics().timeout("redis-timeout", ToxicDirection.DOWNSTREAM, 1);

        try {
            // [When] 좋아요 처리 및 Fallback 동기화 실행
            likeProcessor.processLike(targetUserIgn);

            // flushLocalToRedisWithFallback()은 Redis 실패 시 파일 백업으로 fallback
            var result = likeSyncService.flushLocalToRedisWithFallback();

            // [Then] Redis 장애 시 파일 백업 또는 Redis 성공 중 하나는 발생해야 함
            // (Sentinel 환경에서는 Failover로 Redis 성공할 수 있음)
            assertThat(result).isNotNull();
            assertThat(result.redisSuccessCount() + result.fileBackupCount())
                    .as("Redis 성공 또는 파일 백업 중 하나는 발생해야 함")
                    .isGreaterThanOrEqualTo(1);

        } finally {
            // Toxic 제거 및 연결 복구
            recoverMaster();
            // 프록시 복구 대기
            org.awaitility.Awaitility.await()
                    .atMost(5, TimeUnit.SECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .until(() -> {
                        try {
                            redisBufferRepository.getTotalPendingCount();
                            return true;
                        } catch (Exception e) {
                            return false;
                        }
                    });
        }
    }
}
