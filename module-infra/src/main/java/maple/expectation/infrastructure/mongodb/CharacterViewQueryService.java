package maple.expectation.infrastructure.mongodb;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * V5 CQRS Query Side Service - MongoDB Read Operations
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Fast read from CharacterValuationView collection
 *   <li>LogicExecutor pattern for exception handling
 *   <li>Micrometer metrics for monitoring
 *   <li>Graceful degradation on MongoDB failure
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterViewQueryService {

  private final CharacterValuationRepository repository;
  private final MongoTemplate mongoTemplate;
  private final LogicExecutor executor;
  private final MeterRegistry meterRegistry;

  /** Find character valuation view by user IGN (O(1) indexed lookup) */
  public Optional<CharacterValuationView> findByUserIgn(String userIgn) {
    TaskContext context = TaskContext.of("MongoQuery", "FindByUserIgn", userIgn);

    return executor.executeOrDefault(
        () -> {
          Optional<CharacterValuationView> result = repository.findByUserIgn(userIgn);
          if (result.isPresent()) {
            meterRegistry
                .timer("mongodb.query.latency", "operation", "hit")
                .record(java.time.Duration.ofMillis(1));
            return result;
          }
          meterRegistry
              .timer("mongodb.query.latency", "operation", "miss")
              .record(java.time.Duration.ofMillis(1));
          return Optional.empty();
        },
        Optional.empty(),
        context);
  }

  /** Upsert character valuation view (insert or update) */
  public void upsert(CharacterValuationView view) {
    TaskContext context = TaskContext.of("MongoQuery", "Upsert", view.getUserIgn());

    executor.executeVoid(
        () -> {
          repository.save(view);
        },
        context);
  }

  /** Delete by user IGN (for invalidation) */
  public void deleteByUserIgn(String userIgn) {
    TaskContext context = TaskContext.of("MongoQuery", "Delete", userIgn);

    executor.executeVoid(
        () -> {
          repository.deleteByUserIgn(userIgn);
        },
        context);
  }
}
