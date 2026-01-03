package maple.expectation.service.v2.facade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.service.v2.GameCharacterService;
import org.redisson.api.RCountDownLatch;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
@Slf4j
public class GameCharacterSynchronizer {

    private final GameCharacterService gameCharacterService;
    private final org.redisson.api.RedissonClient redissonClient;

    public GameCharacter synchronizeCharacter(String userIgn) {
        String cleanUserIgn = userIgn.trim();

        // 1. Negative Cache 사전 차단 (넥슨 API 호출 전 컷)
        if (gameCharacterService.isNonExistent(cleanUserIgn)) {
            throw new CharacterNotFoundException(cleanUserIgn);
        }

        // 2. 분산 래치 생성
        RCountDownLatch latch = redissonClient.getCountDownLatch("latch:char:" + cleanUserIgn);

        // 3. 내가 대장(Leader)인가?
        boolean isLeader = latch.trySetCount(1);

        if (isLeader) {
            try {
                log.info("👑 [Leader] 신규 생성 주도: {}", cleanUserIgn);
                return gameCharacterService.createNewCharacter(cleanUserIgn);
            } finally {
                // 🚀 [Publish] 대기가 끝났음을 모든 팔로워에게 알림 (한 번에 깨움)
                latch.countDown();
                latch.delete();
            }
        } else {
            try {
                log.info("😴 [Follower] 대장을 기다림: {}", cleanUserIgn);
                // 4. [Subscribe] 대장이 종을 칠 때까지 대기 (최대 5초)
                boolean completed = latch.await(5, java.util.concurrent.TimeUnit.SECONDS);

                if (!completed) {
                    log.warn("⏰ [Follower Timeout] 대장이 너무 느려 직접 DB 확인: {}", cleanUserIgn);
                }

                // 깨어난 후 결과 반환 (대장이 이미 생성했거나 Negative Cache를 채웠을 것임)
                return gameCharacterService.getCharacterIfExist(cleanUserIgn)
                        .orElseThrow(() -> new CharacterNotFoundException(cleanUserIgn));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("작업 중단됨", e);
            }
        }
    }
}