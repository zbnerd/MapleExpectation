package maple.expectation.service.v2;

import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import maple.expectation.service.v2.like.event.LikeSyncFailedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class LikeSyncService {

    private final LikeBufferStorage likeBufferStorage;
    private final LikeSyncExecutor syncExecutor;
    private final ApplicationEventPublisher eventPublisher;
    private final Retry likeSyncRetry;


    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;

    @ObservedTransaction("scheduler.like.sync")
    public void syncLikesToDatabase() {
        Map<String, AtomicLong> bufferMap = likeBufferStorage.getCache().asMap();
        if (bufferMap.isEmpty()) return;

        bufferMap.forEach(this::syncWithRetry);
    }

    private void syncWithRetry(String userIgn, AtomicLong atomicCount) {
        long countToAdd = atomicCount.getAndSet(0);
        if (countToAdd <= 0) return;

        try {
            Retry.decorateRunnable(likeSyncRetry, () ->
                    syncExecutor.executeIncrement(userIgn, countToAdd)
            ).run();
        } catch (Exception e) {
            handleFinalFailure(userIgn, atomicCount, countToAdd, e);
        }
    }

    private void handleFinalFailure(String userIgn, AtomicLong atomicCount, long count, Exception e) {
        atomicCount.addAndGet(count); // 데이터 복구
        eventPublisher.publishEvent(new LikeSyncFailedEvent(userIgn, count, MAX_RETRIES, e));
    }
}