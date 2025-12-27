package maple.expectation.concurrency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.service.v2.LikeSyncService; // 💡 추가
import maple.expectation.support.SpringBootTestWithTimeLogging;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
@SpringBootTestWithTimeLogging
public class LikeConcurrencyTest {

    @Autowired private GameCharacterRepository gameCharacterRepository;
    @Autowired private GameCharacterService gameCharacterService;
    @Autowired private LikeSyncService likeSyncService; // 💡 동기화 제어용 주입
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;

    private String targetUserIgn;

    @BeforeEach
    void setUp() {
        targetUserIgn = "TestUser_" + UUID.randomUUID().toString().substring(0, 8);
        transactionTemplate.execute(status -> {
            String fakeOcid = "test-fake-ocid-" + UUID.randomUUID().toString();
            gameCharacterRepository.save(new GameCharacter(targetUserIgn, fakeOcid));
            return null;
        });
    }

    @Test
    @DisplayName("🚀 계층형 쓰기 지연 검증: 1000명 동시 요청 -> L1->L2->L3 단계별 동기화 후 DB 반영 확인")
    void hierarchicalLikePerformanceTest() throws InterruptedException {
        // [Given] 1000명의 유저가 동시에 좋아요 클릭
        int userCount = 1000;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(userCount);

        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    // 1단계: L1(Caffeine)에 기록됨
                    gameCharacterService.clickLikeCache(targetUserIgn);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executorService.shutdown();

        // [When] 단계별 수동 동기화 실행 (스케줄러 기다리지 않음!)
        log.info("📥 [Step 1] L1(Caffeine) -> L2(Redis) 전송 시작");
        likeSyncService.flushLocalToRedis();

        log.info("📤 [Step 2] L2(Redis) -> L3(DB) 최종 동기화 시작");
        likeSyncService.syncRedisToDatabase();

        // [Then] DB 최종 값 확인
        entityManager.clear(); // 영속성 컨텍스트 초기화 필수
        GameCharacter characterAfterSync = gameCharacterService.getCharacterOrThrow(targetUserIgn);

        log.info("✅ 모든 계층 동기화 완료. DB 최종 값: {}", characterAfterSync.getLikeCount());
        assertEquals(userCount, characterAfterSync.getLikeCount());
    }
}