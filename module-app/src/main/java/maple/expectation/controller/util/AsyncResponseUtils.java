package maple.expectation.controller.util;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;

/**
 * Utility class for asynchronous response handling in controllers.
 *
 * <p>Provides common patterns for transforming CompletableFuture results into ResponseEntity
 * instances, reducing code duplication across controller endpoints.
 *
 * <h3>Usage Example:</h3>
 *
 * <pre>{@code
 * // Before (duplicated pattern)
 * return service.getDataAsync(id)
 *     .thenApply(ResponseEntity::ok);
 *
 * // After (using utility)
 * return AsyncResponseUtils.ok(service.getDataAsync(id));
 * }</pre>
 *
 * @see org.springframework.http.ResponseEntity
 * @see java.util.concurrent.CompletableFuture
 */
public final class AsyncResponseUtils {

  private AsyncResponseUtils() {
    // Utility class - prevent instantiation
  }

  /**
   * Wraps a CompletableFuture result in a ResponseEntity with HTTP 200 OK status.
   *
   * <p>This is the most common response pattern for successful async operations.
   *
   * @param future the CompletableFuture to transform
   * @param <T> the response body type
   * @return a CompletableFuture that completes with ResponseEntity.ok(body)
   */
  public static <T> CompletableFuture<ResponseEntity<T>> ok(CompletableFuture<T> future) {
    return future.thenApply(ResponseEntity::ok);
  }

  /**
   * Applies a transformation function to a CompletableFuture result and wraps in ResponseEntity.
   *
   * <p>Useful for post-processing responses before wrapping in ResponseEntity.
   *
   * @param future the CompletableFuture to transform
   * @param mapper the transformation function to apply
   * @param <T> the input type
   * @param <R> the output type
   * @return a CompletableFuture that completes with transformed ResponseEntity
   */
  public static <T, R> CompletableFuture<ResponseEntity<R>> map(
      CompletableFuture<T> future, Function<T, R> mapper) {
    return future.thenApply(mapper).thenApply(ResponseEntity::ok);
  }
}
