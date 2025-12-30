package maple.expectation.service.v2.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Scheduled(fixedDelay = 100) // 0.1초당 1개씩 처리 (넥슨 429 방어의 핵심)
    public void processJob() {
        RBlockingQueue<String> queue = redissonClient.getBlockingQueue("character_job_queue");
        String userIgn = queue.poll(); // 큐에서 이름 하나 꺼내기

        if (userIgn != null) {
            RTopic topic = redissonClient.getTopic("char_event:" + userIgn);
            try {
                // 1. 이미 DB에 생겼는지 최종 확인
                if (gameCharacterService.getCharacterIfExist(userIgn).isPresent()) {
                    topic.publish("DONE");
                    return;
                }

                // 2. 서비스 호출 (이제 API 쏘고 DB/캐시 저장까지 서비스가 다 함)
                gameCharacterService.createNewCharacter(userIgn);

                // 3. 기다리는 Facade에게 "끝났어!" 방송
                topic.publish("DONE");

            } catch (CharacterNotFoundException e) {
                // "진짜 없는 캐릭터"라고 방송
                topic.publish("NOT_FOUND");
            } catch (Exception e) {
                log.error("❌ 워커 처리 중 일시적 오류: {}", userIgn, e);
                // 429 에러 등은 방송하지 않음 -> Facade는 타임아웃 나고 유저는 다시 시도하게 됨
            }
        }
    }
}