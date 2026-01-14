package maple.expectation.concurrency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.service.v2.LikeProcessor;
import maple.expectation.service.v2.LikeSyncService;
import maple.expectation.support.IntegrationTestSupport;
import maple.expectation.support.EnableTimeLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p>IntegrationTestSupport 상속으로 컨텍스트 공유 최적화
 *
 * <p>Note: 좋아요 버퍼링 검증이 목적이므로 LikeProcessor를 직접 사용
 *
 * <p>Flaky Test 방지 (CLAUDE.md Section 23):
 * <ul>
 *   <li>shutdown() 후 awaitTermination() 필수 호출</li>
 *   <li>CountDownLatch + awaitTermination 조합</li>
 *   <li>테스트 간 상태 격리</li>
 * </ul>
 */
@EnableTimeLogging
public class LikeConcurrencyTest extends IntegrationTestSupport {

    private static final int LATCH_TIMEOUT_SECONDS = 30;
    private static final int TERMINATION_TIMEOUT_SECONDS = 10;

    @Autowired private GameCharacterService gameCharacterService;
    @Autowired private LikeProcessor likeProcessor;
    @Autowired private LikeSyncService likeSyncService;
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
        // 싱글톤 DB 공유를 위해 데이터 정리 필수
        gameCharacterRepository.deleteAllInBatch();
        // Redis 장애 복구 및 버퍼 정리
        recoverMaster();
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
    @DisplayName("Redis 장애 시나리오: L2 전송 실패 시 즉시 DB(L3) 반영 확인")
    void redisFailureFallbackTest() throws InterruptedException {
        // [Given] Redis 차단 전 연결 안정화 대기
        Thread.sleep(500);

        failMaster(); // Redis 차단

        try {
            // 프록시 설정 반영 대기 (Toxiproxy 설정이 즉시 적용되지 않을 수 있음)
            Thread.sleep(1000);

            // [When] 좋아요 처리 및 동기화
            likeProcessor.processLike(targetUserIgn);
            likeSyncService.flushLocalToRedis(); // L1->L2 시도 (Redis 장애로 실패)

            // Redis 실패 시 내부적으로 DB 직접 반영이 발생하므로 처리 완료 대기
            // flushLocalToRedis 내부의 executeOrCatch가 동기적이므로 추가 대기 불필요
            likeSyncService.syncRedisToDatabase(); // L2->L3 동기화

            // [Then] 영속성 컨텍스트를 완전히 비워 DB의 최신값을 읽어옴
            entityManager.clear();

            GameCharacter character = gameCharacterService.getCharacterOrThrow(targetUserIgn);

            assertEquals(1, character.getLikeCount(),
                    "Redis 장애 시 DB로 직접 반영되어야 합니다.");

        } finally {
            recoverMaster();
            // 프록시 복구 대기
            Thread.sleep(500);
        }
    }
}
