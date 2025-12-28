package maple.expectation.mornitering;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.lock.LockStrategy; // ✅ 추가
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class MonitoringAlertService {
    private final LikeBufferStorage likeBufferStorage;
    private final DiscordAlertService discordAlertService;
    private final LockStrategy lockStrategy; // ✅ 주입 추가

    @Scheduled(fixedRate = 5000) // 5초마다 체크
    public void checkBufferSaturation() {
        try {
            // 💡 리더 선출: 락을 획득한 단 한 대의 인스턴스만 모니터링 및 알림 수행
            // waitTime 0: 락 획득 실패 시 즉시 무시 (다른 서버가 이미 체크 중)
            lockStrategy.executeWithLock("monitoring-alert-lock", 0, 4, () -> {

                // 1. 현재 인스턴스 혹은 공유 저장소의 수치 계산
                // (이슈 #27의 취지에 따라 나중에는 Redis의 전역 수치를 가져오게 됩니다)
                long currentPending = likeBufferStorage.getCache().asMap().values().stream()
                        .mapToLong(AtomicLong::get).sum();

                log.debug("📊 [Monitoring] 버퍼 체크 수행 중: {}", currentPending);

                // 2. 임계치 초과 시 알림 발송
                if (currentPending > 2000) {
                    discordAlertService.sendCriticalAlert(
                            "BUFFER SATURATION WARNING",
                            "현재 시스템의 좋아요 버퍼 잔량이 임계치를 초과했습니다: " + currentPending,
                            new RuntimeException("System Load High (Detected by Leader)")
                    );
                }

                return null;
            });
        } catch (Throwable t) {
            // DistributedLockException 등이 발생하면 리더가 아니라고 판단하고 조용히 넘어감
            log.trace("⏭️ [Monitoring] 리더 권한이 없어 체크를 스킵합니다.");
        }
    }
}