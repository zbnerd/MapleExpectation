package maple.expectation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class LikeSyncScheduler {

    private final LikeBufferStorage likeBufferStorage;
    private final GameCharacterRepository gameCharacterRepository;

    @Scheduled(fixedRate = 3000) // 3초마다 실행
    @Transactional
    @ObservedTransaction("scheduler.like.sync")
    public void syncLikesToDatabase() {
        Map<String, AtomicLong> bufferMap = likeBufferStorage.getCache().asMap();

        if (bufferMap.isEmpty()) return;

        log.debug("[Sync] 데이터 동기화 시작 (대상 유저 수: {})", bufferMap.size());

        bufferMap.forEach((userIgn, atomicCount) -> {
            long countToAdd = atomicCount.getAndSet(0); // 원자적 리셋

            if (countToAdd > 0) {
                try {
                    gameCharacterRepository.incrementLikeCount(userIgn, countToAdd);
                } catch (Exception e) {
                    log.error("[Sync Error] {} 반영 실패. 데이터 복구 시도", userIgn, e);
                    atomicCount.addAndGet(countToAdd); // 실패 시 다시 버퍼에 복구
                }
            }
        });
    }
}