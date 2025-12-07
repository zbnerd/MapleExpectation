package maple.expectation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class LikeSyncScheduler {

    private final LikeBufferStorage likeBufferStorage;
    private final GameCharacterRepository gameCharacterRepository;

    // 3초마다 실행 (비즈니스 요건에 따라 조절)
    @Scheduled(fixedRate = 3000)
    @Transactional
    public void syncLikesToDatabase() {
        likeBufferStorage.getCache().asMap().forEach((userIgn, atomicCount) -> {
            
            // 핵심: 현재 쌓인 값을 가져오면서 0으로 리셋 (Atomic 연산)
            // 만약 get() 하고 set(0)을 따로 하면, 그 사이에 들어온 클릭이 사라질 수 있음(Race Condition)
            long countToAdd = atomicCount.getAndSet(0);

            if (countToAdd > 0) {
                try {
                    // DB Bulk Update 실행
                    gameCharacterRepository.incrementLikeCount(userIgn, countToAdd);
                    log.info("[Sync] {} 님의 좋아요 {}개 DB 반영 완료", userIgn, countToAdd);
                } catch (Exception e) {
                    log.error("[Sync Error] {} 반영 실패. 다시 복구 시도", userIgn);
                    // 실패 시 다시 캐시에 더해주는 롤백 로직이 필요할 수 있음 (선택 사항)
                    atomicCount.addAndGet(countToAdd);
                }
            }
        });
    }
}