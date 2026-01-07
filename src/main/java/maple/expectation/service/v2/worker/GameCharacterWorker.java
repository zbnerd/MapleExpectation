package maple.expectation.service.v2.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor; // ✅ 주입
import maple.expectation.global.executor.TaskContext; // ✅ 관측성
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.service.v2.GameCharacterService;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class GameCharacterWorker {
    private final RedissonClient redissonClient;
    private final GameCharacterService gameCharacterService;
    private final LogicExecutor executor; // ✅ 지능형 실행기 주입

    @Scheduled(fixedDelay = 100)
    public void processJob() {
        RBlockingQueue<String> queue = redissonClient.getBlockingQueue("character_job_queue");
        String userIgn = queue.poll();

        if (userIgn == null) return;

        TaskContext context = TaskContext.of("Worker", "ProcessJob", userIgn); //
        RTopic topic = redissonClient.getTopic("char_event:" + userIgn);

        // ✅  try-catch 제거 및 executeWithRecovery로 방송 시나리오 평탄화
        executor.executeOrCatch(
                () -> {
                    performCharacterCreation(userIgn, topic);
                    return null;
                },
                (e) -> {
                    handleWorkerFailure(userIgn, topic, e);
                    return null;
                },
                context
        );
    }

    /**
     * 헬퍼 1: 실제 생성 로직 및 성공 방송 (비즈니스 집중)
     */
    private void performCharacterCreation(String userIgn, RTopic topic) {
        // 1. 중복 처리 방지 확인
        if (gameCharacterService.getCharacterIfExist(userIgn).isPresent()) {
            topic.publish("DONE");
            return;
        }

        // 2. 서비스 호출 및 성공 방송
        gameCharacterService.createNewCharacter(userIgn);
        topic.publish("DONE");
        log.info("✅ [Worker Success] 캐릭터 생성 완료 및 방송: {}", userIgn);
    }

    /**
     * 헬퍼 2: 장애 대응 시나리오 (예외별 방송 여부 결정)
     */
    private void handleWorkerFailure(String userIgn, RTopic topic, Throwable e) {
        // [시나리오 A] 진짜 없는 캐릭터인 경우 -> 명시적으로 "NOT_FOUND" 알림
        if (e instanceof CharacterNotFoundException) {
            topic.publish("NOT_FOUND");
            log.warn("⚠️ [Worker] 존재하지 않는 캐릭터 방송: {}", userIgn);
            return;
        }

        // [시나리오 B] 429(Rate Limit)나 일시적 장애 -> 방송하지 않음 (Facade 타임아웃 유도)
        // 유저는 다시 시도하게 되며, 시스템의 부하를 조절합니다.
        log.error("❌ [Worker Error] 처리 중 일시적 오류 (침묵): {}", userIgn, e);
    }
}