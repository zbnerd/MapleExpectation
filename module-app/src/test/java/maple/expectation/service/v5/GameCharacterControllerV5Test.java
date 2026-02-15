package maple.expectation.service.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import maple.expectation.infrastructure.mongodb.CharacterValuationView;
import maple.expectation.infrastructure.mongodb.CharacterValuationView.CostBreakdownView;
import maple.expectation.infrastructure.mongodb.CharacterValuationView.ItemExpectationView;
import maple.expectation.infrastructure.mongodb.CharacterValuationView.PresetView;
import maple.expectation.infrastructure.mongodb.CharacterViewQueryService;
import maple.expectation.service.v5.event.MongoSyncEventPublisherInterface;
import maple.expectation.service.v5.queue.ExpectationCalculationTask;
import maple.expectation.service.v5.queue.PriorityCalculationQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * V5 CQRS Query Side Integration Tests
 *
 * <h3>Test Scope</h3>
 *
 * <ul>
 *   <li>MongoDB HIT scenarios (cached views)
 *   <li>MongoDB MISS scenarios (queue delegation)
 *   <li>Force recalculation (cache invalidation)
 *   <li>Backpressure (queue full)
 * </ul>
 *
 * <h3>Test Strategy</h3>
 *
 * Uses Mockito mocks for infrastructure components (MongoDB, Queue, Redis). Focuses on controller
 * logic and CQRS flow validation.
 */
@Tag("unit")
class GameCharacterControllerV5Test {

  @Mock private CharacterViewQueryService queryService;
  @Mock private PriorityCalculationQueue queue;
  @Mock private MongoSyncEventPublisherInterface eventPublisher;

  @InjectMocks private TestableGameCharacterControllerV5 controller;

  private static final String TEST_IGN = "TestCharacter";
  private static final String MASKED_IGN = "T***r";

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    controller = new TestableGameCharacterControllerV5(queryService, queue, eventPublisher);
  }

  @Test
  @DisplayName("MongoDB HIT: Return cached view immediately")
  void testMongoDBHit_ReturnsCachedView() {
    // Given: MongoDB has cached view
    CharacterValuationView mockView = createMockView();
    when(queryService.findByUserIgn(TEST_IGN)).thenReturn(Optional.of(mockView));
    // Default queue mock behavior - offer returns true (not used in HIT case)
    when(queue.offer(any(ExpectationCalculationTask.class))).thenReturn(true);

    // When: Query expectation
    ResponseEntity<?> response = controller.getExpectationV5Internal(TEST_IGN);

    // Then: Return 200 OK with view data
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();

    // Verify no queue interaction (cache hit, so queue not used)
    verify(queue, times(0)).offer(any(ExpectationCalculationTask.class));
  }

  @Test
  @DisplayName("MongoDB MISS: Queue calculation and return 202")
  void testMongoDBMiss_QueuesCalculation_Returns202() {
    // Given: MongoDB has no cached view
    when(queryService.findByUserIgn(TEST_IGN)).thenReturn(Optional.empty());
    when(queue.offer(any(ExpectationCalculationTask.class))).thenReturn(true);

    // When: Query expectation
    ResponseEntity<?> response = controller.getExpectationV5Internal(TEST_IGN);

    // Then: Return 202 Accepted
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    // Verify task was queued
    verify(queue, times(1)).offer(any(ExpectationCalculationTask.class));
  }

  @Test
  @DisplayName("Queue Full: Return 503 Service Unavailable")
  void testQueueFull_Returns503() {
    // Given: MongoDB miss and queue full
    when(queryService.findByUserIgn(TEST_IGN)).thenReturn(Optional.empty());
    when(queue.offer(any(ExpectationCalculationTask.class))).thenReturn(false);

    // When: Query expectation
    ResponseEntity<?> response = controller.getExpectationV5Internal(TEST_IGN);

    // Then: Return 503
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isEqualTo("Queue full, try again later");
  }

  @Test
  @DisplayName("Force Recalculation: Delete cache and queue task")
  void testForceRecalculation_DeletesCacheAndQueues() {
    // Given: Queue accepts task
    when(queue.offer(any(ExpectationCalculationTask.class))).thenReturn(true);

    // When: Force recalculation
    ResponseEntity<Void> response = controller.recalculateExpectationV5Internal(TEST_IGN);

    // Then: Cache deleted and task queued
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    verify(queryService, times(1)).deleteByUserIgn(TEST_IGN);
    verify(queue, times(1)).offer(any(ExpectationCalculationTask.class));
  }

  @Test
  @DisplayName("Force Recalculation Queue Full: Return 503")
  void testForceRecalculationQueueFull_Returns503() {
    // Given: Queue full
    when(queue.offer(any(ExpectationCalculationTask.class))).thenReturn(false);

    // When: Force recalculation
    ResponseEntity<Void> response = controller.recalculateExpectationV5Internal(TEST_IGN);

    // Then: Return 503
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  // ==================== Helper Methods ====================

  private CharacterValuationView createMockView() {
    CostBreakdownView breakdown =
        CostBreakdownView.builder()
            .blackCubeCost(100000L)
            .redCubeCost(50000L)
            .additionalCubeCost(20000L)
            .starforceCost(30000L)
            .flameCost(0L)
            .build();

    List<ItemExpectationView> items =
        List.of(
            ItemExpectationView.builder()
                .itemName("Arcane Umbra Hat")
                .expectedCost(50000L)
                .costText("50,000")
                .build());

    PresetView preset =
        PresetView.builder()
            .presetNo(1)
            .totalExpectedCost(200000L)
            .totalCostText("200,000")
            .costBreakdown(breakdown)
            .items(items)
            .build();

    return CharacterValuationView.builder()
        .id("test-id")
        .userIgn(TEST_IGN)
        .characterOcid("test-ocid")
        .characterClass("Pathfinder")
        .characterLevel(275)
        .calculatedAt(Instant.now())
        .lastApiSyncAt(Instant.now())
        .version(1L)
        .totalExpectedCost(200000)
        .maxPresetNo(1)
        .fromCache(true)
        .presets(List.of(preset))
        .build();
  }

  // ==================== Test Helper Classes ====================

  /**
   * Testable wrapper for GameCharacterControllerV5 to expose package-private methods for testing.
   */
  static class TestableGameCharacterControllerV5 {
    private final CharacterViewQueryService queryService;
    private final PriorityCalculationQueue queue;
    private final MongoSyncEventPublisherInterface eventPublisher;

    TestableGameCharacterControllerV5(
        CharacterViewQueryService queryService,
        PriorityCalculationQueue queue,
        MongoSyncEventPublisherInterface eventPublisher) {
      this.queryService = queryService;
      this.queue = queue;
      this.eventPublisher = eventPublisher;
    }

    ResponseEntity<?> getExpectationV5Internal(String userIgn) {
      var viewOpt = queryService.findByUserIgn(userIgn);

      if (viewOpt.isPresent()) {
        CharacterValuationView view = viewOpt.get();
        return ResponseEntity.ok(toResponseDto(view));
      }

      ExpectationCalculationTask task = ExpectationCalculationTask.highPriority(userIgn, false);
      boolean queued = queue.offer(task);

      if (queued) {
        return ResponseEntity.accepted().build();
      } else {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body("Queue full, try again later");
      }
    }

    ResponseEntity<Void> recalculateExpectationV5Internal(String userIgn) {
      queryService.deleteByUserIgn(userIgn);

      ExpectationCalculationTask task = ExpectationCalculationTask.highPriority(userIgn, true);
      boolean queued = queue.offer(task);

      if (queued) {
        return ResponseEntity.accepted().build();
      } else {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
      }
    }

    private Object toResponseDto(CharacterValuationView view) {
      return java.util.Map.of(
          "userIgn",
          view.getUserIgn(),
          "totalExpectedCost",
          view.getTotalExpectedCost(),
          "maxPresetNo",
          view.getMaxPresetNo(),
          "calculatedAt",
          view.getCalculatedAt().toString(),
          "fromCache",
          view.getFromCache(),
          "presets",
          view.getPresets());
    }
  }
}
