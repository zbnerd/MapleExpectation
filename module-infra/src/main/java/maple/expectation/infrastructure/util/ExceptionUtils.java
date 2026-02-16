package maple.expectation.infrastructure.util;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Exception unwrapping utilities.
 *
 * <p>Extracts root causes from wrapped exceptions like CompletionException and ExecutionException.
 */
public class ExceptionUtils {

  /**
   * Unwrap async exception wrappers to find the root cause.
   *
   * <p>Unwraps: CompletionException, ExecutionException
   *
   * @param throwable The exception to unwrap
   * @return The root cause, or the original if not wrapped
   */
  public static Throwable unwrapAsyncException(Throwable throwable) {
    Throwable cause = throwable;
    while (cause instanceof CompletionException || cause instanceof ExecutionException) {
      cause = cause.getCause();
      if (cause == null) {
        return throwable;
      }
    }
    return cause;
  }

  private ExceptionUtils() {
    // Utility class
  }
}
