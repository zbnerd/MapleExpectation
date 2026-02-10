package maple.expectation.aop.context;

import org.slf4j.MDC;

/**
 * Expectation 경로에서 EquipmentResponse L2(Redis) 저장을 스킵하기 위한 MDC 기반 컨텍스트 (#271 V5 Stateless
 * Architecture)
 *
 * <h3>V5 Stateless 전환</h3>
 *
 * <p>ThreadLocal에서 MDC(Mapped Diagnostic Context)로 마이그레이션:
 *
 * <ul>
 *   <li>MDC는 SLF4J의 스레드-로컬 컨텍스트로 로그 추적성 향상
 *   <li>기존 API 100% 호환: enabled(), withSkip(), snapshot(), restore()
 *   <li>prev==null이면 MDC.remove()로 완전 정리 (스레드풀 누수 방지)
 * </ul>
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): MDC 전환으로 로그 추적성 확보
 *   <li>Red (SRE): remove() 보장으로 스레드풀 누수 방지
 *   <li>Purple (Auditor): 기존 API 100% 호환으로 마이그레이션 안전성 확보
 * </ul>
 *
 * <p>사용 패턴:
 *
 * <pre>{@code
 * try (var ignored = SkipEquipmentL2CacheContext.withSkip()) {
 *     // 이 블록 내에서는 NexonDataCacheAspect가 L2 saveCache를 스킵함
 *     calculateTotalExpectation(...);
 * }
 * }</pre>
 *
 * @see <a href="https://github.com/issue/158">Issue #158: Expectation API 캐시 타겟 전환</a>
 * @see <a href="https://github.com/issue/271">Issue #271: V5 Stateless Architecture</a>
 */
public final class SkipEquipmentL2CacheContext {

  /** MDC 키 (로그에서 확인 가능) */
  private static final String MDC_KEY = "skipL2Cache";

  /** MDC 활성화 값 */
  private static final String MDC_VALUE_TRUE = "true";

  /**
   * 현재 컨텍스트에서 L2 저장 스킵이 활성화되어 있는지 확인
   *
   * @return true면 EquipmentResponse L2 저장을 스킵해야 함
   */
  public static boolean enabled() {
    return MDC_VALUE_TRUE.equals(MDC.get(MDC_KEY));
  }

  /**
   * L2 저장 스킵 모드를 활성화하고, try-with-resources로 자동 복원
   *
   * <p>P0-4 "진짜 restore" 패턴 (MDC 버전):
   *
   * <ul>
   *   <li>이전 값(prev)을 캡처
   *   <li>블록 종료 시 prev==null이면 MDC.remove()로 완전 정리
   *   <li>prev!=null이면 이전 값으로 복원 (중첩 호출 지원)
   * </ul>
   *
   * @return AutoCloseable - try-with-resources에서 사용
   */
  public static AutoCloseable withSkip() {
    final String prev = MDC.get(MDC_KEY); // null 가능 (중요: 캡처 시점)
    MDC.put(MDC_KEY, MDC_VALUE_TRUE);
    return () -> {
      if (prev == null) {
        MDC.remove(MDC_KEY); // V5: prev==null이면 remove()로 완전 정리
      } else {
        MDC.put(MDC_KEY, prev);
      }
    };
  }

  // ==================== B2: async 전파용 캡슐화 API ====================

  /**
   * 현재 MDC 상태를 스냅샷 (async 전파용)
   *
   * <p>B2 패턴: 내부 필드 직접 접근 금지 → snapshot/restore API 사용
   *
   * <pre>{@code
   * // 호출 스레드에서
   * String snap = SkipEquipmentL2CacheContext.snapshot();
   *
   * // 워커 스레드에서
   * String before = SkipEquipmentL2CacheContext.snapshot();
   * SkipEquipmentL2CacheContext.restore(snap);
   * try {
   *     // 작업 수행
   * } finally {
   *     SkipEquipmentL2CacheContext.restore(before);
   * }
   * }</pre>
   *
   * @return 현재 MDC 값 (null 가능)
   */
  public static String snapshot() {
    return MDC.get(MDC_KEY);
  }

  /**
   * MDC 상태를 복원 (async 전파용)
   *
   * <p>V5: prev==null이면 MDC.remove()로 완전 정리
   *
   * @param prev snapshot()으로 캡처한 이전 값 (null 가능)
   */
  public static void restore(String prev) {
    if (prev == null) {
      MDC.remove(MDC_KEY);
    } else {
      MDC.put(MDC_KEY, prev);
    }
  }

  /**
   * [Deprecated] Boolean 타입 복원 (하위 호환성)
   *
   * <p>V5 마이그레이션 중 Boolean 타입을 사용하는 기존 코드 지원. 신규 코드는 String 기반 snapshot()/restore(String)을 사용하십시오.
   *
   * @param prev 이전 Boolean 값 (null 가능)
   * @deprecated Use {@link #restore(String)} instead
   */
  @Deprecated(since = "V5", forRemoval = true)
  public static void restore(Boolean prev) {
    if (prev == null || !prev) {
      MDC.remove(MDC_KEY);
    } else {
      MDC.put(MDC_KEY, MDC_VALUE_TRUE);
    }
  }

  // Private constructor to prevent instantiation
  private SkipEquipmentL2CacheContext() {
    throw new UnsupportedOperationException("Utility class");
  }
}
