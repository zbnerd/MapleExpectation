package maple.expectation.service.v2.facade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.global.error.exception.ExternalServiceException;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameCharacterFacade {

    private final GameCharacterService gameCharacterService;
    private final RedissonClient redissonClient;

    /**
     * 🚀 기존 메서드명 유지: 다른 코드 수정 불필요
     */
    public GameCharacter findCharacterByUserIgn(String userIgn) {
        String cleanUserIgn = userIgn.trim();

        // 1. 이미 존재하거나 Blacklist(Negative Cache)에 있는지 우선 확인
        if (gameCharacterService.isNonExistent(cleanUserIgn)) {
            throw new CharacterNotFoundException(cleanUserIgn);
        }

        return gameCharacterService.getCharacterIfExist(cleanUserIgn)
                .orElseGet(() -> waitForWorkerResult(cleanUserIgn));
    }

    /**
     * 큐에 작업을 넣고 Pub/Sub 신호를 기다리는 내부 로직
     */
    private GameCharacter waitForWorkerResult(String userIgn) {
        RTopic topic = redissonClient.getTopic("char_event:" + userIgn);
        CompletableFuture<GameCharacter> future = new CompletableFuture<>();

        // 1. 결과 방송 구독 (리스너 등록)
        int listenerId = topic.addListener(String.class, (channel, msg) -> {
            if ("DONE".equals(msg)) {
                gameCharacterService.getCharacterIfExist(userIgn).ifPresent(future::complete);
            } else if ("NOT_FOUND".equals(msg)) {
                future.completeExceptionally(new CharacterNotFoundException(userIgn));
            }
        });

        try {
            // 2. 큐에 작업 등록 (워커가 이 큐를 보고 처리함)
            RBlockingQueue<String> queue = redissonClient.getBlockingQueue("character_job_queue");
            queue.offer(userIgn);
            log.info("📥 [Queue Enqueue] 작업 등록 및 대기: {}", userIgn);

            // 3. 최대 10초간 결과 대기
            return future.get(10, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("⏳ [Timeout] 캐릭터 생성 대기 실패: {}", userIgn);
            throw new ExternalServiceException("현재 요청이 많습니다. 잠시 후 다시 확인해주세요.");
        } finally {
            // 4. 리스너 해제 (메모리 누수 방지)
            topic.removeListener(listenerId);
        }
    }
}