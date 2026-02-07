package maple.expectation.service.v2.donation.listener;

import lombok.RequiredArgsConstructor;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DonationEventListener {
  private final DiscordAlertService discordAlertService;

  @EventListener
  public void handleDonationFailed(DonationFailedEvent event) {
    discordAlertService.sendCriticalAlert(
        "DONATION TRANSACTION FAILED",
        "RequestId: " + event.requestId() + " | Guest: " + event.guestUuid(),
        event.exception());
  }
}
