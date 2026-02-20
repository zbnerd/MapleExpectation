package maple.expectation.controller;

import jakarta.validation.constraints.NotBlank;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.v5.EquipmentExpectationResponseV5;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.mongodb.CharacterViewQueryService;
import maple.expectation.service.v5.mapper.CharacterViewMapper;
import maple.expectation.service.v5.queue.ExpectationCalculationTask;
import maple.expectation.service.v5.queue.PriorityCalculationQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
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
 *   <li><b>Query Side:</b> MongoDB CharacterValuationView (fast read 1-10ms)
 *   <li><b>Command Side:</b> Priority Queue + Calculation Worker
 *   <li><b>Sync:</b> Redis Stream character-sync → MongoDB upsert
 * </ul>
 *
 * <h3>Flowchart Flow</h3>
 *
 * <pre>
 * Client Request → MongoDB Check (Query Side)
 *   → HIT: Return JSON (1-10ms) [200 OK]
 *   → MISS: Queue Calculation (Command Side) → Return 202 Accepted
 * </pre>
 *
 * <h3>SOLID Compliance</h3>
 *
 * <ul>
 *   <li><b>SRP:</b> Only handles HTTP request/response, delegates to services
 *   <li><b>DIP:</b> Depends on CharacterViewQueryService abstraction
 *   <li><b>LogicExecutor:</b> All operations via executeOrDefault/executeVoid
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v5/characters")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "v5.enabled", havingValue = "true", matchIfMissing = false)
public class GameCharacterControllerV5 {

  private final CharacterViewQueryService queryService;
  private final PriorityCalculationQueue queue;
  private final LogicExecutor executor;

  /**
   * V5: 캐릭터 기대값 조회 (CQRS - MongoDB Read First)
   *
   * <h4>Flowchart Flow</h4>
   *
   * <pre>
   * Client Request → MongoDB Check (Query Side)
   *   → HIT: Return EquipmentExpectationResponseV5 (1-10ms)
   *   → MISS: Queue Calculation → Return 202 Accepted
   * </pre>
   *
   * <h4>LogicExecutor Usage (Section 12)</h4>
   *
   * <ul>
   *   <li>executeOrDefault: MongoDB lookup with null fallback
   *   <li>executeVoid: Queue operation (no return value)
   * </ul>
   *
   * @param userIgn 캐릭터 IGN
   * @return V5 response DTO or 202 Accepted if calculation queued
   */
  @GetMapping("/{userIgn}/expectation")
  // @PreAuthorize("hasRole('ADMIN') or hasRole('USER')") // TODO: 인증 구현 후 활성화
  public CompletableFuture<ResponseEntity<?>> getExpectationV5(
      @PathVariable @NotBlank String userIgn) {

    log.debug("[V5] Query expectation for: {}", maskIgn(userIgn));

    // Use CompletableFuture for async response
    return CompletableFuture.supplyAsync(() -> processMongoDBCacheFirstLookup(userIgn));
  }

  private ResponseEntity<?> processMongoDBCacheFirstLookup(String userIgn) {
    TaskContext context = TaskContext.of("V5Query", "CacheFirstLookup", userIgn);

    // 1. Query Side: Check MongoDB first (LogicExecutor: executeOrDefault)
    Optional<EquipmentExpectationResponseV5> cachedResult =
        executor.executeOrDefault(
            () -> queryService.findByUserIgn(userIgn).flatMap(CharacterViewMapper::toResponseDto),
            Optional.empty(),
            context);

    // 2. HIT: Return immediately (1-10ms)
    if (cachedResult.isPresent()) {
      log.debug("[V5] MongoDB HIT: {}", maskIgn(userIgn));
      return ResponseEntity.ok(cachedResult.get());
    }

    // 3. MISS: Queue to Command Side
    return queueCalculationTask(userIgn, false, context);
  }

  /**
   * V5: 기대값 강제 재계산 (Cache Invalidation)
   *
   * <h4>Flow</h4>
   *
   * <ol>
   *   <li>Delete MongoDB view (invalidate cache)
   *   <li>Queue calculation with force=true
   *   <li>Return 202 Accepted
   * </ol>
   *
   * @param userIgn 캐릭터 IGN
   * @return 202 Accepted if calculation queued
   */
  @PostMapping("/{userIgn}/expectation/recalculate")
  // @PreAuthorize("hasRole('ADMIN') or hasRole('USER')") // TODO: 인증 구현 후 활성화
  public CompletableFuture<ResponseEntity<?>> recalculateExpectationV5(
      @PathVariable String userIgn) {

    log.info("[V5] Force recalculation requested: {}", maskIgn(userIgn));

    return CompletableFuture.supplyAsync(() -> processCacheInvalidation(userIgn));
  }

  private ResponseEntity<?> processCacheInvalidation(String userIgn) {
    TaskContext context = TaskContext.of("V5Query", "InvalidateAndRecalculate", userIgn);

    // 1. Invalidate MongoDB cache
    executor.executeVoid(() -> queryService.deleteByUserIgn(userIgn), context);

    // 2. Queue with force=true
    return queueCalculationTask(userIgn, true, context);
  }

  // ==================== Private Helper Methods ====================

  /**
   * Queue calculation task with proper error handling
   *
   * <p>LogicExecutor Section 12 compliance: executeOrDefault for boolean return
   *
   * @return 202 Accepted if queued, 503 Service Unavailable if queue full
   */
  private ResponseEntity<?> queueCalculationTask(
      String userIgn, boolean forceRecalculation, TaskContext context) {

    boolean queued =
        executor.executeOrDefault(
            () -> {
              ExpectationCalculationTask task =
                  ExpectationCalculationTask.highPriority(userIgn, forceRecalculation);
              return queue.offer(task);
            },
            false,
            context);

    if (queued) {
      log.info("[V5] MongoDB MISS, queued calculation: {}", maskIgn(userIgn));
      return ResponseEntity.accepted().build();
    } else {
      log.warn("[V5] Queue full, rejecting: {}", maskIgn(userIgn));
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body("Queue full, try again later");
    }
  }

  /** Mask IGN for privacy logging */
  private String maskIgn(String ign) {
    if (ign == null || ign.length() < 2) return "***";
    return ign.charAt(0) + "***" + ign.substring(ign.length() - 1);
  }
}
