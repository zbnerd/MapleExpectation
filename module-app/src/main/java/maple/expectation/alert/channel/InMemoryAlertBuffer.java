package maple.expectation.alert.channel;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.alert.message.AlertMessage;
import org.springframework.stereotype.Component;

/**
 * In-Memory Alert Buffer (Fallback Channel)
 *
 * <p>Stateless fallback channel that stores alerts in memory
 *
 * <p>Thread-safe circular buffer with max capacity (1000 alerts)
 *
 * <p>Zero external dependencies - pure JVM implementation
 *
 * <h4>Architecture Decision:</h4>
 *
 * <ul>
 *   <li>Uses ArrayBlockingQueue for thread-safe bounded buffer
 *   <li>When buffer is full, new alerts are dropped (with warning)
 *   <li>Implements FallbackSupport for chaining
 * </ul>
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@Component
@Slf4j
public class InMemoryAlertBuffer implements AlertChannel, FallbackSupport {

  private static final int MAX_CAPACITY = 1000;
  private final BlockingQueue<AlertMessage> buffer = new ArrayBlockingQueue<>(MAX_CAPACITY);
  private AlertChannel fallback;

  @Override
  public boolean send(AlertMessage message) {
    boolean offered = buffer.offer(message);
    if (!offered && log.isWarnEnabled()) {
      log.warn("[InMemoryAlertBuffer] Buffer full, dropping alert: {}", message.getTitle());
    }
    return offered;
  }

  @Override
  public String getChannelName() {
    return "in-memory";
  }

  public int getBufferSize() {
    return buffer.size();
  }

  public int drainTo(AlertChannel targetChannel) {
    int drained = 0;
    AlertMessage message;
    while ((message = buffer.poll()) != null) {
      boolean sent = targetChannel.send(message);
      if (sent) {
        drained++;
      } else if (log.isWarnEnabled()) {
        log.warn(
            "[InMemoryAlertBuffer] Failed to drain alert to {}: {}",
            targetChannel.getChannelName());
      }
    }
    return drained;
  }

  @Override
  public void setFallback(AlertChannel fallback) {
    this.fallback = fallback;
    log.info(
        "[InMemoryAlertBuffer] Fallback channel set to {}",
        fallback != null ? fallback.getChannelName() : "none");
  }
}
