package maple.expectation.service.v4.buffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.config.BufferProperties;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
import maple.expectation.support.TestLogicExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P2 Atomic Backpressure Verification Test
 *
 * <p>This test verifies that the backpressure mechanism correctly enforces maxQueueSize under high
 * concurrency, preventing the queue from exceeding its intended capacity.
 */
@Slf4j
class ExpectationWriteBackBufferConcurrencyTest {

  private ExpectationWriteBackBuffer buffer;
  private MeterRegistry meterRegistry;
  private BufferProperties properties;
  private BackoffStrategy backoffStrategy;
  private maple.expectation.infrastructure.executor.LogicExecutor executor;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    executor = TestLogicExecutors.passThrough();

    // BufferProperties: shutdownAwaitTimeoutSeconds, casMaxRetries, maxQueueSize
    properties =
        new BufferProperties(
            30, // shutdownAwaitTimeoutSeconds
            10, // casMaxRetries (no longer used in atomic implementation)
            100 // maxQueueSize: small for testing
            );
    // BackoffStrategy is no longer needed for atomic implementation
    backoffStrategy = null;

    buffer = new ExpectationWriteBackBuffer(properties, meterRegistry, backoffStrategy, executor);
  }

  @AfterEach
  void tearDown() {
    meterRegistry.close();
  }

  @Test
  void atomicBackpressure_ShouldEnforceMaxQueueSizeUnderHighConcurrency() throws Exception {
    // Given: Small buffer size for easier testing
    int maxQueueSize = properties.maxQueueSize();
    int threads = 50; // High concurrency
    int offersPerThread = 10;
    int presetsPerOffer = 5;

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threads);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger rejectedCount = new AtomicInteger(0);

    ExecutorService threadPool = Executors.newFixedThreadPool(threads);

    // When: All threads attempt to offer simultaneously
    for (int t = 0; t < threads; t++) {
      final long characterId = t;
      threadPool.submit(
          () -> {
            try {
              startLatch.await(); // Synchronize start

              for (int i = 0; i < offersPerThread; i++) {
                List<PresetExpectation> presets = createPresets(presetsPerOffer);

                // Directly call offerInternal (bypassing executor for test)
                boolean result = buffer.offer(characterId, presets);

                if (result) {
                  successCount.incrementAndGet();
                } else {
                  rejectedCount.incrementAndGet();
                }
              }
            } catch (Exception e) {
              e.printStackTrace();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown(); // Start all threads simultaneously
    boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
    threadPool.shutdown();

    assertThat(finished).isTrue();

    // Then: Queue MUST NOT exceed maxQueueSize
    int actualPending = buffer.getPendingCount();
    log.info("Concurrency test results:");
    log.info("  Max queue size: {}", maxQueueSize);
    log.info("  Actual pending: {}", actualPending);
    log.info("  Successful offers: {}", successCount.get());
    log.info("  Rejected offers: {}", rejectedCount.get());
    log.info("  Total attempted: {}", (threads * offersPerThread));

    // CRITICAL ASSERTION: Atomic backpressure must enforce the limit
    assertThat(actualPending)
        .as("Queue must NOT exceed maxQueueSize under concurrency")
        .isLessThanOrEqualTo(maxQueueSize);

    // Verify that some offers were rejected due to backpressure
    assertThat(rejectedCount.get())
        .as("Some offers should be rejected due to backpressure")
        .isGreaterThan(0);

    // Verify the counter matches actual queue size
    // Drain the queue to verify (use actualPending as drain size, not Integer.MAX_VALUE)
    List<ExpectationWriteTask> drained = buffer.drain(actualPending);
    assertThat(drained.size())
        .as("Drained count should match pending counter")
        .isEqualTo(actualPending);
  }

  @Test
  void atomicBackpressure_RollbackOnExceed() {
    // Given: Buffer near capacity
    List<PresetExpectation> presets = createPresets(10);
    for (int i = 0; i < 9; i++) { // Fill to 90
      buffer.offer(1L, presets);
    }
    assertThat(buffer.getPendingCount()).isEqualTo(90);

    // When: Offer that exceeds capacity
    boolean result = buffer.offer(1L, createPresets(20)); // 90 + 20 = 110 > 100

    // Then: Should be rejected AND counter rolled back
    assertThat(result).isFalse();
    assertThat(buffer.getPendingCount())
        .as("Counter should be rolled back after rejection")
        .isEqualTo(90);
  }

  @Test
  void atomicBackpressure_MultipleSequentialOffers() {
    // Given: Empty buffer
    assertThat(buffer.getPendingCount()).isZero();

    // When: Multiple small offers
    for (int i = 0; i < 10; i++) {
      buffer.offer(1L, createPresets(5));
    }

    // Then: Counter should be accurate
    assertThat(buffer.getPendingCount()).isEqualTo(50);

    // When: Drain
    List<ExpectationWriteTask> drained = buffer.drain(100);
    assertThat(drained).hasSize(50);
    assertThat(buffer.getPendingCount()).isZero();
  }

  private List<PresetExpectation> createPresets(int count) {
    List<PresetExpectation> presets = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      // PresetExpectation: use builder pattern
      presets.add(
          PresetExpectation.builder()
              .presetNo(i)
              .totalExpectedCost(java.math.BigDecimal.valueOf(1000000L))
              .totalCostText("100억")
              .costBreakdown(
                  maple.expectation.dto.v4.EquipmentExpectationResponseV4.CostBreakdownDto.empty())
              .items(new java.util.ArrayList<>())
              .build());
    }
    return presets;
  }
}
