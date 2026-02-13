package maple.expectation.alert.channel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import maple.expectation.alert.message.AlertMessage;

/**
 * Local File Alert Channel (Tertiary Fallback)
 *
 * <p>Last resort fallback channel that writes alerts to file
 *
 * <p>When Discord and in-memory buffer both fail, writes to local file
 *
 * <p>Zero external dependencies - only OS file system
 *
 * <h4>Architecture Decision:</h4>
 *
 * <ul>
 *   <li>Uses Files.write() for atomic append operations
 *   <li>No database, no Redis, no network - pure OS resources
 *   <li>Implements FallbackSupport for chaining
 * </ul>
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
public class LocalFileAlertChannel implements AlertChannel, FallbackSupport {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(LocalFileAlertChannel.class);

  private final Path logFilePath;
  private AlertChannel fallback;

  public LocalFileAlertChannel(Path logFilePath) {
    this.logFilePath = logFilePath;
  }

  @Override
  public boolean send(AlertMessage message) {
    try {
      // Create parent directories if not exist
      if (Files.notExists(logFilePath.getParent())) {
        Files.createDirectories(logFilePath.getParent());
      }

      // Append to log file (atomic operation)
      String logEntry =
          String.format(
              "[%s] %s\n```\n%s", message.getTitle(), message.getFormattedMessage(), "LocalFile");

      Files.writeString(
          logFilePath,
          logEntry,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.APPEND);

      if (log.isInfoEnabled()) {
        log.info("[LocalFileAlertChannel] Alert written to file: {}", logFilePath);
      }
      return true;

    } catch (IOException e) {
      log.error(
          "[LocalFileAlertChannel] Failed to write alert to file: {}", logFilePath, e.getMessage());
      return false;
    }
  }

  @Override
  public String getChannelName() {
    return "local-file";
  }

  @Override
  public void setFallback(AlertChannel fallback) {
    this.fallback = fallback;
    log.info(
        "[LocalFileAlertChannel] Fallback channel set to {}",
        fallback != null ? fallback.getChannelName() : "none");
  }
}
