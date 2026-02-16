package maple.expectation.infrastructure.alert.channel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import lombok.RequiredArgsConstructor;
import maple.expectation.infrastructure.alert.message.AlertMessage;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Component;

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
 * <h3>CLAUDE.md Section 12 Compliance</h3>
 *
 * <ul>
 *   <li>Uses LogicExecutor.executeOrDefault() for exception handling
 *   <li>No raw try-catch blocks in business logic
 * </ul>
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@Component
@RequiredArgsConstructor
public class LocalFileAlertChannel implements AlertChannel, FallbackSupport {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(LocalFileAlertChannel.class);

  private final Path logFilePath;
  private final LogicExecutor executor;
  private AlertChannel fallback;

  @Override
  public boolean send(AlertMessage message) {
    return executor.executeOrDefault(
        () -> writeToFile(message),
        false,
        TaskContext.of("LocalFileAlertChannel", "Send", message.getTitle()));
  }

  /**
   * Write alert to file with checked exceptions.
   *
   * <p>Wrapped by LogicExecutor.executeOrDefault() which translates IOException to
   * InternalSystemException and returns false on failure.
   */
  private boolean writeToFile(AlertMessage message) throws IOException {
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
