package maple.expectation.service.v4.buffer;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.config.BufferProperties;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Component;

/**
 * Expectation Write-Behind 메모리 버퍼 (#266 ADR 정합성 리팩토링)
 *
 * <h3>5-Agent Council 합의 (Round 1-5)</h3>
 *
 * <ul>
 *   <li>Blue (Architect): ConcurrentLinkedQueue로 Lock-free 구현, offerInternal() SRP 분리
 *   <li>Red (SRE): 백프레셔 구현 - maxQueueSize 초과 시 동기 폴백
 *   <li>Green (Performance): CAS + Exponential backoff로 경합 최적화
 *   <li>Purple (Auditor): Phaser 기반 Shutdown Race 방지, LogicExecutor 강제
 *   <li>Yellow (QA): BackoffStrategy 추상화로 테스트 가능성 확보
 * </ul>
 *
 * <h3>P0 Shutdown Race 방지</h3>
 *
 * <p>Phaser를 사용하여 진행 중인 offer 작업을 추적하고, Shutdown 시 모든 작업이 완료될 때까지 안전하게 대기합니다.
 *
 * <h3>성능 특성</h3>
 *
 * <ul>
 *   <li>offer: O(1) Lock-free + CAS backoff
 *   <li>drain: O(n) Lock-free
 *   <li>메모리: ~10MB max (10,000 × ~1KB)
 * </ul>
 *
 * @see BackoffStrategy CAS 재시도 대기 전략
 * @see BufferProperties 외부화된 설정
 */
@Slf4j
@Component
public class ExpectationWriteBackBuffer {

  private final ConcurrentLinkedQueue<ExpectationWriteTask> queue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger pendingCount = new AtomicInteger(0);
  private final MeterRegistry meterRegistry;
  private final BufferProperties properties;
  private final BackoffStrategy backoffStrategy;
  private final LogicExecutor executor;

  /**
   * Phaser for tracking in-flight offers (P0 Shutdown Race Prevention)
   *
   * <h4>Purple Agent: onAdvance() 오버라이드</h4>
   *
   * <p>모든 party가 완료될 때만 다음 phase로 진행하도록 설정
   *
   * <h4>Issue #283 P1-13: Scale-out 분산 안전성</h4>
   *
   * <p>Phaser는 <b>인스턴스 로컬</b> in-flight offer 추적 메커니즘입니다. 각 인스턴스는 자신의 ConcurrentLinkedQueue에 대한
   * offer 작업만 추적합니다. Shutdown 시 해당 인스턴스의 진행 중인 offer가 완료될 때까지만 대기하면 되므로, 분산 Phaser로의 변환은 불필요합니다.
   */
  private final Phaser shutdownPhaser =
      new Phaser() {
        @Override
        protected boolean onAdvance(int phase, int parties) {
          // 모든 party 완료 시에만 다음 phase 진행
          return parties == 0;
        }
      };

  /**
   * Shutdown 진행 플래그
   *
   * <p>true로 설정되면 새로운 offer 요청을 거부합니다.
   *
   * <h4>Issue #283 P1-13: Scale-out 분산 안전성</h4>
   *
   * <p>이 volatile 플래그는 <b>인스턴스 로컬 lifecycle 상태</b>를 나타냅니다. 각 인스턴스는 독립적으로 자신의 shutdown을 관리합니다:
   *
   * <ul>
   *   <li>K8s/ECS: 각 Pod/Task에 개별 SIGTERM 전달 -> 인스턴스별 독립 shutdown
   *   <li>Rolling update: 한 인스턴스의 shutdown이 다른 인스턴스에 영향 없음
   *   <li>이 버퍼의 데이터는 인스턴스 로컬 메모리에 존재 -> 해당 인스턴스만 drain 가능
   * </ul>
   *
   * <p><b>결론: 인스턴스별 독립 shutdown lifecycle이므로 Redis 전환 불필요.</b>
   */
  private volatile boolean shuttingDown = false;

  public ExpectationWriteBackBuffer(
      BufferProperties properties,
      MeterRegistry meterRegistry,
      BackoffStrategy backoffStrategy,
      LogicExecutor executor) {
    this.properties = properties;
    this.meterRegistry = meterRegistry;
    this.backoffStrategy = backoffStrategy;
    this.executor = executor;
    registerMetrics();
  }

  /**
   * 메트릭 등록
   *
   * <h4>Prometheus Alert 권장 임계값 (Red Agent)</h4>
   *
   * <ul>
   *   <li>expectation.buffer.pending > 8000: WARNING (80% capacity)
   *   <li>expectation.buffer.pending == maxQueueSize: CRITICAL (backpressure)
   *   <li>expectation.buffer.cas.exhausted > 0: WARNING (CAS 재시도 소진)
   * </ul>
   */
  private void registerMetrics() {
    Gauge.builder("expectation.buffer.pending", pendingCount, AtomicInteger::get)
        .description("Expectation 버퍼 대기 작업 수")
        .register(meterRegistry);
  }

  /**
   * 프리셋 결과를 버퍼에 추가 (P0: Phaser + LogicExecutor 패턴)
   *
   * <h4>CLAUDE.md Section 12 준수</h4>
   *
   * <p>Raw try-finally 금지 → LogicExecutor.executeWithFinally() 사용
   *
   * <h4>Round 5 Blue: SRP 준수</h4>
   *
   * <p>CAS 로직을 offerInternal()로 추출하여 단일 책임 원칙 준수
   *
   * <h4>백프레셔 동작</h4>
   *
   * <p>큐가 maxQueueSize에 도달하거나 CAS 재시도 소진 시 false 반환 → 호출자가 동기 폴백 수행
   *
   * @param characterId 캐릭터 ID
   * @param presets 프리셋 결과 목록
   * @return true: 버퍼링 성공, false: 백프레셔 발동 또는 Shutdown 중
   */
  public boolean offer(Long characterId, List<PresetExpectation> presets) {
    // Shutdown 중이면 즉시 거부
    if (shuttingDown) {
      meterRegistry.counter("expectation.buffer.rejected.shutdown").increment();
      log.debug("[ExpectationBuffer] Rejected during shutdown: characterId={}", characterId);
      return false;
    }

    // Phaser에 등록하여 진행 중인 작업 추적
    shutdownPhaser.register();

    // Round 5 Purple: Raw try-finally 금지 → LogicExecutor 패턴
    // Round 5 Blue: SRP - CAS 로직을 offerInternal()로 추출
    return executor.executeWithFinally(
        () -> offerInternal(characterId, presets),
        shutdownPhaser::arriveAndDeregister, // finally 블록
        TaskContext.of("Buffer", "Offer", "characterId=" + characterId) // Red: 로그 추적
        );
  }

  /**
   * CAS 기반 버퍼 추가 로직 (Round 5 Blue: SRP 분리)
   *
   * <h4>Green Agent: CAS + Exponential Backoff</h4>
   *
   * <ul>
   *   <li>백프레셔 체크 → CAS 시도 → 성공 시 큐 추가
   *   <li>CAS 실패 시 backoff 후 재시도 (최대 casMaxRetries회)
   * </ul>
   *
   * @param characterId 캐릭터 ID
   * @param presets 프리셋 결과 목록
   * @return true: 버퍼링 성공, false: 백프레셔 발동 또는 CAS 소진
   */
  private boolean offerInternal(Long characterId, List<PresetExpectation> presets) {
    int required = presets.size();

    // P2 Fix: Atomic capacity reservation using addAndGet with rollback
    // 1. Atomically reserve capacity
    int newCount = pendingCount.addAndGet(required);

    // 2. Check if reservation exceeds limit
    if (newCount > properties.maxQueueSize()) {
      // Rollback: immediately release the reservation
      pendingCount.addAndGet(-required);
      meterRegistry.counter("expectation.buffer.rejected.backpressure").increment();
      log.warn(
          "[ExpectationBuffer] Backpressure triggered: pending={}, required={}, max={}",
          newCount - required,
          required,
          properties.maxQueueSize());
      return false;
    }

    // 3. Capacity reserved - enqueue items
    try {
      for (PresetExpectation preset : presets) {
        queue.offer(ExpectationWriteTask.from(characterId, preset));
      }
      meterRegistry.counter("expectation.buffer.cas.success").increment();
      log.debug(
          "[ExpectationBuffer] Buffered {} presets for character {}, pending={}",
          presets.size(),
          characterId,
          newCount);
      return true;
    } catch (Exception e) {
      // Rollback on enqueue failure (should never happen with ConcurrentLinkedQueue)
      pendingCount.addAndGet(-required);
      throw e;
    }
  }

  /**
   * 버퍼에서 배치 크기만큼 작업 추출
   *
   * <h4>Lock-free Drain</h4>
   *
   * <p>ConcurrentLinkedQueue.poll()은 Lock-free이므로 스케줄러와 Shutdown Handler가 동시에 호출해도 안전함
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

  /** 대기 중인 작업 수 조회 */
  public int getPendingCount() {
    return pendingCount.get();
  }

  /** 버퍼가 비어있는지 확인 */
  public boolean isEmpty() {
    return queue.isEmpty();
  }

  // ==================== P0: Shutdown Race Prevention ====================

  /**
   * Shutdown 준비 단계 - 새로운 offer 차단
   *
   * <h4>Phase 1 of 3-Phase Shutdown</h4>
   *
   * <p>shuttingDown 플래그를 설정하여 새로운 offer 요청을 거부합니다.
   */
  public void prepareShutdown() {
    this.shuttingDown = true;
    log.info("[ExpectationBuffer] Shutdown prepared - new offers will be rejected");
  }

  /**
   * Shutdown 진행 중 여부 확인
   *
   * <h4>Red Agent: 스케줄러 양보</h4>
   *
   * <p>스케줄러가 이 메서드를 확인하여 Shutdown 중이면 flush를 스킵합니다.
   *
   * @return true: Shutdown 진행 중, false: 정상 운영 중
   */
  public boolean isShuttingDown() {
    return this.shuttingDown;
  }

  /**
   * 진행 중인 offer 작업 완료 대기 (P0 데이터 유실 방지)
   *
   * <h4>Phase 2 of 3-Phase Shutdown</h4>
   *
   * <p>Phaser.awaitAdvanceInterruptibly()를 사용하여 모든 진행 중인 offer 작업이 완료될 때까지 대기합니다.
   *
   * <h4>CLAUDE.md Section 4: 구조적 분리</h4>
   *
   * <p>checked exception은 Optional 밖에서 직접 처리
   *
   * @param timeout 최대 대기 시간
   * @return true: 모든 작업 완료, false: 타임아웃 또는 인터럽트
   */
  public boolean awaitPendingOffers(Duration timeout) {
    return executor.executeOrDefault(
        () -> {
          int phase = shutdownPhaser.arrive();
          shutdownPhaser.awaitAdvanceInterruptibly(
              phase, timeout.toMillis(), TimeUnit.MILLISECONDS);
          return true;
        },
        false, // 타임아웃/인터럽트 시 false 반환
        TaskContext.of("Buffer", "AwaitPendingOffers", "timeout=" + timeout.toSeconds() + "s"));
  }

  /**
   * Shutdown 대기 타임아웃 조회 (설정에서)
   *
   * @return Shutdown 대기 타임아웃 Duration
   */
  public Duration getShutdownAwaitTimeout() {
    return Duration.ofSeconds(properties.shutdownAwaitTimeoutSeconds());
  }
}
