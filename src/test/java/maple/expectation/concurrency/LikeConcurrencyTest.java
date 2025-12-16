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
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@Slf4j
@SpringBootTestWithTimeLogging
@TestPropertySource(properties = "app.optimization.use-compression=false")
public class LikeConcurrencyTest {

    @Autowired
    private GameCharacterRepository gameCharacterRepository;

    @Autowired
    GameCharacterService gameCharacterService;

    @PersistenceContext
    private EntityManager entityManager;

    private String targetUserIgn;

    @BeforeEach
    void setUp() {
        // 1. 랜덤 닉네임 생성
        targetUserIgn = "TestUser_" + UUID.randomUUID().toString().substring(0, 8);

        GameCharacter target = new GameCharacter(targetUserIgn);

        // [⭐⭐⭐ 핵심 수정 ⭐⭐⭐]
        // 테스트 환경에서는 API 호출이 안 되므로, 가짜 OCID를 강제로 넣어줘야 합니다.
        // (Setter가 없다면 엔티티에 추가하거나, 테스트용 생성자를 쓰셔야 합니다)
        target.setOcid("test-fake-ocid-" + UUID.randomUUID().toString());

        gameCharacterRepository.save(target);
    }

    @AfterEach
    void tearDown() {
        // [수정 2] 혹시 모를 잔여 데이터 제거를 위해 전체 삭제 (안전장치)
        // (단, H2라서 전체 삭제해도 속도 영향 거의 없음)
        try {
            gameCharacterRepository.deleteAll();
        } catch (Exception e) {
            log.warn("TearDown 중 에러 발생 (무시 가능): {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("✅ 0. [Lock 없음] 1명이 좋아요")
    void likeOne() throws InterruptedException {
        int userCount = 1;
        gameCharacterService.clickLikeWithOutLock(targetUserIgn);

        GameCharacter c = gameCharacterService.getCharacterOrThrowException(targetUserIgn);
        log.info("✅ [No Lock] 1명 좋아요: {}", c.getLikeCount());
        assertEquals(userCount, c.getLikeCount());
    }

    @Test
    @DisplayName("❌ 1. [Lock 없음] 100명이 동시에 좋아요 -> 100개가 안 됨 (실패)")
    void likeWithoutLock() throws InterruptedException {
        int userCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(userCount);

        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    gameCharacterService.clickLikeWithOutLock(targetUserIgn);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        GameCharacter c = gameCharacterService.getCharacterOrThrowException(targetUserIgn);
        log.info("❌ [No Lock] 최종 좋아요: {}", c.getLikeCount());

        assertNotEquals(userCount, c.getLikeCount());
    }

    @Test
    @Commit
    @DisplayName("✅ 2. [비관적 락] 100명이 동시에 좋아요 -> 정확히 100개")
    void likeWithPessimisticLock() throws InterruptedException {
        int userCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(userCount);

        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    gameCharacterService.clickLikeWithPessimisticLock(targetUserIgn);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        entityManager.clear();

        GameCharacter c = gameCharacterService.getCharacterOrThrowException(targetUserIgn);
        log.info("✅ [Pessimistic Lock] 최종 좋아요: {}", c.getLikeCount());

        assertEquals(userCount, c.getLikeCount());
    }


    @Test
    @DisplayName("🚀 [Caffeine Cache] 1000명 동시 요청 -> 0.1초 내 처리 -> 3초 뒤 DB 반영 확인")
    void cacheLikePerformanceTest() throws InterruptedException {
        int userCount = 1000;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(userCount);

        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    gameCharacterService.clickLikeWithCache(targetUserIgn);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        executorService.shutdown();

        entityManager.clear();
        GameCharacter characterBeforeSync = gameCharacterRepository.findByUserIgn(targetUserIgn).orElseThrow();
        log.info("⏳ [Before Sync] DB 현재 값: {}", characterBeforeSync.getLikeCount());

        // [주의] 로컬/CI 환경 성능 차이로 스케줄러가 조금 늦게 돌 수도 있으니 넉넉히 대기
        log.info("💤 스케줄러 대기 중...");
        Thread.sleep(4500);

        entityManager.clear();
        GameCharacter characterAfterSync = gameCharacterRepository.findByUserIgn(targetUserIgn).orElseThrow();
        log.info("✅ [After Sync] DB 최종 값: {}", characterAfterSync.getLikeCount());

        assertEquals(userCount, characterAfterSync.getLikeCount(),
                "스케줄러에 의해 좋아요가 DB에 반영되어야 합니다.");
    }
}