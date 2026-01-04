package maple.expectation.service.v2.facade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.global.error.exception.ExternalServiceException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.service.v2.GameCharacterService;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameCharacterFacade {

    private final GameCharacterService gameCharacterService;
    private final RedissonClient redissonClient;
    private final LogicExecutor executor;

    public GameCharacter findCharacterByUserIgn(String userIgn) {
        String cleanUserIgn = userIgn.trim();
        TaskContext context = TaskContext.of("CharacterFacade", "FindCharacter", cleanUserIgn);

        return executor.execute(() -> {
            if (gameCharacterService.isNonExistent(cleanUserIgn)) {
                throw new CharacterNotFoundException(cleanUserIgn);
            }

            return gameCharacterService.getCharacterIfExist(cleanUserIgn)
                    .orElseGet(() -> waitForWorkerResult(cleanUserIgn));
        }, context);
    }

    private GameCharacter waitForWorkerResult(String userIgn) {
        RTopic topic = redissonClient.getTopic("char_event:" + userIgn);
        CompletableFuture<GameCharacter> future = new CompletableFuture<>();
        TaskContext context = TaskContext.of("CharacterFacade", "WaitWorker", userIgn);

        int listenerId = topic.addListener(String.class, (channel, msg) -> {
            if ("DONE".equals(msg)) {
                gameCharacterService.getCharacterIfExist(userIgn).ifPresent(future::complete);
            } else if ("NOT_FOUND".equals(msg)) {
                future.completeExceptionally(new CharacterNotFoundException(userIgn));
            }
        });

        return executor.executeWithFinally(
                () -> {
                    performQueueOffer(userIgn);
                    // ✅ TaskContext를 전달하여 하위 메서드에서 예외 번역 시 활용
                    return awaitFuture(future, userIgn, context);
                },
                () -> topic.removeListener(listenerId),
                context
        );
    }

    private void performQueueOffer(String userIgn) {
        RBlockingQueue<String> queue = redissonClient.getBlockingQueue("character_job_queue");
        queue.offer(userIgn);
        log.info("📥 [Queue Enqueue] 작업 등록: {}", userIgn);
    }

    /**
     * ✅  executeWithTranslation을 사용하여 try-catch 완전 제거
     * 기술적 예외(Timeout 등)를 도메인 예외(ExternalServiceException)로 즉시 세탁합니다.
     */
    private GameCharacter awaitFuture(CompletableFuture<GameCharacter> future, String userIgn, TaskContext context) {
        return executor.executeWithTranslation(
                // 🚀 1. 작업: Checked Exception을 그대로 던지게 둡니다.
                () -> future.get(10, TimeUnit.SECONDS),

                // 🚀 2. 번역: 발생한 Throwable을 여기서 요리합니다.
                (e, ctx) -> {
                    // 비동기 실행 중 발생한 실제 원인(cause)을 추출합니다.
                    Throwable cause = (e instanceof ExecutionException) ? e.getCause() : e;

                    // 이미 도메인 예외(404 등)라면 그대로 던집니다.
                    if (cause instanceof CharacterNotFoundException) {
                        return (CharacterNotFoundException) cause;
                    }

                    // 그 외 기술적 예외(TimeoutException, InterruptedException 등) 처리
                    log.error("⏳ [Timeout/Error] 캐릭터 생성 대기 실패 (IGN: {}): {}", userIgn, cause.getMessage());
                    return new ExternalServiceException("현재 요청이 많습니다. 잠시 후 다시 확인해주세요.");
                },
                context
        );
    }

    public GameCharacter findCharacterWithCache(String userIgn) {
        return findCharacterByUserIgn(userIgn);
    }
}