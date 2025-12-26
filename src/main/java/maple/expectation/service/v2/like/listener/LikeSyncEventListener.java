package maple.expectation.service.v2.like.listener;

import lombok.RequiredArgsConstructor;
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.service.v2.like.event.LikeSyncFailedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LikeSyncEventListener {
    private final DiscordAlertService discordAlertService;

    @EventListener
    public void handleSyncFailure(LikeSyncFailedEvent event) {
        discordAlertService.sendCriticalAlert(
            "좋아요 동기화 장애",
            String.format("유저: %s | 유실 위기: %d개", event.userIgn(), event.lostCount()),
            event.exception()
        );
    }
}