package maple.expectation.external.dto.v2;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Nexon API 캐릭터 기본 정보 응답 DTO
 *
 * <p>Endpoint: GET /maplestory/v1/character/basic
 *
 * @see <a href="https://openapi.nexon.com/ko/game/maplestory/?id=22">Nexon Open API</a>
 */
@Getter
@NoArgsConstructor
public class CharacterBasicResponse {

  @JsonProperty("character_name")
  private String characterName;

  @JsonProperty("world_name")
  private String worldName;

  @JsonProperty("character_class")
  private String characterClass;

  @JsonProperty("character_level")
  private int characterLevel;

  @JsonProperty("character_image")
  private String characterImage;

  @JsonProperty("character_guild_name")
  private String guildName;
}
