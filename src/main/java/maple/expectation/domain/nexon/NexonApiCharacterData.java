package maple.expectation.domain.nexon;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nexon API character data response.
 *
 * <p>This is a simplified version of the actual Nexon API response. In production, this should
 * match the actual Nexon Open API schema.
 *
 * <p><strong>Anti-Corruption Layer:</strong> This DTO isolates the external API structure from
 * internal domain models. Changes in Nexon API should not propagate to internal business logic.
 *
 * @see <a href="https://openapi.nexon.com/maplestory">Nexon Open API Documentation</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "nexon_character_data")
public class NexonApiCharacterData {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @JsonProperty("ocid")
  private String ocid;

  @JsonProperty("character_name")
  private String characterName;

  @JsonProperty("world_name")
  private String worldName;

  @JsonProperty("character_class")
  private String characterClass;

  @JsonProperty("character_level")
  private Integer characterLevel;

  @JsonProperty("character_guild_name")
  private String guildName;

  @JsonProperty("character_image")
  private String characterImageUrl;

  @JsonProperty("date")
  private Instant date;

  // Additional fields can be added as needed
  // This is a minimal subset for demonstration
}
