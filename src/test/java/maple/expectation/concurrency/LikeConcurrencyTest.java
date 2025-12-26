package maple.expectation.concurrency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.support.SpringBootTestWithTimeLogging;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.support.TransactionTemplate;

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

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate; // 명시적 트랜잭션 커밋용

    private String targetUserIgn;

    @BeforeEach
    void setUp() {
        targetUserIgn = "TestUser_" + UUID.randomUUID().toString().substring(0, 8);

        // 💡 1. 데이터를 별도 트랜잭션으로 커밋하여 다른 쓰레드가 볼 수 있게 함
        transactionTemplate.execute(status -> {
            // [수정 포인트] 가짜 OCID를 미리 생성합니다.
            String fakeOcid = "test-fake-ocid-" + UUID.randomUUID().toString();

            // [수정 포인트] 생성자 호출 시 이름과 OCID를 한 번에 넣습니다. (Setter 제거 반영)
            GameCharacter target = new GameCharacter(targetUserIgn, fakeOcid);

            gameCharacterRepository.save(target);
            return null;
        });

        log.info("🎯 테스트 유저 준비 완료: {}", targetUserIgn);
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
    @DisplayName("🚀 1. [BufferedLikeProxy] 1000명 동시 요청 -> 쓰기 지연 후 스케줄러 DB 반영 확인")
    void bufferedLikePerformanceTest() throws InterruptedException {
        int userCount = 1000;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(userCount);

        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    gameCharacterService.clickLikeCache(targetUserIgn);
                } catch (Exception e) {
                    log.error("💥 [Cache] 좋아요 처리 중 에러: {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executorService.shutdown();

        log.info("💤 스케줄러 동기화 대기 중 (4.5s)...");
        Thread.sleep(4500);

        entityManager.clear();

        GameCharacter characterAfterSync = gameCharacterService.getCharacterOrThrow(targetUserIgn);
        log.info("✅ [After Sync] DB 최종 값: {}", characterAfterSync.getLikeCount());

        assertEquals(userCount, characterAfterSync.getLikeCount());
    }
}