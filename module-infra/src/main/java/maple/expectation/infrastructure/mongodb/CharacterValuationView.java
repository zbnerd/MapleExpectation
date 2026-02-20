package maple.expectation.infrastructure.mongodb;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * V5 CQRS: MongoDB Read Model for Character Valuation Views
 *
 * <h3>Purpose</h3>
 *
 * <ul>
 *   <li>Read-optimized document for fast expectation queries
 *   <li>Denormalized: All preset data embedded in single document
 *   <li>Indexed: userIgn for O(1) lookup performance
 * </ul>
 *
 * <h3>CQRS Separation</h3>
 *
 * <ul>
 *   <li><b>Query Side:</b> This document (MongoDB)
 *   <li><b>Command Side:</b> MySQL game_character, character_equipment
 *   <li><b>Sync:</b> Redis Stream character-sync topic
 * </ul>
 *
 * <h3>TTL Strategy</h3>
 *
 * <p>24-hour automatic expiry. Stale data removed without manual invalidation. TTL index is created
 * on {@code calculatedAt} field via {@link MongoDBConfig}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "character_valuation_views")
@CompoundIndex(def = "{'userIgn': 1, 'calculatedAt': -1}")
public class CharacterValuationView {

  @Id private String id;

  @Indexed private String userIgn;

  @Indexed(unique = true)
  private String messageId;

  @Indexed private String characterOcid;

  private String characterClass;

  private Integer characterLevel;

  private Instant calculatedAt;

  private Instant lastApiSyncAt;

  private Long version;

  @Indexed private Long totalExpectedCost;

  @JsonIgnore private Integer maxPresetNo;

  private List<PresetView> presets;

  private Boolean fromCache;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PresetView {
    private Integer presetNo;
    private Long totalExpectedCost;
    private String totalCostText;
    private CostBreakdownView costBreakdown;
    private List<ItemExpectationView> items;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class CostBreakdownView {
    private Long blackCubeCost;
    private Long redCubeCost;
    private Long additionalCubeCost;
    private Long starforceCost;
    private Long flameCost;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ItemExpectationView {
    private String itemName;
    private Long expectedCost;
    private String costText;
  }
}
