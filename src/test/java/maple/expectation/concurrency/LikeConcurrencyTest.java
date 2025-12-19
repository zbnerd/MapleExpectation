package maple.expectation.concurrency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.service.v2.impl.DatabaseLikeProcessor;
import maple.expectation.support.SpringBootTestWithTimeLogging;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Commit;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
@SpringBootTestWithTimeLogging
public class LikeConcurrencyTest {

    @Autowired
    private GameCharacterRepository gameCharacterRepository;

    @Autowired
    private GameCharacterService gameCharacterService;

    @Autowired
    private DatabaseLikeProcessor databaseLikeProcessor; // 직접 DB 반영 (비관적 락) 검증용

    @PersistenceContext
    private EntityManager entityManager;

    private String targetUserIgn;

    @BeforeEach
    void setUp() {
        targetUserIgn = "TestUser_" + UUID.randomUUID().toString().substring(0, 8);
        GameCharacter target = new GameCharacter(targetUserIgn);
        target.setOcid("test-fake-ocid-" + UUID.randomUUID().toString());
        gameCharacterRepository.save(target);
    }

    @AfterEach
    void tearDown() {
        try {
            gameCharacterRepository.deleteAll();
        } catch (Exception e) {
            log.warn("TearDown 중 에러 발생: {}", e.getMessage());
        }
    }

    @Test
    @Commit
    @DisplayName("✅ 1. [DatabaseLikeProcessor] 100명 동시 좋아요 -> 비관적 락으로 정합성 보장")
    void likeWithPessimisticLock() throws InterruptedException {
        int userCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(userCount);

        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    databaseLikeProcessor.processLike(targetUserIgn);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        // 영속성 컨텍스트 초기화 후 DB에서 직접 다시 읽어옴
        entityManager.clear();

        // getCharacterOrThrowException -> getCharacterOrThrow로 수정
        GameCharacter c = gameCharacterService.getCharacterOrThrow(targetUserIgn);
        log.info("✅ [Pessimistic Lock] 최종 좋아요: {}", c.getLikeCount());

        assertEquals(userCount, c.getLikeCount());
    }

    @Test
    @DisplayName("🚀 2. [BufferedLikeProxy] 1000명 동시 요청 -> 쓰기 지연 후 스케줄러 DB 반영 확인")
    void bufferedLikePerformanceTest() throws InterruptedException {
        int userCount = 1000;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(userCount);

        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    gameCharacterService.clickLike(targetUserIgn);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executorService.shutdown();

        log.info("💤 스케줄러 동기화 대기 중 (4.5s)...");
        Thread.sleep(4500);

        // 검증 전 영속성 컨텍스트 초기화 필수
        entityManager.clear();

        GameCharacter characterAfterSync = gameCharacterService.getCharacterOrThrow(targetUserIgn);
        log.info("✅ [After Sync] DB 최종 값: {}", characterAfterSync.getLikeCount());

        assertEquals(userCount, characterAfterSync.getLikeCount());
    }
}