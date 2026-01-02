package maple.expectation.concurrency;

import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.service.v2.LikeSyncService;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
public class LikeConcurrencyTest extends AbstractContainerBaseTest {

    @Autowired private GameCharacterRepository gameCharacterRepository;
    @Autowired private GameCharacterService gameCharacterService;
    @Autowired private LikeSyncService likeSyncService;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;

    private String targetUserIgn;

    @BeforeEach
    void setUp() {
        targetUserIgn = "TestUser_" + UUID.randomUUID().toString().substring(0, 8);
        transactionTemplate.execute(status -> {
            String fakeOcid = "test-fake-ocid-" + UUID.randomUUID();
            gameCharacterRepository.save(new GameCharacter(targetUserIgn, fakeOcid));
            return null;
        });
    }

    @Test
    @DisplayName("🚀 계층형 쓰기 지연 검증: 100명 동시 요청 -> L1->L2->L3 단계별 동기화 후 DB 반영 확인")
    void hierarchicalLikePerformanceTest() throws InterruptedException {
        int userCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(userCount);

        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    gameCharacterService.clickLikeCache(targetUserIgn);
                } finally {
                    latch.countDown();
                }
            });
        }

        // 💡 [수정] 무한정 기다리지 않도록 타임아웃을 줍니다.
        boolean completed = latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        executorService.shutdown();

        // Step 1 & 2 로직 동일...
        likeSyncService.flushLocalToRedis();
        likeSyncService.syncRedisToDatabase();

        entityManager.clear();
        GameCharacter characterAfterSync = gameCharacterService.getCharacterOrThrow(targetUserIgn);

        assertEquals(userCount, characterAfterSync.getLikeCount(), "DB 최종 값이 " + userCount + "이어야 합니다.");
    }

    @Test
    @DisplayName("🚑 Redis 장애 시나리오: L2 전송 실패 시 즉시 DB(L3) 반영 확인")
    void redisFailureFallbackTest() {
        // [Given] Redis 프록시에 장애(접속 차단) 주입
        // AbstractContainerBaseTest에서 설정한 redisProxy를 사용합니다.
        redisProxy.setConnectionCut(true);

        try {
            // [When] 좋아요 클릭 시도
            gameCharacterService.clickLikeCache(targetUserIgn);

            // L1 -> L2 전송 시도 (Redis가 죽었으므로 여기서 Fallback 발생해야 함)
            likeSyncService.flushLocalToRedis(); // 내부에서 handleRedisFailure 실행됨

            // [Then] DB에 즉시 반영되었는지 확인
            entityManager.clear();
            GameCharacter character = gameCharacterService.getCharacterOrThrow(targetUserIgn);

            // Redis를 거치지 않고 바로 DB로 갔으므로 likeCount는 1이어야 함
            assertEquals(1, character.getLikeCount(), "Redis 장애 시 DB로 직접 반영되어야 합니다.");

        } finally {
            // 장애 복구
            redisProxy.setConnectionCut(false);
        }
    }
}