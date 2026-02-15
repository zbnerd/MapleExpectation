package maple.expectation.service.v5.queue;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** V5 CQRS: Expectation calculation task for priority queue */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpectationCalculationTask {

  private String taskId;

  private String userIgn;

  private QueuePriority priority;

  private Instant createdAt;

  private Instant startedAt;

  private Instant completedAt;

  private boolean forceRecalculation;

  private String requesterInstanceId;

  public static ExpectationCalculationTask highPriority(String userIgn, boolean force) {
    return ExpectationCalculationTask.builder()
        .taskId(UUID.randomUUID().toString())
        .userIgn(userIgn)
        .priority(QueuePriority.HIGH)
        .createdAt(Instant.now())
        .forceRecalculation(force)
        .build();
  }

  public static ExpectationCalculationTask lowPriority(String userIgn) {
    return ExpectationCalculationTask.builder()
        .taskId(UUID.randomUUID().toString())
        .userIgn(userIgn)
        .priority(QueuePriority.LOW)
        .createdAt(Instant.now())
        .forceRecalculation(false)
        .build();
  }
}
