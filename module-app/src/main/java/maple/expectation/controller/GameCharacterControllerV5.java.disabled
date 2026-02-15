package maple.expectation.controller;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.mongodb.CharacterValuationView;
import maple.expectation.infrastructure.mongodb.CharacterViewQueryService;
import maple.expectation.service.v5.event.MongoSyncEventPublisher;
import maple.expectation.service.v5.queue.ExpectationCalculationTask;
import maple.expectation.service.v5.queue.PriorityCalculationQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V5 CQRS 캐릭터 컨트롤러
 *
 * <h3>CQRS Pattern</h3>
 *
 * <ul>
 *   <li><b>Query Side:</b> MongoDB CharacterValuationView (fast read)
 *   <li><b>Command Side:</b> Priority Queue + Calculation Worker
 *   <li><b>Sync:</b> Redis Stream character-sync
 * </ul>
 *
 * <h3>Flow</h3>
 *
 * <ol>
 *   <li>Check MongoDB for existing view
 *   <li>If HIT: Return immediately
 *   <li>If MISS: Queue calculation task (HIGH priority)
 *   <li>Return 202 Accepted with Location header
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/v5/characters")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "v5.enabled", havingValue = "true", matchIfMissing = false)
public class GameCharacterControllerV5 {

  private final CharacterViewQueryService queryService;
  private final PriorityCalculationQueue queue;
  private final MongoSyncEventPublisher eventPublisher;
  private final LogicExecutor executor;

  /**
   * V5: 캐릭터 기대값 조회 (CQRS - MongoDB Read First)
   *
   * <h4>Flowchart Flow</h4>
   *
   * <pre>
   * Client Request → MongoDB Check
   *   → HIT: Return JSON (1-10ms)
   *   → MISS: Queue Calculation → Return 202 Accepted
   * </pre>
   *
   * @param userIgn 캐릭터 IGN
   * @return MongoDB view or 202 Accepted if calculation queued
   */
  @GetMapping("/{userIgn}/expectation")
  // @PreAuthorize("hasRole('ADMIN') or hasRole('USER')") // TODO: 인증 구현 후 활성화
  public CompletableFuture<ResponseEntity<?>> getExpectationV5(
      @PathVariable @NotBlank String userIgn) {

    log.debug("[V5] Query expectation for: {}", maskIgn(userIgn));

    // Use a CompletableFuture to wrap the response
    return CompletableFuture.supplyAsync(() -> getExpectationV5Internal(userIgn));
  }

  private ResponseEntity<?> getExpectationV5Internal(String userIgn) {
    // 1. Query Side: Check MongoDB first
    var viewOpt = queryService.findByUserIgn(userIgn);

    if (viewOpt.isPresent()) {
      CharacterValuationView view = viewOpt.get();
      log.debug("[V5] MongoDB HIT: {}, calculatedAt={}", maskIgn(userIgn), view.getCalculatedAt());
      return ResponseEntity.ok(toResponseDto(view));
    }

    // 2. MISS: Queue to Command Side
    ExpectationCalculationTask task = ExpectationCalculationTask.highPriority(userIgn, false);
    boolean queued = queue.offer(task);

    if (queued) {
      log.info("[V5] MongoDB MISS, queued calculation: {}", maskIgn(userIgn));
      return ResponseEntity.accepted().build();
    } else {
      log.warn("[V5] Queue full, rejecting: {}", maskIgn(userIgn));
      return ResponseEntity.status(503).body("Queue full, try again later");
    }
  }

  /**
   * V5: 기대값 강제 재계산 (Cache Invalidation)
   *
   * @param userIgn 캐릭터 IGN
   * @return 202 Accepted if calculation queued
   */
  @PostMapping("/{userIgn}/expectation/recalculate")
  // @PreAuthorize("hasRole('ADMIN') or hasRole('USER')") // TODO: 인증 구현 후 활성화
  public CompletableFuture<ResponseEntity<Void>> recalculateExpectationV5(
      @PathVariable String userIgn) {

    log.info("[V5] Force recalculation requested: {}", maskIgn(userIgn));

    // Use a CompletableFuture to wrap the response
    return CompletableFuture.supplyAsync(
        () -> {
          // Invalidate MongoDB cache
          queryService.deleteByUserIgn(userIgn);

          // Queue with force=true
          ExpectationCalculationTask task = ExpectationCalculationTask.highPriority(userIgn, true);
          boolean queued = queue.offer(task);

          if (queued) {
            return ResponseEntity.accepted().build();
          } else {
            return ResponseEntity.status(503).build();
          }
        });
  }

  // ==================== Helper Methods ====================

  private Object toResponseDto(CharacterValuationView view) {
    // Simplified response for now - TODO: Map full structure
    return Map.of(
        "userIgn", view.getUserIgn(),
        "totalExpectedCost", view.getTotalExpectedCost(),
        "maxPresetNo", view.getMaxPresetNo(),
        "calculatedAt", view.getCalculatedAt().toString(),
        "fromCache", view.getFromCache(),
        "presets", view.getPresets());
  }

  private String maskIgn(String ign) {
    if (ign == null || ign.length() < 2) return "***";
    return ign.charAt(0) + "***" + ign.substring(ign.length() - 1);
  }
}
