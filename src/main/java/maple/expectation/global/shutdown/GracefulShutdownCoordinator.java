package maple.expectation.global.shutdown;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.service.v2.LikeSyncService;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GracefulShutdownCoordinator {

    private final LikeSyncService likeSyncService;
    private final LockStrategy lockStrategy;

    @PreDestroy
    public void coordinate() {
        log.warn("========= [System Shutdown] 종료 절차 시작 =========");

        // 1. 로컬 데이터 Flush (모든 인스턴스 수행)
        try {
            log.info("▶️ [1/2] 로컬 좋아요 버퍼 Flush 중...");
            likeSyncService.flushLocalToRedis();
            log.info("✅ 로컬 데이터 전송 완료.");
        } catch (Exception e) {
            log.error("❌ 로컬 Flush 실패", e);
        }

        // 2. 리더 서버인 경우 DB 최종 동기화 (단일 인스턴스 수행)
        try {
            log.info("▶️ [2/2] DB 최종 동기화 시도 중...");
            lockStrategy.executeWithLock("like-db-sync-lock", 0, 5, () -> {
                likeSyncService.syncRedisToDatabase();
                return null;
            });
            log.info("✅ DB 최종 동기화 완료.");
        } catch (Exception e) {
            log.info("ℹ️ DB 최종 동기화는 타 서버에서 처리되었거나 권한이 없습니다.");
        }

        log.info("========= [System Shutdown] 종료 완료 =========");
    }
}