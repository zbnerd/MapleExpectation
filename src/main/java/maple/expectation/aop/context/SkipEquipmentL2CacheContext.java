package maple.expectation.aop.context;

/**
 * Expectation 경로에서 EquipmentResponse L2(Redis) 저장을 스킵하기 위한 ThreadLocal 컨텍스트
 *
 * <p>P0-4 불변식 준수:
 * <ul>
 *   <li>withInitial() 금지 → new ThreadLocal&lt;&gt;() 사용</li>
 *   <li>prev==null이면 remove()로 복원 (스레드풀 누수 방지)</li>
 *   <li>snapshot()/restore() API로 async 전파 (B2)</li>
 * </ul>
 *
 * <p>사용 패턴:
 * <pre>{@code
 * try (var ignored = SkipEquipmentL2CacheContext.withSkip()) {
 *     // 이 블록 내에서는 NexonDataCacheAspect가 L2 saveCache를 스킵함
 *     calculateTotalExpectation(...);
 * }
 * }</pre>
 *
 * @see <a href="https://github.com/issue/158">Issue #158: Expectation API 캐시 타겟 전환</a>
 */
public final class SkipEquipmentL2CacheContext {

    /**
     * P0-4: withInitial() 금지 → new ThreadLocal<>() 사용
     * 스레드풀에서 값이 잔존하지 않도록 명시적 관리 필요
     */
    private static final ThreadLocal<Boolean> FLAG = new ThreadLocal<>();

    /**
     * 현재 컨텍스트에서 L2 저장 스킵이 활성화되어 있는지 확인
     *
     * @return true면 EquipmentResponse L2 저장을 스킵해야 함
     */
    public static boolean enabled() {
        return Boolean.TRUE.equals(FLAG.get());
    }

    /**
     * L2 저장 스킵 모드를 활성화하고, try-with-resources로 자동 복원
     *
     * <p>P0-4 "진짜 restore" 패턴:
     * <ul>
     *   <li>이전 값(prev)을 캡처</li>
     *   <li>블록 종료 시 prev==null이면 remove()로 완전 정리</li>
     *   <li>prev!=null이면 이전 값으로 복원 (중첩 호출 지원)</li>
     * </ul>
     *
     * @return AutoCloseable - try-with-resources에서 사용
     */
    public static AutoCloseable withSkip() {
        final Boolean prev = FLAG.get(); // null 가능 (중요: 캡처 시점)
        FLAG.set(Boolean.TRUE);
        return () -> {
            if (prev == null) {
                FLAG.remove(); // P0-4: prev==null이면 remove()로 완전 정리
            } else {
                FLAG.set(prev);
            }
        };
    }

    // ==================== B2: async 전파용 캡슐화 API ====================

    /**
     * 현재 ThreadLocal 상태를 스냅샷 (async 전파용)
     *
     * <p>B2 패턴: 내부 필드 직접 접근 금지 → snapshot/restore API 사용
     *
     * <pre>{@code
     * // 호출 스레드에서
     * Boolean snap = SkipEquipmentL2CacheContext.snapshot();
     *
     * // 워커 스레드에서
     * Boolean before = SkipEquipmentL2CacheContext.snapshot();
     * SkipEquipmentL2CacheContext.restore(snap);
     * try {
     *     // 작업 수행
     * } finally {
     *     SkipEquipmentL2CacheContext.restore(before);
     * }
     * }</pre>
     *
     * @return 현재 FLAG 값 (null 가능)
     */
    public static Boolean snapshot() {
        return FLAG.get();
    }

    /**
     * ThreadLocal 상태를 복원 (async 전파용)
     *
     * <p>P0-4: prev==null이면 remove()로 완전 정리
     *
     * @param prev snapshot()으로 캡처한 이전 값 (null 가능)
     */
    public static void restore(Boolean prev) {
        if (prev == null) {
            FLAG.remove();
        } else {
            FLAG.set(prev);
        }
    }

    // Private constructor to prevent instantiation
    private SkipEquipmentL2CacheContext() {
        throw new UnsupportedOperationException("Utility class");
    }
}
