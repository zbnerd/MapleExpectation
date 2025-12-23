package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class LikeSyncService {

    private final LikeBufferStorage likeBufferStorage;
    private final GameCharacterRepository gameCharacterRepository;
    private final DiscordAlertService discordAlertService;

    private static final int MAX_RETRIES = 3; // 최대 재시도 횟수
    private static final long INITIAL_BACKOFF_MS = 1000; // 초기 대기 시간 (1초)

    @Transactional
    @ObservedTransaction("scheduler.like.sync")
    public void syncLikesToDatabase() {
        Map<String, AtomicLong> bufferMap = likeBufferStorage.getCache().asMap();
        if (bufferMap.isEmpty()) return;

        log.debug("[Sync] 데이터 동기화 시작 (대상 유저 수: {})", bufferMap.size());
        bufferMap.forEach(this::syncEachUserLikeWithRetry); // 재시도 로직 포함 메서드로 변경
    }

    private void syncEachUserLikeWithRetry(String userIgn, AtomicLong atomicCount) {
        long countToAdd = atomicCount.getAndSet(0);
        if (countToAdd <= 0) return;

        boolean success = false;
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                gameCharacterRepository.incrementLikeCount(userIgn, countToAdd);
                success = true;
                if (attempt > 1) log.info("✅ [Retry Success] {} 유저 데이터가 {}회차 재시도 끝에 반영되었습니다.", userIgn, attempt);
                break;
            } catch (Exception e) {
                lastException = e;
                log.warn("❌ [Sync Failed] {} 반영 실패 ({}회차). 사유: {}", userIgn, attempt, e.getMessage());

                if (attempt < MAX_RETRIES) {
                    applyBackoff(attempt); // 지수 백오프 적용
                }
            }
        }

        if (!success) {
            handleFinalFailure(userIgn, atomicCount, countToAdd, lastException);
        }
    }

    private void applyBackoff(int attempt) {
        try {
            // 지수 백오프: 1초 -> 2초 -> 4초...
            long waitTime = INITIAL_BACKOFF_MS * (long) Math.pow(2, attempt - 1);
            Thread.sleep(waitTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleFinalFailure(String userIgn, AtomicLong atomicCount, long countToAdd, Exception e) {
        log.error("🚨 [Critical] {} 동기화 최종 실패. 데이터를 버퍼로 롤백하고 알림을 발송합니다.", userIgn);

        // 1. 버퍼 데이터 복구 (데이터 유실 방지)
        atomicCount.addAndGet(countToAdd);

        // 2. 관리자에게 디스코드 알림 발송 (관측 가능성 확보)
        discordAlertService.sendCriticalAlert(
                "좋아요 동기화 장애 발생",
                String.format("대상 유저: %s\n실패 횟수: %d회\n유실 위기 데이터: %d개", userIgn, MAX_RETRIES, countToAdd),
                e
        );
    }
}