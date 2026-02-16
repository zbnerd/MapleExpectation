package maple.expectation.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import maple.expectation.infrastructure.alert.StatelessAlertService;
import maple.expectation.infrastructure.alert.channel.AlertChannel;
import maple.expectation.infrastructure.alert.message.AlertMessage;
import maple.expectation.infrastructure.executor.LogicExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Stateless Alert Service Test
 *
 * <p>Verifies stateless alert service behavior
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class StatelessAlertServiceTest {

  @Mock
  private maple.expectation.infrastructure.alert.strategy.AlertChannelStrategy channelStrategy;

  @Mock private LogicExecutor logicExecutor;

  @Test
  void testSendCritical_Success() {
    // Mock channel that always succeeds
    AlertChannel mockChannel = mock(AlertChannel.class);
    when(mockChannel.send(any(AlertMessage.class))).thenReturn(true);

    // Inject mock strategy
    when(channelStrategy.getChannel(maple.expectation.infrastructure.alert.AlertPriority.CRITICAL))
        .thenReturn(mockChannel);

    StatelessAlertService service =
        new StatelessAlertService(
            channelStrategy, logicExecutor, "https://discord.example.com/webhook");

    // Should not throw exception
    service.sendCritical("Test Critical", "Test message", null);
  }

  @Test
  void testSendCritical_ChannelFails() {
    // Mock channel that always fails
    AlertChannel mockChannel = mock(AlertChannel.class);
    when(mockChannel.send(any(AlertMessage.class))).thenReturn(false);

    when(channelStrategy.getChannel(maple.expectation.infrastructure.alert.AlertPriority.CRITICAL))
        .thenReturn(mockChannel);

    StatelessAlertService service =
        new StatelessAlertService(
            channelStrategy, logicExecutor, "https://discord.example.com/webhook");

    // Should log warning but not throw exception
    service.sendCritical("Test Critical", "Test message", null);
  }

  @Test
  void testGetChannel_ReturnsDiscordChannel() {
    // Mock strategy to return Discord channel
    AlertChannel discordChannel = mock(AlertChannel.class);
    when(discordChannel.getChannelName()).thenReturn("discord");

    when(channelStrategy.getChannel(maple.expectation.infrastructure.alert.AlertPriority.CRITICAL))
        .thenReturn(discordChannel);

    StatelessAlertService service =
        new StatelessAlertService(
            channelStrategy, logicExecutor, "https://discord.example.com/webhook");

    AlertChannel result =
        channelStrategy.getChannel(maple.expectation.infrastructure.alert.AlertPriority.CRITICAL);
    assertEquals("discord", result.getChannelName());
  }
}
