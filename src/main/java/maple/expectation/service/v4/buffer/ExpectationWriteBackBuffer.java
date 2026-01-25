package maple.expectation.service.v4.buffer;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Expectation Write-Behind 메모리 버퍼 (#266)
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Blue (Architect): ConcurrentLinkedQueue로 Lock-free 구현</li>
 *   <li>Red (SRE): 백프레셔 구현 - MAX_QUEUE_SIZE 초과 시 동기 폴백</li>
 *   <li>Green (Performance): 메트릭 노출로 모니터링 가능</li>
 * </ul>
 *
 * <h3>성능 특성</h3>
 * <ul>
 *   <li>offer: O(1) Lock-free</li>
 *   <li>drain: O(n) Lock-free</li>
 *   <li>메모리: ~10MB max (10,000 × ~1KB)</li>
 * </ul>
 *
 * <h3>P0/P1 허용 결정</h3>
 * <p>버퍼 데이터 유실은 P2로 허용됨:
 * 캐시에 이미 저장되어 있고, DB는 분석용 데이터이므로</p>
 */
@Slf4j
@Component
public class ExpectationWriteBackBuffer {

    private final ConcurrentLinkedQueue<ExpectationWriteTask> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger(0);
    private final MeterRegistry meterRegistry;

    /**
     * 최대 큐 크기 (백프레셔 임계값)
     *
     * <p>10,000 tasks × ~1KB = ~10MB max memory</p>
     */
    private static final int MAX_QUEUE_SIZE = 10_000;

    public ExpectationWriteBackBuffer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerMetrics();
    }

    /**
     * 메트릭 등록
     *
     * <h4>Prometheus Alert 권장 임계값 (Red Agent)</h4>
     * <ul>
     *   <li>expectation.buffer.pending > 8000: WARNING (80% capacity)</li>
     *   <li>expectation.buffer.pending == 10000: CRITICAL (backpressure)</li>
     * </ul>
     */
    private void registerMetrics() {
        Gauge.builder("expectation.buffer.pending", pendingCount, AtomicInteger::get)
                .description("Expectation 버퍼 대기 작업 수")
                .register(meterRegistry);
    }

    /**
     * 프리셋 결과를 버퍼에 추가
     *
     * <h4>백프레셔 동작</h4>
     * <p>큐가 MAX_QUEUE_SIZE에 도달하면 false 반환 → 호출자가 동기 폴백 수행</p>
     *
     * @param characterId 캐릭터 ID
     * @param presets 프리셋 결과 목록
     * @return true: 버퍼링 성공, false: 백프레셔 발동 (동기 폴백 필요)
     */
    public boolean offer(Long characterId, List<PresetExpectation> presets) {
        // 백프레셔 체크
        if (pendingCount.get() >= MAX_QUEUE_SIZE) {
            meterRegistry.counter("expectation.buffer.rejected").increment();
            log.warn("[ExpectationBuffer] Backpressure triggered: pending={}, max={}",
                    pendingCount.get(), MAX_QUEUE_SIZE);
            return false;
        }

        // 각 프리셋을 버퍼에 추가
        for (PresetExpectation preset : presets) {
            ExpectationWriteTask task = ExpectationWriteTask.from(characterId, preset);
            queue.offer(task);
            pendingCount.incrementAndGet();
        }

        log.debug("[ExpectationBuffer] Buffered {} presets for character {}, pending={}",
                presets.size(), characterId, pendingCount.get());
        return true;
    }

    /**
     * 버퍼에서 배치 크기만큼 작업 추출
     *
     * <h4>Lock-free Drain</h4>
     * <p>ConcurrentLinkedQueue.poll()은 Lock-free이므로
     * 스케줄러와 Shutdown Handler가 동시에 호출해도 안전함</p>
     *
     * @param maxBatchSize 최대 배치 크기
     * @return 추출된 작업 목록 (빈 리스트 가능)
     */
    public List<ExpectationWriteTask> drain(int maxBatchSize) {
        List<ExpectationWriteTask> batch = new ArrayList<>(maxBatchSize);
        ExpectationWriteTask task;

        while (batch.size() < maxBatchSize && (task = queue.poll()) != null) {
            batch.add(task);
            pendingCount.decrementAndGet();
        }

        return batch;
    }

    /**
     * 대기 중인 작업 수 조회
     */
    public int getPendingCount() {
        return pendingCount.get();
    }

    /**
     * 버퍼가 비어있는지 확인
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
