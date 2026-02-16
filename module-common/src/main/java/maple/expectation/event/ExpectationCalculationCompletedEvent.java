package maple.expectation.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * V5 CQRS: Event published when expectation calculation completes
 *
 * <h3>Purpose</h3>
 *
 * <ul>
 *   <li>Carries calculation result from worker to MongoDB sync worker
 *   <li>Serialized to Redis Stream character-sync
 *   <li>Contains full V4 response payload for MongoDB upsert
 * </ul>
 *
 * <h3>Flow</h3>
 *
 * <pre>
 * ExpectationCalculationWorker.calculate()
 *   → MongoSyncEventPublisher.publishCalculationCompleted()
 *   → Redis Stream (character-sync)
 *   → MongoDBSyncWorker.consume()
 *   → CharacterValuationView.upsert()
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpectationCalculationCompletedEvent {

  private String taskId;
  private String userIgn;
  private String characterOcid;
  private String characterClass;
  private Integer characterLevel;
  private String calculatedAt;
  private String totalExpectedCost;
  private Integer maxPresetNo;
  private String payload; // Serialized EquipmentExpectationResponseV4
}
