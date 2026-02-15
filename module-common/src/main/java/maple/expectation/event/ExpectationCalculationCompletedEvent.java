package maple.expectation.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Event payload when expectation calculation is completed.
 *
 * <p>Contains full calculation results for MongoDB sync worker to process.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpectationCalculationCompletedEvent {
  private String taskId;
  private String userIgn;
  private String characterOcid;
  private String calculatedAt;
  private String totalExpectedCost;
  private Integer maxPresetNo;
  private String payload; // Serialized EquipmentExpectationResponseV4
}
