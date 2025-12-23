package maple.expectation.scheduler;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.lock.LockStrategy; // 👈 추가
import maple.expectation.service.v2.LikeSyncService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LikeSyncScheduler {

    private final LikeSyncService likeSyncService;
    private final LockStrategy lockStrategy; // 👈 RedisDistributedLockStrategy가 주입됨

    /**
     * 3초마다 주기적으로 동기화 수행
     * 분산 환경에서 단 한 대의 서버만 실행하도록 락을 겁니다.
     */
    @Scheduled(fixedRate = 3000)
    public void scheduledSync() {
        // "like-sync-lock"이라는 키로 분산 락 획득 시도
        lockStrategy.executeWithLock("like-sync-lock", () -> {
            likeSyncService.syncLikesToDatabase();
            return null;
        });
    }

    /**
     * 서버 종료 시 호출
     * 종료 시에는 다른 서버와 상관없이 '내 서버'의 잔량 데이터를 무조건 Flush 해야 하므로 락을 걸지 않습니다.
     */
    @PreDestroy
    public void shutdownSync() {
        log.warn("⚠️ [Shutdown] 서버 종료 감지: 잔량 좋아요 데이터를 Flush합니다.");
        try {
            likeSyncService.syncLikesToDatabase();
            log.info("✅ [Shutdown] 모든 좋아요 데이터가 안전하게 DB에 동기화되었습니다.");
        } catch (Exception e) {
            log.error("❌ [Shutdown Error] 종료 중 동기화 실패!", e);
        }
    }
}