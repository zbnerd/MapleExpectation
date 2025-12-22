package maple.expectation.scheduler;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.service.v2.LikeSyncService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LikeSyncScheduler {

    private final LikeSyncService likeSyncService;

    /**
     * 3초마다 주기적으로 동기화 수행
     */
    @Scheduled(fixedRate = 3000)
    public void scheduledSync() {
        likeSyncService.syncLikesToDatabase();
    }

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