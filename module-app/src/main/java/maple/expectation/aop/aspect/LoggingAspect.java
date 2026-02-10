package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.collector.PerformanceStatisticsCollector;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 실행 시간 로깅 Aspect (TaskContext 및 평탄화 적용)
 *
 * <h3>#271 V5 Stateless Architecture</h3>
 *
 * <p>SmartLifecycle을 구현하여 Graceful Shutdown 시 통계를 출력합니다. Phase가 낮아 다른 컴포넌트보다 나중에 종료됩니다.
 *
 * <h3>Issue #283 P0-6: Scale-out Safety</h3>
 *
 * <p>{@code running} 플래그는 인스턴스별 SmartLifecycle 상태로, 분산 환경에서 각 인스턴스가 독립적으로 관리하는 것이 올바른 설계입니다.
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class LoggingAspect implements SmartLifecycle {

  private final PerformanceStatisticsCollector statsCollector;
  private final LogicExecutor executor;

  private volatile boolean running = false;

  /**
   * 메서드 실행 시간 로깅 (코드 평탄화 적용)
   *
   * <p>TaskContext를 통해 메트릭 카디널리티를 통제하며 체크 예외 노이즈를 제거합니다.
   */
  @Around("@annotation(maple.expectation.aop.annotation.LogExecutionTime)")
  public Object logExecutionTime(ProceedingJoinPoint joinPoint) {
    // Issue #283 P0-6: Graceful Shutdown 중에는 성능 기록 스킵
    if (!running) {
      return executor.execute(joinPoint::proceed, TaskContext.of("Logging", "ShutdownBypass"));
    }

    String methodName = joinPoint.getSignature().toShortString();
    long start = System.currentTimeMillis();

    // ✅ 수정: String 대신 TaskContext 사용 (Component="Logging", Operation="ExecutionTime")
    TaskContext context = TaskContext.of("Logging", "ExecutionTime", methodName);

    return executor.executeWithFinally(
        joinPoint::proceed, () -> this.recordExecutionTime(methodName, start), context);
  }

  /** 실행 시간 기록 (평탄화: 별도 메서드로 분리) */
  private void recordExecutionTime(String methodName, long start) {
    long executionTime = System.currentTimeMillis() - start;
    statsCollector.addTime(methodName, executionTime);
  }

  public String[] getStatistics(String testName) {
    return statsCollector.calculateStatistics(testName);
  }

  public void resetStatistics() {
    log.warn("🔄 Micrometer 통계는 수동으로 리셋되지 않습니다. Prometheus 대시보드를 확인하세요.");
  }

  // ==================== SmartLifecycle Implementation ====================

  @Override
  public void start() {
    this.running = true;
    log.debug("[LoggingAspect] Started");
  }

  /**
   * Graceful Shutdown 시 최종 통계 출력
   *
   * <p>#271 V5: @PreDestroy 대신 SmartLifecycle.stop() 사용
   */
  @Override
  public void stop() {
    printFinalStatistics();
    this.running = false;
  }

  /** 최종 통계 출력 (내부 헬퍼) */
  private void printFinalStatistics() {
    String[] stats = statsCollector.calculateStatistics("애플리케이션 전체 운영");
    log.info("========================================================");
    for (String stat : stats) {
      log.info(stat);
    }
    log.info("========================================================");
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /**
   * 다른 Shutdown 컴포넌트보다 나중에 종료 (낮은 phase)
   *
   * <p>GracefulShutdownCoordinator (MAX-1000) 이후 실행
   */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 2000;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }
}
