package maple.expectation.monitoring;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.MonitoringException;
import maple.expectation.global.executor.LogicExecutor; // ✅ 주입
import maple.expectation.global.executor.TaskContext; // ✅ 관측성 확보
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
    private final LogicExecutor executor; // ✅ 지능형 실행 엔진 주입

    /**
     * ✅  버퍼 포화도 체크 로직 평탄화
     * try-catch 대신 executeWithRecovery를 사용하여 리더 선출 실패를 우아하게 처리합니다.
     */
    @Scheduled(fixedRate = 5000)
    public void checkBufferSaturation() {
        TaskContext context = TaskContext.of("Monitoring", "CheckSaturation"); //

        // [패턴 5] executeWithRecovery: 락 획득 실패(Follower)는 스킵하고, 실제 장애는 로그로 기록
        executor.executeWithRecovery(() -> {
            lockStrategy.executeWithLock("global-monitoring-lock", 0, 4, () -> {
                performBufferCheck(); // 비즈니스 로직 분리 (평탄화)
                return null;
            });
            return null;
        }, (e) -> {
            handleMonitoringFailure(e); // 장애 대응 로직 격리
            return null;
        }, context);
    }

    /**
     * 헬퍼 1: 실제 수치 확인 및 알림 로직 (로직 응집도 향상)
     */
    private void performBufferCheck() {
        long globalPending = redisBufferRepository.getTotalPendingCount();

        if (globalPending > 5000) {
            MonitoringException exception = new MonitoringException(
                    CommonErrorCode.SYSTEM_CAPACITY_EXCEEDED,
                    globalPending
            );

            // [패턴 1] executeVoid: 외부 알림 발송 과정도 실행기로 보호하여 관측성 확보
            executor.executeVoid(() -> {
                discordAlertService.sendCriticalAlert(
                        "🚨 GLOBAL BUFFER SATURATION",
                        exception.getMessage(),
                        exception
                );
                log.warn("[{}] {}", exception.getErrorCode().getCode(), exception.getMessage());
            }, TaskContext.of("Alert", "SendDiscord", String.valueOf(globalPending)));
        }
    }

    /**
     * 헬퍼 2: 모니터링 실패 대응 (평탄화 보조)
     */
    private void handleMonitoringFailure(Throwable t) {
        // [분기 1] DistributedLockException: 리더가 아님 (정상 시나리오)
        if (t instanceof maple.expectation.global.error.exception.DistributedLockException) {
            log.trace("⏭️ [Monitoring] 리더 권한이 없어 체크를 스킵합니다.");
            return;
        }

        // [분기 2] 그 외 실제 장애 (Redis 연결 오류 등)
        log.error("❌ [Monitoring] 버퍼 모니터링 중 장애 발생: {}", t.getMessage());
    }
}