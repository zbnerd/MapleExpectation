package maple.expectation.scheduler;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.global.lock.LockStrategy; // 👈 추가
import maple.expectation.service.v2.LikeSyncService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LikeSyncScheduler {

    private final LikeSyncService likeSyncService;
    private final LockStrategy lockStrategy;

    /**
     * 🚀 [미션 A] 로컬 데이터를 중앙으로 집결 (L1 -> L2)
     * 1초마다 실행: 모든 서버가 예외 없이 자기 로컬 버퍼를 Redis로 쏩니다.
     */
    @Scheduled(fixedRate = 1000)
    public void localFlush() {
        likeSyncService.flushLocalToRedis();
    }

    /**
     * 🚀 [미션 B] 중앙 데이터를 DB로 반영 (L2 -> L3)
     * 3초마다 실행: 오직 분산 락을 획득한 한 대의 서버만 수행합니다.
     */
    @Scheduled(fixedRate = 3000)
    public void globalSync() {
        try {
            // "like-db-sync-lock"을 잡은 서버만 Redis->DB 동기화 수행
            lockStrategy.executeWithLock("like-db-sync-lock", 0, 10, () -> {
                likeSyncService.syncRedisToDatabase();
                return null;
            });
        } catch (DistributedLockException e) {
            // 락을 못 잡은 서버는 평화롭게 다음 주기를 기다립니다. ㅋㅋㅋ
            log.debug("⏭️ 리더 서버가 이미 동기화 중입니다. 작업을 건너뜁니다.");
        }
    }
}