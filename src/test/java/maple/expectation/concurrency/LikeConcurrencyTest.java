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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 🚀 [Issue #133] 계층형 쓰기 지연 및 장애 복원력 테스트
 * - IntegrationTestSupport 상속으로 컨텍스트 공유 최적화
 * - SpringBootTestWithTimeLogging 적용으로 동시성 통계 측정
 *
 * <p>Note: 좋아요 버퍼링 검증이 목적이므로 LikeProcessor를 직접 사용</p>
 */
@EnableTimeLogging
public class LikeConcurrencyTest extends IntegrationTestSupport {

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
        // 💡 중요: 저장 후 영속성 컨텍스트를 비워야 이후 조회 시 DB에서 새로 가져옵니다.
        entityManager.clear();
    }

    @AfterEach
    void tearDown() {
        // 💡 싱글톤 DB 공유를 위해 데이터 정리 필수
        gameCharacterRepository.deleteAllInBatch();
        // 💡 Redis 장애 복구 및 버퍼 정리
        recoverMaster();
        entityManager.clear();
    }

    @Test
    @DisplayName("🚀 계층형 쓰기 지연 검증: L1->L2->L3 동기화 확인")
    void hierarchicalLikePerformanceTest() throws InterruptedException {
        int userCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(userCount);

        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try { likeProcessor.processLike(targetUserIgn); }
                finally { latch.countDown(); }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();
        // 5-Agent 합의: 모든 작업 완료 대기 (incrementAndGet 완료 보장)
        executorService.awaitTermination(5, TimeUnit.SECONDS);

        likeSyncService.flushLocalToRedis();
        likeSyncService.syncRedisToDatabase();

        // 💡 Assertion 전 클리어 (JPA 1차 캐시 무효화)
        entityManager.clear();
        GameCharacter character = gameCharacterService.getCharacterOrThrow(targetUserIgn);
        assertEquals(userCount, character.getLikeCount());
    }

    @Test
    @DisplayName("🚑 Redis 장애 시나리오: L2 전송 실패 시 즉시 DB(L3) 반영 확인")
    void redisFailureFallbackTest() {
        failMaster(); // Redis 차단

        try {
            likeProcessor.processLike(targetUserIgn);
            likeSyncService.flushLocalToRedis(); // L1→L2 시도 (Redis 장애로 실패)
            likeSyncService.syncRedisToDatabase(); // L2→L3 동기화 (Redis 장애 시 직접 DB 반영)

            // 🚀 [해결 핵심] 영속성 컨텍스트를 완전히 비웁니다.
            // 이걸 해야 다음 줄에서 DB의 진짜 최신값(1)을 읽어옵니다.
            entityManager.clear();

            GameCharacter character = gameCharacterService.getCharacterOrThrow(targetUserIgn);

            // 이제 Actual이 1이 되어 성공할 것입니다.
            assertEquals(1, character.getLikeCount(), "Redis 장애 시 DB로 직접 반영되어야 합니다.");

        } finally {
            recoverMaster();
        }
    }
}