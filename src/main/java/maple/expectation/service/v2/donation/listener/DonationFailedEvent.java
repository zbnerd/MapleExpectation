package maple.expectation.service.v2.donation.listener;

public record DonationFailedEvent(String requestId, String guestUuid, Throwable exception) {}
