package maple.expectation.service.v2.donation.listener;

import lombok.RequiredArgsConstructor;
import maple.expectation.infrastructure.alert.StatelessAlertService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DonationEventListener {
  private final StatelessAlertService statelessAlertService;

  @EventListener
  public void handleDonationFailed(DonationFailedEvent event) {
    statelessAlertService.sendCritical(
        "DONATION TRANSACTION FAILED",
        "RequestId: " + event.requestId() + " | Guest: " + event.guestUuid(),
        event.exception());
  }
}
