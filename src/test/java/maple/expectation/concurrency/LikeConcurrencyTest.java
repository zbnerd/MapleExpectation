package maple.expectation.concurrency;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.GameCharacter;
import maple.expectation.repository.GameCharacterRepository;
import maple.expectation.service.GameCharacterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@Slf4j
@Transactional
@SpringBootTest
public class LikeConcurrencyTest {

    @Autowired
    private GameCharacterRepository gameCharacterRepository;

    @Autowired
    GameCharacterService gameCharacterService;

    private String targetUserIgn;

    @BeforeEach
    void setUp() {
        // 테스트용 타겟 캐릭터 생성 (좋아요 0개)
        GameCharacter target = new GameCharacter("테스트유저_Geek");
        gameCharacterRepository.save(target);
        targetUserIgn = target.getUserIgn();
    }

    @AfterEach
    void tearDown() {
        // 테스트용 데이터만 삭제
        gameCharacterRepository.delete(gameCharacterRepository.findByUserIgn(targetUserIgn));
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

        GameCharacter c = gameCharacterRepository.findByUserIgn(targetUserIgn);
        log.info("❌ [No Lock] 최종 좋아요: {}", c.getLikeCount());

        // 100이 아니면 테스트 통과 (문제가 발생했음을 증명!)
        assertNotEquals(100L, c.getLikeCount());
    }

    @Test
    @DisplayName("✅ 2. [비관적 락] 100명이 동시에 좋아요 -> 정확히 100개 (성공)")
    void likeWithLock() throws InterruptedException {
        int userCount = 100;
        // 32개의 스레드 풀 생성 (동시 접속자 흉내)
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        // 100명이 다 준비될 때까지 기다리는 신호총 (Latch)
        CountDownLatch latch = new CountDownLatch(userCount);

        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    gameCharacterService.clickLikeWithLock(targetUserIgn); // 락 없는 메서드 호출
                } finally {
                    latch.countDown(); // 완료 신호 보냄
                }
            });
        }
        latch.await(); // 모든 스레드가 끝날 때까지 대기

        GameCharacter c = gameCharacterRepository.findByUserIgn(targetUserIgn);
        log.info("✅ [Pessimistic Lock] 최종 좋아요: {}", c.getLikeCount());

        // 정확히 100개여야 성공
        assertEquals(100L, c.getLikeCount());
    }
}
