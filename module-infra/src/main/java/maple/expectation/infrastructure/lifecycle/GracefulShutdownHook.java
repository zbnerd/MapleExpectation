package maple.expectation.infrastructure.lifecycle;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.shutdown.ShutdownProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Graceful Shutdown Hook (ADR-008)
 *
 * <h3>역할</h3>
 *
 * <ul>
 *   <li>SmartLifecycle 기반 30초 타임아웃으로 안전 종료
 *   <li>Phase 기반 순차 종료 조정
 *   <li>ShutdownCoordinator를 통한 4단계 종료 실행
 * </ul>
 *
 * <h3>Phase 설정</h3>
 *
 * <p>Integer.MAX_VALUE: 가장 늦게 종료하여 다른 LifecycleBean들이 먼저 완료되도록 함
 *
 * <h3>CLAUDE.md 준수</h3>
 *
 * <ul>
 *   <li>Section 4: SmartLifecycle 인터페이스 구현
 *   <li>Section 11: 예외 계층 구조
 *   <li>Section 12: LogicExecutor 사용 (try-catch 금지)
 * </ul>
 *
 * @see ShutdownCoordinator
 * @see OutboxDrainOnShutdown
 */
@Slf4j
@Component
public class GracefulShutdownHook implements SmartLifecycle {

  private final ShutdownCoordinator coordinator;
  private final LogicExecutor executor;
  private final ShutdownProperties properties;

  private final Timer shutdownTimer;
  private final Counter shutdownSuccessCounter;
  private final Counter shutdownTimeoutCounter;

  /** SmartLifecycle 실행 상태 플래그 */
  private volatile boolean running = false;

  public GracefulShutdownHook(
      @Lazy ShutdownCoordinator coordinator,
      LogicExecutor executor,
      ShutdownProperties properties,
      MeterRegistry meterRegistry) {
    this.coordinator = coordinator;
    this.executor = executor;
    this.properties = properties;
    // Handle null MeterRegistry (may not be available in test environment)
    this.shutdownTimer =
        meterRegistry != null
            ? Timer.builder("shutdown.hook.duration")
                .description("Graceful Shutdown Hook 총 소요 시간")
                .register(meterRegistry)
            : null;
    this.shutdownSuccessCounter =
        meterRegistry != null
            ? Counter.builder("shutdown.hook.result")
                .tag("status", "success")
                .description("Shutdown 성공 횟수")
                .register(meterRegistry)
            : null;
    this.shutdownTimeoutCounter =
        meterRegistry != null
            ? Counter.builder("shutdown.hook.result")
                .tag("status", "timeout")
                .description("Shutdown 타임아웃 횟수")
                .register(meterRegistry)
            : null;
  }

  @Override
  public void start() {
    this.running = true;
    log.debug("[GracefulShutdownHook] Started");
  }

  /**
   * Graceful Shutdown 실행
   *
   * <h4>타임아웃 처리</h4>
   *
   * <p>30초 타임아웃 내에 Coordinator가 완료되지 않으면 타임아웃 기록
   */
  @Override
  public void stop() {
    TaskContext context = TaskContext.of("GracefulShutdownHook", "Main");
    long startNanos = System.nanoTime();

    executor.executeWithFinally(
        () -> {
          log.warn("[GracefulShutdownHook] =============== Shutdown 시작 ===============");

          // 타임아웃 제어된 실행
          boolean completed = executeWithTimeout();

          if (completed) {
            shutdownSuccessCounter.increment();
            log.warn("[GracefulShutdownHook] =============== Shutdown 완료 ===============");
          } else {
            shutdownTimeoutCounter.increment();
            log.error("[GracefulShutdownHook] =============== Shutdown 타임아웃 ===============");
          }

          return null;
        },
        () -> {
          this.running = false;
          shutdownTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        },
        context);
  }

  /**
   * 타임아웃 제어된 Coordinator 실행
   *
   * @return 완료 여부
   */
  private boolean executeWithTimeout() {
    return executor.executeOrDefault(
        () -> {
          long deadlineNs = System.nanoTime() + Duration.ofSeconds(30).toNanos();

          // Coordinator 실행 (별도 스레드로 실행하여 타임아웃 감지)
          Thread coordinatorThread =
              new Thread(
                  () -> {
                    try {
                      coordinator.executeShutdown();
                    } catch (Exception e) {
                      log.error("[GracefulShutdownHook] Coordinator 실행 실패", e);
                    }
                  },
                  "shutdown-coordinator");

          coordinatorThread.start();

          // 타임아웃 대기
          long remainingNs;
          while ((remainingNs = deadlineNs - System.nanoTime()) > 0) {
            try {
              TimeUnit.NANOSECONDS.timedJoin(coordinatorThread, remainingNs);
              if (!coordinatorThread.isAlive()) {
                return true; // 완료
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              log.warn("[GracefulShutdownHook] 대기 중 인터럽트");
              return false;
            }
          }

          // 타임아웃 발생
          if (coordinatorThread.isAlive()) {
            log.error("[GracefulShutdownHook] Coordinator 타임아웃 - 강제 종료 예정");
            coordinatorThread.interrupt();
            return false;
          }

          return true;
        },
        false,
        TaskContext.of("GracefulShutdownHook", "ExecuteWithTimeout"));
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /**
   * Phase 설정: Integer.MAX_VALUE
   *
   * <p>가장 늦게 종료하여 다른 LifecycleBean들이 먼저 완료되도록 함
   */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }
}
