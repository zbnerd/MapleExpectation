package maple.expectation.concurrency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.support.SpringBootTestWithTimeLogging;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Commit;
import org.springframework.test.context.TestPropertySource;

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

    @PersistenceContext // ✅ 1. EntityManager 주입
    private EntityManager entityManager;

    private String targetUserIgn;

    @BeforeEach
    @Transactional
    void setUp() {
        // 테스트용 타겟 캐릭터 생성 (좋아요 0개)
        GameCharacter target = new GameCharacter("테스트유저_Geek");
        gameCharacterRepository.save(target);
        targetUserIgn = target.getUserIgn();
    }

    @AfterEach
    void tearDown() {
        // 테스트용 데이터만 삭제
        gameCharacterRepository.delete(gameCharacterService.getCharacterOrThrowException(targetUserIgn));
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
        // 32개의 스레드 풀 생성 (동시 접속자 흉내)
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        // 100명이 다 준비될 때까지 기다리는 신호총 (Latch)
        CountDownLatch latch = new CountDownLatch(userCount);

        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    gameCharacterService.clickLikeWithOutLock(targetUserIgn); // 락 없는 메서드 호출
                } finally {
                    latch.countDown(); // 완료 신호 보냄
                }
            });
        }
        latch.await(); // 모든 스레드가 끝날 때까지 대기



        GameCharacter c = gameCharacterService.getCharacterOrThrowException(targetUserIgn);
        log.info("❌ [No Lock] 최종 좋아요: {}", c.getLikeCount());

        // userCount만큼 아니면 테스트 통과 (문제가 발생했음을 증명!)
        assertNotEquals(userCount, c.getLikeCount());
    }

    @Test
    @Commit
    @DisplayName("✅ 2. [비관적 락] 100명이 동시에 좋아요 -> 정확히 100개")
    void likeWithPessimisticLock() throws InterruptedException {
        int userCount = 100;
        // 32개의 스레드 풀 생성 (동시 접속자 흉내)
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        // 100명이 다 준비될 때까지 기다리는 신호총 (Latch)
        CountDownLatch latch = new CountDownLatch(userCount);


        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    gameCharacterService.clickLikeWithPessimisticLock(targetUserIgn); // 락 없는 메서드 호출
                } finally {
                    latch.countDown(); // 완료 신호 보냄
                }
            });
        }
        latch.await(); // 모든 스레드가 끝날 때까지 대기

        entityManager.clear();

        GameCharacter c = gameCharacterService.getCharacterOrThrowException(targetUserIgn);
        log.info("✅ [Pessimistic Lock] 최종 좋아요: {}", c.getLikeCount());

        // 정확히 유저카운트만큼 좋아요 갯수여야 성공
        assertEquals(userCount, c.getLikeCount());
    }

    @Test
    @DisplayName("⚠️ 3. [낙관적 락] (성능 비교용 - 현재 미사용)")
    @Commit
    @Disabled("고경합(High Contention) 상황에서 재시도 비용으로 인해 비관적 락보다 성능이 낮아(3.7s vs 3.2s) 비활성화함.")
    void likeWithOptimisticLock() throws InterruptedException {
        int userCount = 100;
        // 32개의 스레드 풀 생성 (동시 접속자 흉내)
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        // 100명이 다 준비될 때까지 기다리는 신호총 (Latch)
        CountDownLatch latch = new CountDownLatch(userCount);

        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    gameCharacterService.clickLikeWithOptimisticLock(targetUserIgn); // 락 없는 메서드 호출
                } finally {
                    latch.countDown(); // 완료 신호 보냄
                }
            });
        }
        latch.await(); // 모든 스레드가 끝날 때까지 대기

        entityManager.clear();

        Long finalCount = gameCharacterService.getLikeCount(targetUserIgn);
        log.info("✅ [OptimisticLock Lock] 최종 좋아요: {}", finalCount);

        // 정확히 유저카운트만큼 좋아요 갯수여야 성공
        assertEquals(userCount, finalCount);
    }
}
