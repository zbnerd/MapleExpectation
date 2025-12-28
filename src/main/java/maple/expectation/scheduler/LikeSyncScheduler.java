package maple.expectation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.service.v2.LikeSyncService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LikeSyncScheduler {

    private final LikeSyncService likeSyncService;
    private final LockStrategy lockStrategy;

    @Scheduled(fixedRate = 1000)
    public void localFlush() {
        likeSyncService.flushLocalToRedis();
    }

    @Scheduled(fixedRate = 3000)
    public void globalSync() {
        try {
            lockStrategy.executeWithLock("like-db-sync-lock", 0, 10, () -> {
                likeSyncService.syncRedisToDatabase();
                return null;
            });
        } catch (DistributedLockException ignored) {}
    }
}