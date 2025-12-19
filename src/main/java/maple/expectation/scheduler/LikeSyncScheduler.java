package maple.expectation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class LikeSyncScheduler {

    private final LikeBufferStorage likeBufferStorage;
    private final GameCharacterRepository gameCharacterRepository;

    @Scheduled(fixedRate = 3000)
    @Transactional
    public void syncLikesToDatabase() {
        likeBufferStorage.getCache().asMap().forEach((userIgn, atomicCount) -> {
            long countToAdd = atomicCount.getAndSet(0); // 원자적 리셋

            if (countToAdd > 0) {
                try {
                    gameCharacterRepository.incrementLikeCount(userIgn, countToAdd);
                    log.info("[Sync] {} 님의 좋아요 {}개 DB 반영 완료", userIgn, countToAdd);
                } catch (Exception e) {
                    log.error("[Sync Error] {} 반영 실패. 데이터 복구", userIgn);
                    atomicCount.addAndGet(countToAdd); // 실패 시 복구
                }
            }
        });
    }
}