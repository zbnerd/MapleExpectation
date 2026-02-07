package maple.expectation.global.util;

import java.io.InterruptedIOException;

/**
 * 인터럽트 복원 유틸리티
 *
 * <p>예외 그래프(cause chain + suppressed)에서 InterruptedException 또는 InterruptedIOException이 발견되면 현재
 * 스레드의 interrupt 플래그를 복원합니다.
 *
 * <h3>통합 이유 (P1-2)</h3>
 *
 * <ul>
 *   <li>{@code ExecutionPipeline}: cause chain만 스캔
 *   <li>{@code DefaultCheckedLogicExecutor}: cause + suppressed 스캔
 *   <li>두 구현을 통합하여 일관된 동작 보장
 * </ul>
 *
 * @since 2.5.0
 */
public final class InterruptUtils {

  /** Throwable 그래프 순회 최대 깊이 (무한 루프 방지) */
  private static final int MAX_GRAPH_DEPTH = 32;

  private InterruptUtils() {}

  /**
   * Throwable 그래프에 InterruptedException 또는 InterruptedIOException이 포함되어 있으면 현재 스레드의 interrupt 플래그를
   * 복원합니다.
   *
   * @param t 검사할 Throwable (null 허용)
   */
  public static void restoreInterruptIfNeeded(Throwable t) {
    if (t != null && containsInterrupted(t, 0)) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Throwable 그래프에서 InterruptedException 존재 여부 확인
   *
   * <p>cause chain과 suppressed 배열을 모두 순회합니다.
   *
   * @param t 검사할 Throwable
   * @param depth 현재 깊이 (무한 루프 방지)
   * @return InterruptedException 또는 InterruptedIOException이 존재하면 true
   */
  private static boolean containsInterrupted(Throwable t, int depth) {
    if (t == null || depth >= MAX_GRAPH_DEPTH) return false;
    if (t instanceof InterruptedException || t instanceof InterruptedIOException) return true;

    // suppressed 배열 스캔
    for (Throwable s : t.getSuppressed()) {
      if (containsInterrupted(s, depth + 1)) return true;
    }

    // cause chain 스캔
    return containsInterrupted(t.getCause(), depth + 1);
  }
}
