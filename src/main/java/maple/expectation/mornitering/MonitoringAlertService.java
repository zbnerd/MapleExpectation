package maple.expectation.mornitering;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.CommonErrorCode; // ✅ 추가
import maple.expectation.global.error.exception.MonitoringException; // ✅ 추가
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.repository.v2.RedisBufferRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MonitoringAlertService {

    private final RedisBufferRepository redisBufferRepository;
    private final DiscordAlertService discordAlertService;
    private final LockStrategy lockStrategy;

    @Scheduled(fixedRate = 5000)
    public void checkBufferSaturation() {
        try {
            // 리더 선출: 한 대의 인스턴스만 전역 수치를 체크
            lockStrategy.executeWithLock("global-monitoring-lock", 0, 4, () -> {

                long globalPending = redisBufferRepository.getTotalPendingCount();

                // 💡 전역 임계치 초과 시 구조화된 비즈니스 예외 발생
                if (globalPending > 5000) {
                    MonitoringException exception = new MonitoringException(
                            CommonErrorCode.SYSTEM_CAPACITY_EXCEEDED,
                            globalPending
                    );

                    // Discord 알림 시에도 가공된 메시지와 예외 객체를 전달
                    discordAlertService.sendCriticalAlert(
                            "🚨 GLOBAL BUFFER SATURATION",
                            exception.getMessage(),
                            exception
                    );

                    // 로그에도 구조화된 에러 로그 출력
                    log.warn("[{}] {}", exception.getErrorCode().getCode(), exception.getMessage());
                }
                return null;
            });
        } catch (Throwable t) {
            // DistributedLockException(락 획득 실패) 등은 스킵하여 알림 중복 방지
            log.trace("⏭️ [Monitoring] 리더 권한이 없어 체크를 스킵합니다.");
        }
    }
}