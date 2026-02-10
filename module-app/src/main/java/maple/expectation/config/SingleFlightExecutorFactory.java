package maple.expectation.config;

import java.util.concurrent.Executor;
import java.util.function.Function;
import maple.expectation.global.concurrency.SingleFlightExecutor;
import org.springframework.stereotype.Component;

/**
 * Factory for creating SingleFlightExecutor instances.
 *
 * <p>Supports DIP (Dependency Inversion Principle) by allowing injection instead of direct
 * instantiation with {@code new}.
 *
 * @param <T> the response type
 * @see maple.expectation.service.v2.EquipmentService
 */
@Component
public class SingleFlightExecutorFactory {

  /**
   * Create a new SingleFlightExecutor with the specified parameters.
   *
   * @param timeoutSeconds timeout in seconds
   * @param executor executor for async execution
   * @param fallback fallback function on timeout
   * @param <T> response type
   * @return configured SingleFlightExecutor
   */
  public <T> SingleFlightExecutor<T> create(
      long timeoutSeconds, Executor executor, Function<String, T> fallback) {
    return new SingleFlightExecutor<>((int) timeoutSeconds, executor, fallback);
  }
}
