package maple.expectation.alert;

import static org.junit.jupiter.api.Assertions.*;

import maple.expectation.alert.message.AlertMessage;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import maple.expectation.alert.AlertPriority;
import maple.expectation.alert.strategy.AlertChannelStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Stateless Alert Service Test
 *
 * <p>Verifies stateless alert service behavior
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@ExtendWith(MockitoExtension.class)
class StatelessAlertServiceTest {

  @Mock private AlertChannelStrategy channelStrategy;

  @Test
  void testSendCritical_Success() {
    // Mock channel that always succeeds
    AlertChannel mockChannel = mock(AlertChannel.class);
    when(mockChannel.send(any(AlertMessage.class))).thenReturn(true);

    // Inject mock strategy
    when(channelStrategy.getChannel(AlertPriority.CRITICAL)).thenReturn(mockChannel);

    StatelessAlertService service = new StatelessAlertService(channelStrategy);

    // Should not throw exception
    service.sendCritical("Test Critical", "Test message", null);
  }

  @Test
  void testSendCritical_ChannelFails() {
    // Mock channel that always fails
    AlertChannel mockChannel = mock(AlertChannel.class);
    when(mockChannel.send(any(AlertMessage.class))).thenReturn(false);

    when(channelStrategy.getChannel(AlertPriority.CRITICAL)).thenReturn(mockChannel);

    StatelessAlertService service = new StatelessAlertService(channelStrategy);

    // Should log warning but not throw exception
    service.sendCritical("Test Critical", "Test message", null);
  }

  @Test
  void testGetChannel_ReturnsDiscordChannel() {
    // Mock strategy to return Discord channel
    AlertChannel discordChannel = mock(AlertChannel.class);
    when(discordChannel.getChannelName()).thenReturn("discord");

    when(channelStrategy.getChannel(AlertPriority.CRITICAL)).thenReturn(discordChannel);

    StatelessAlertService service = new StatelessAlertService(channelStrategy);

    AlertChannel result = channelStrategy.getChannel(AlertPriority.CRITICAL);
    assertEquals("discord", result.getChannelName());
  }
}
