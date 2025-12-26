package maple.expectation.service.v2.like.event;

public record LikeSyncFailedEvent(String userIgn, long lostCount, int retryCount, Exception exception) {}