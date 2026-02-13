package maple.expectation.alert.channel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import maple.expectation.alert.message.AlertMessage;
import maple.expectation.infrastructure.executor.DefaultLogicExecutor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.policy.ExecutionPipeline;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import org.junit.jupiter.api.Test;

/**
 * Local File Alert Channel Test
 *
 * <p>Verifies file-based alert channel behavior
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
class LocalFileAlertChannelTest {

  private final LogicExecutor executor =
      new DefaultLogicExecutor(
          new ExecutionPipeline(java.util.List.of()), ExceptionTranslator.defaultTranslator());

  @Test
  void testSend_Success() throws IOException {
    Path tempFile = Files.createTempFile("test-alerts", ".log");
    try {
      // Create channel
      LocalFileAlertChannel channel = new LocalFileAlertChannel(tempFile, executor);

      AlertMessage message =
          new AlertMessage("Test Alert", "Success message", null, "http://test.webhook");

      boolean sent = channel.send(message);
      assertTrue(sent, "Alert should be written to file successfully");

      // Verify file content
      String content = Files.readString(tempFile);
      assertTrue(content.contains("Test Alert"), "Log should contain alert title");
      assertTrue(content.contains("Success message"), "Log should contain alert message");

    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  void testSend_Failure() {
    Path tempFile = Path.of("/non/existent/directory/test-alerts.log");

    // Create channel with invalid path
    LocalFileAlertChannel channel = new LocalFileAlertChannel(tempFile, executor);

    AlertMessage message =
        new AlertMessage("Test Alert", "Should fail", null, "http://test.webhook");

    boolean sent = channel.send(message);
    assertFalse(sent, "Alert send should fail for invalid path");
  }
}
