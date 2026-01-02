package maple.expectation.service.v2.shutdown;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Equipment 비동기 저장 작업 추적기
 * <p>
 * {@code EquipmentCacheService}에서 {@link CompletableFuture}로 실행되는
 * Equipment DB 저장 작업을 추적하여, Graceful Shutdown 시 모든 작업이
 * 완료될 때까지 대기할 수 있도록 지원합니다.
 * <p>
 * 작업 완료 시 자동으로 맵에서 제거되며, timeout 발생 시 미완료 작업 목록을 반환합니다.
 * <p>
 * Graceful Shutdown이 시작되면 {@code shutdownInProgress} 플래그가 활성화되어
 * 새로운 비동기 작업 등록을 거부합니다.
 */
@Slf4j
@Component
public class EquipmentPersistenceTracker {

    /**
     * 진행 중인 비동기 저장 작업 맵
     * <p>
     * Key: OCID, Value: CompletableFuture<Void>
     */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> pendingOperations = new ConcurrentHashMap<>();

    /**
     * Shutdown 진행 중 플래그
     * <p>
     * Shutdown이 시작되면 true로 설정되어 새로운 작업 등록을 거부합니다.
     */
    private volatile boolean shutdownInProgress = false;

    /**
     * 비동기 저장 작업을 등록하고 추적합니다.
     * <p>
     * 작업 완료 시 ({@code whenComplete}) 자동으로 맵에서 제거됩니다.
     * 예외 발생 시 로그를 남기며, 미완료 상태로 기록됩니다.
     * <p>
     * Graceful Shutdown이 시작된 경우 새로운 작업 등록을 거부합니다.
     *
     * @param ocid   캐릭터 OCID
     * @param future 비동기 저장 작업 Future
     * @throws IllegalStateException Shutdown이 진행 중인 경우
     */
    public void trackOperation(String ocid, CompletableFuture<Void> future) {
        if (shutdownInProgress) {
            log.warn("⚠️ [Equipment Persistence] Shutdown 진행 중 - 새로운 작업 등록 거부: {}", ocid);
            throw new IllegalStateException("Shutdown이 진행 중이므로 새로운 비동기 작업을 등록할 수 없습니다.");
        }

        pendingOperations.put(ocid, future);

        // 완료 시 자동 제거 및 로깅
        future.whenComplete((result, throwable) -> {
            pendingOperations.remove(ocid);

            if (throwable != null) {
                log.error("❌ [Equipment Persistence] 비동기 저장 실패: {}", ocid, throwable);
            } else {
                log.debug("✅ [Equipment Persistence] 비동기 저장 완료: {}", ocid);
            }
        });
    }

    /**
     * 모든 진행 중인 작업이 완료될 때까지 대기합니다.
     * <p>
     * Graceful Shutdown 시 호출되며, {@code timeout} 내에 모든 작업이
     * 완료되지 않으면 {@code false}를 반환합니다.
     * <p>
     * {@link CompletableFuture#allOf(CompletableFuture[])}를 사용하여
     * 모든 작업을 병렬로 대기합니다.
     * <p>
     * 이 메서드 호출 시 {@code shutdownInProgress} 플래그가 활성화되어
     * 새로운 작업 등록이 차단됩니다.
     *
     * @param timeout 대기 시간
     * @return 모든 작업이 완료되면 {@code true}, timeout 발생 시 {@code false}
     */
    public boolean awaitAllCompletion(Duration timeout) {
        // Shutdown 플래그 활성화 - 새로운 작업 등록 차단
        shutdownInProgress = true;
        log.info("🚫 [Equipment Persistence] Shutdown 시작 - 새로운 작업 등록 차단");

        if (pendingOperations.isEmpty()) {
            log.info("✅ [Equipment Persistence] 대기 중인 작업 없음");
            return true;
        }

        int initialCount = pendingOperations.size();
        log.info("⏳ [Equipment Persistence] {}건의 작업 완료 대기 중... (timeout: {}초)",
                initialCount, timeout.getSeconds());

        long startTime = System.currentTimeMillis();

        try {
            // 모든 Future를 배열로 수집
            CompletableFuture<?>[] futures = pendingOperations.values().toArray(new CompletableFuture[0]);

            // allOf로 모든 작업 완료 대기
            CompletableFuture<Void> allOf = CompletableFuture.allOf(futures);

            // timeout 내에 완료되기를 기다림
            allOf.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            long elapsedMillis = System.currentTimeMillis() - startTime;
            log.info("✅ [Equipment Persistence] 모든 작업 완료 ({}건, {}ms 소요)",
                    initialCount, elapsedMillis);
            return true;

        } catch (java.util.concurrent.TimeoutException e) {
            int remainingCount = pendingOperations.size();
            log.warn("⏱️ [Equipment Persistence] Timeout 발생. 미완료 작업: {}건", remainingCount);
            return false;

        } catch (Exception e) {
            log.error("❌ [Equipment Persistence] 작업 대기 중 예외 발생", e);
            return false;
        }
    }

    /**
     * 여전히 진행 중인 OCID 목록을 반환합니다.
     * <p>
     * Timeout 발생 시 어떤 작업이 완료되지 않았는지 확인하기 위해 사용됩니다.
     * Graceful Shutdown에서 파일 백업을 위해 호출됩니다.
     *
     * @return 진행 중인 OCID 리스트
     */
    public List<String> getPendingOcids() {
        return new ArrayList<>(pendingOperations.keySet());
    }

    /**
     * 현재 진행 중인 작업 개수를 반환합니다.
     * <p>
     * 모니터링 및 메트릭 수집에 사용됩니다.
     *
     * @return 진행 중인 작업 개수
     */
    public int getPendingCount() {
        return pendingOperations.size();
    }

    /**
     * 테스트 전용: Shutdown 플래그 및 pending 작업 초기화
     * <p>
     * ⚠️ <b>WARNING:</b> 이 메서드는 통합 테스트에서 각 테스트 간
     * 격리를 보장하기 위한 목적으로만 사용되어야 하며,
     * 프로덕션 코드에서 호출해서는 안 됩니다.
     * <p>
     * Shutdown 플래그를 false로 리셋하고 모든 pending 작업을 제거합니다.
     */
    public void resetForTesting() {
        shutdownInProgress = false;
        pendingOperations.clear();
        log.debug("🔄 [Equipment Persistence] 테스트용 리셋 완료");
    }
}
