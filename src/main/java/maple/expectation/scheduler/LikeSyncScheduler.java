package maple.expectation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.service.v2.LikeSyncService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j // ✅ 로그 기록을 위해 추가
@Component
@RequiredArgsConstructor
public class LikeSyncScheduler {

    private final LikeSyncService likeSyncService;
    private final LockStrategy lockStrategy;

    @Scheduled(fixedRate = 1000)
    public void localFlush() {
        // 로컬 버퍼는 각 서버가 독립적으로 수행하므로 락이 필요 없습니다.
        likeSyncService.flushLocalToRedis();
    }

    @Scheduled(fixedRate = 3000)
    public void globalSync() {
        try {
            // ✅ 수정: ThrowingSupplier 대응 및 Throwable 캐치
            // waitTime 0: 락을 못 잡으면 즉시 포기하고 다음 1초 뒤를 기약합니다.
            lockStrategy.executeWithLock("like-db-sync-lock", 0, 10, () -> {
                likeSyncService.syncRedisToDatabase();
                return null;
            });
        } catch (Throwable t) {
            // 💡 스케줄러에서는 락 획득 실패(DistributedLockException)가 빈번하므로
            // 별도의 로그 없이 조용히 넘어가거나, 필요 시 debug 로그만 남깁니다.
            // 만약 진짜 에러(DB 장애 등)인지는 syncRedisToDatabase 내부 로그가 알려줄 것입니다.
        }
    }
}