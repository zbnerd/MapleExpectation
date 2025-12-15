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
//    @Transactional
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

    @Test
    @DisplayName("🚀 [Caffeine Cache] 1000명 동시 요청 -> 0.1초 내 처리 -> 3초 뒤 DB 반영 확인")
    void cacheLikePerformanceTest() throws InterruptedException {
        // Given
        int userCount = 1000;
        ExecutorService executorService = Executors.newFixedThreadPool(32); // 32개 스레드로 폭격
        CountDownLatch latch = new CountDownLatch(userCount);

        // When: 100명이 동시에 메모리(Cache)에 좋아요 누름

        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    gameCharacterService.clickLikeWithCache(targetUserIgn);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(); // 모든 요청이 끝날 때까지 대기

        // Then 1: 아직 DB에는 반영되지 않았어야 함 (스케줄러 동작 전)
        // 영속성 컨텍스트를 비워야 실제 DB 값을 가져옴
        entityManager.clear();
        GameCharacter characterBeforeSync = gameCharacterRepository.findByUserIgn(targetUserIgn).orElseThrow();

        // 주의: 타이밍에 따라 0일 수도 있고, 테스트 도중 스케줄러가 돌아버렸으면 일부 반영될 수도 있음.
        // 하지만 요청 처리 속도가 워낙 빨라(약 50ms) 보통 0이거나 매우 적은 수여야 정상.
        log.info("⏳ [Before Sync] DB 현재 값: {}", characterBeforeSync.getLikeCount());


        // When 2: 스케줄러가 돌 때까지 대기 (약 3~4초)
        log.info("💤 스케줄러가 데이터를 DB에 밀어넣기를 기다립니다... (4초 대기)");
        Thread.sleep(4000); // Scheduler가 3초 주기라면 넉넉히 4초 대기


        // Then 3: 최종적으로 DB에 1000개가 정확히 반영되었는지 확인
        entityManager.clear(); // 중요: 1차 캐시 비우고 다시 조회
        GameCharacter characterAfterSync = gameCharacterRepository.findByUserIgn(targetUserIgn).orElseThrow();

        log.info("✅ [After Sync] DB 최종 값: {}", characterAfterSync.getLikeCount());

        assertEquals(userCount, characterAfterSync.getLikeCount(),
                "스케줄러에 의해 1000개의 좋아요가 유실 없이 DB에 반영되어야 합니다.");
    }

}
