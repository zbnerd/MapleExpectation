package maple.expectation.mornitering;

import lombok.RequiredArgsConstructor;
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class MonitoringAlertService {
    private final LikeBufferStorage likeBufferStorage;
    private final DiscordAlertService discordAlertService;

    @Scheduled(fixedRate = 5000) // 5초마다 체크
    public void checkBufferSaturation() {
        long currentPending = likeBufferStorage.getCache().asMap().values().stream()
                .mapToLong(AtomicLong::get).sum();

        if (currentPending > 2000) { // 임계치 설정
            discordAlertService.sendCriticalAlert(
                "BUFFER SATURATION WARNING",
                "현재 좋아요 버퍼 잔량이 너무 높습니다: " + currentPending,
                new RuntimeException("System Load High")
            );
        }
    }
}