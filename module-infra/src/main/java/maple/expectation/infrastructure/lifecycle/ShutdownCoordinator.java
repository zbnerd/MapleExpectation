package maple.expectation.infrastructure.lifecycle;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Graceful Shutdown 조정자 (ADR-008)
 *
 * <h3>역할</h3>
 *
 * <ul>
 *   <li>SmartLifecycle Bean들의 순차적 종료 조정
 *   <li>Phase 기반 종료 순서 보장
 *   <li>각 LifecycleBean의 완료 대기 및 타임아웃 감지
 * </ul>
 *
 * <h3>Phase 순서</h3>
 *
 * <ol>
 *   <li>OutboxDrainOnShutdown: MAX_VALUE - 1500 (가장 먼저)
 *   <li>ExpectationBatchShutdownHandler: MAX_VALUE - 500
 *   <li>GracefulShutdownCoordinator (기존): MAX_VALUE - 1000
 *   <li>GracefulShutdownHook: MAX_VALUE (가장 늦게)
 * </ol>
 *
 * <h3>CLAUDE.md 준수</h3>
 *
 * <ul>
 *   <li>Section 4: SmartLifecycle 인터페이스 구현
 *   <li>Section 11: 예외 계층 구조
 *   <li>Section 12: LogicExecutor 사용 (try-catch 금지)
 * </ul>
 *
 * @see GracefulShutdownHook
 * @see OutboxDrainOnShutdown
 */
@Slf4j
@Component
public class ShutdownCoordinator {

  private final List<SmartLifecycle> lifecycleBeans;
  private final LogicExecutor executor;
  private final MeterRegistry meterRegistry;

  private final Counter phaseSuccessCounter;
  private final Counter phaseFailureCounter;

  public ShutdownCoordinator(
      List<SmartLifecycle> lifecycleBeans, LogicExecutor executor, MeterRegistry meterRegistry) {
    this.lifecycleBeans = lifecycleBeans;
    this.executor = executor;
    this.meterRegistry = meterRegistry;
    this.phaseSuccessCounter =
        meterRegistry != null
            ? Counter.builder("shutdown.coordinator.phase")
                .tag("status", "success")
                .description("Phase 성공 횟수")
                .register(meterRegistry)
            : null;
    this.phaseFailureCounter =
        meterRegistry != null
            ? Counter.builder("shutdown.coordinator.phase")
                .tag("status", "failure")
                .description("Phase 실패 횟수")
                .register(meterRegistry)
            : null;
  }

  /**
   * 순차적 Shutdown 실행
   *
   * <h4>실행 순서</h4>
   *
   * <p>Phase 오름차순으로 정렬하여 낮은 Phase(먼저 종료할 것)부터 실행
   */
  public void executeShutdown() {
    executor.executeVoid(
        () -> {
          log.warn(
              "[ShutdownCoordinator] =========== 4단계 Shutdown 시작 ({}개 Lifecycle Bean) ===========",
              lifecycleBeans.size());

          // Phase 오름차순 정렬 (낮은 Phase 먼저 = 먼저 종료할 것 먼저)
          List<SmartLifecycle> sortedBeans =
              lifecycleBeans.stream()
                  .filter(SmartLifecycle::isRunning)
                  .sorted((a, b) -> Integer.compare(a.getPhase(), b.getPhase()))
                  .toList();

          log.info(
              "[ShutdownCoordinator] Phase 순서: {}",
              sortedBeans.stream()
                  .map(b -> b.getClass().getSimpleName() + "(" + b.getPhase() + ")")
                  .toList());

          int phaseIndex = 0;
          for (SmartLifecycle bean : sortedBeans) {
            phaseIndex++;
            String beanName = bean.getClass().getSimpleName();
            int phase = bean.getPhase();

            log.info(
                "[ShutdownCoordinator] Phase [{}/4]: {} 실행 (phase={})",
                phaseIndex,
                beanName,
                phase);

            boolean success = executePhase(bean);
            if (success) {
              phaseSuccessCounter.increment();
              log.info("[ShutdownCoordinator] Phase [{}/4]: {} 완료", phaseIndex, beanName);
            } else {
              phaseFailureCounter.increment();
              log.error("[ShutdownCoordinator] Phase [{}/4]: {} 실패", phaseIndex, beanName);
            }
          }

          log.warn("[ShutdownCoordinator] =========== 4단계 Shutdown 완료 ===========");
        },
        TaskContext.of("ShutdownCoordinator", "Main"));
  }

  /**
   * 개별 Phase 실행
   *
   * @return 완료 여부
   */
  private boolean executePhase(SmartLifecycle bean) {
    return executor.executeOrDefault(
        () -> {
          String beanName = bean.getClass().getSimpleName();

          try {
            // stop() 실행 (동기)
            bean.stop();

            // 완료 대기 (최대 5초)
            long deadline = System.currentTimeMillis() + 5000;
            while (bean.isRunning() && System.currentTimeMillis() < deadline) {
              Thread.sleep(100);
            }

            if (bean.isRunning()) {
              log.error("[ShutdownCoordinator] {} 타임아웃 (5초 경과)", beanName);
              return false;
            }

            return true;
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[ShutdownCoordinator] {} 실행 중 인터럽트", beanName, e);
            return false;
          } catch (Exception e) {
            log.error("[ShutdownCoordinator] {} 실행 실패", beanName, e);
            return false;
          }
        },
        false,
        TaskContext.of("ShutdownCoordinator", "ExecutePhase", bean.getClass().getSimpleName()));
  }
}
