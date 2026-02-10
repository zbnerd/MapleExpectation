package maple.expectation.external.dto.v2;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Nexon API character/list 응답 DTO
 *
 * <p>API: GET /maplestory/v1/character/list
 *
 * <p>응답 구조:
 *
 * <pre>
 * {
 *   "account_list": [
 *     {
 *       "account_id": "...",
 *       "character_list": [...]
 *     }
 *   ]
 * }
 * </pre>
 */
public record CharacterListResponse(@JsonProperty("account_list") List<AccountInfo> accountList) {

  /** 모든 계정의 캐릭터 목록을 평탄화하여 반환 */
  public List<CharacterInfo> getAllCharacters() {
    if (accountList == null || accountList.isEmpty()) {
      return List.of();
    }
    return accountList.stream()
        .filter(account -> account.characterList() != null)
        .flatMap(account -> account.characterList().stream())
        .toList();
  }

  /** 계정 정보 */
  public record AccountInfo(
      @JsonProperty("account_id") String accountId,
      @JsonProperty("character_list") List<CharacterInfo> characterList) {}

  /** 개별 캐릭터 정보 */
  public record CharacterInfo(
      @JsonProperty("ocid") String ocid,
      @JsonProperty("character_name") String characterName,
      @JsonProperty("world_name") String worldName,
      @JsonProperty("character_class") String characterClass,
      @JsonProperty("character_level") int characterLevel) {}
}
