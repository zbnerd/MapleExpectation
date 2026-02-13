package maple.expectation.config;

import maple.expectation.aop.context.SkipEquipmentL2CacheContext;
import org.springframework.core.task.TaskDecorator;

/**
 * Task Decorator Factory - MDC + Cache Context 전파용 TaskDecorator 생성
 *
 * <h4>책임</h4>
 *
 * <ul>
 *   <li>MDC (Mapped Diagnostic Context) 전파
 *   <li>SkipEquipmentL2CacheContext 전파
 *   <li>ThreadLocal 상태 스냅샷/복원 (snapshot/restore 패턴)
 * </ul>
 *
 * <h4>불변식 3 준수</h4>
 *
 * <p>모든 비동기 실행 지점에서 ThreadLocal 상태가 전파되어야 함
 *
 * <h4>MDCFilter 연계</h4>
 *
 * <p>HTTP 요청 진입 시 {@link maple.expectation.infrastructure.filter.MDCFilter}가 설정한 requestId가 이
 * TaskDecorator를 통해 비동기 워커 스레드로 전파됩니다.
 *
 * <h4>전파 원리 (snapshot/restore 패턴)</h4>
 *
 * <ol>
 *   <li>호출 스레드에서 contextMap = MDC.getCopyOfContextMap(), snap = snapshot()
 *   <li>워커 스레드 진입 시 MDC.setContextMap(contextMap), restore(snap)
 *   <li>작업 완료 후 finally에서 MDC.clear(), restore(before)로 원복
 * </ol>
 */
public class TaskDecoratorFactory {

  /**
   * MDC + SkipEquipmentL2CacheContext 전파용 TaskDecorator 생성
   *
   * @return TaskDecorator 인스턴스
   * @see maple.expectation.infrastructure.filter.MDCFilter
   */
  public TaskDecorator createContextPropagatingDecorator() {
    return runnable -> {
      // 1. 호출 스레드에서 현재 상태 캡처
      var mdcContextMap = org.slf4j.MDC.getCopyOfContextMap();
      String cacheContextSnap = SkipEquipmentL2CacheContext.snapshot(); // V5: MDC 기반

      return () -> {
        // 2. 워커 스레드에서 기존 상태 백업
        var mdcBefore = org.slf4j.MDC.getCopyOfContextMap();
        String cacheContextBefore = SkipEquipmentL2CacheContext.snapshot(); // V5: MDC 기반

        // 3. 캡처된 상태로 설정
        if (mdcContextMap != null) {
          org.slf4j.MDC.setContextMap(mdcContextMap);
        } else {
          org.slf4j.MDC.clear();
        }
        SkipEquipmentL2CacheContext.restore(cacheContextSnap);

        try {
          runnable.run();
        } finally {
          // 4. 작업 완료 후 원래 상태로 복원 (스레드풀 누수 방지)
          if (mdcBefore != null) {
            org.slf4j.MDC.setContextMap(mdcBefore);
          } else {
            org.slf4j.MDC.clear();
          }
          SkipEquipmentL2CacheContext.restore(cacheContextBefore);
        }
      };
    };
  }
}
