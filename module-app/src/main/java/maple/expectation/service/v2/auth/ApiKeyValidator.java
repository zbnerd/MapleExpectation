package maple.expectation.service.v2.auth;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.auth.CharacterNotOwnedException;
import maple.expectation.error.exception.auth.InvalidApiKeyException;
import maple.expectation.external.NexonAuthClient;
import maple.expectation.external.dto.v2.CharacterListResponse;
import org.springframework.stereotype.Service;

/**
 * API Key 검증 및 캐릭터 소유권 확인 서비스
 *
 * <p>책임 (Single Responsibility Principle):
 *
 * <ul>
 *   <li>Nexon API Key 유효성 검증
 *   <li>캐릭터 소유권 확인 (userIgn이 캐릭터 목록에 있는지)
 *   <li>사용자의 모든 캐릭터 OCID 수집
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyValidator {

  private final NexonAuthClient nexonAuthClient;

  /**
   * API Key를 검증하고 캐릭터 목록을 조회합니다.
   *
   * @param apiKey Nexon API Key
   * @return 캐릭터 목록 응답
   * @throws InvalidApiKeyException API Key가 유효하지 않은 경우
   */
  public CharacterListResponse validateApiKey(String apiKey) {
    log.debug("Validating API key");
    return nexonAuthClient.getCharacterList(apiKey).orElseThrow(InvalidApiKeyException::new);
  }

  /**
   * 캐릭터 소유권을 확인합니다.
   *
   * @param userIgn 사용자 캐릭터명
   * @param characters 캐릭터 목록
   * @return 소유권 여부
   */
  public boolean verifyCharacterOwnership(
      String userIgn, List<CharacterListResponse.CharacterInfo> characters) {
    boolean ownsCharacter =
        characters.stream().anyMatch(c -> c.characterName().equalsIgnoreCase(userIgn));

    if (!ownsCharacter) {
      log.warn("Character ownership verification failed: userIgn={}", userIgn);
    }

    return ownsCharacter;
  }

  /**
   * API Key를 검증하고 캐릭터 소유권을 확인합니다.
   *
   * @param apiKey Nexon API Key
   * @param userIgn 사용자 캐릭터명
   * @return 캐릭터 소유권 검증 결과
   * @throws InvalidApiKeyException API Key가 유효하지 않은 경우
   * @throws CharacterNotOwnedException 캐릭터가 사용자 소유가 아닌 경우
   */
  public CharacterOwnershipValidationResult validateAndVerifyOwnership(
      String apiKey, String userIgn) {
    // 1. API Key 검증
    CharacterListResponse characterList = validateApiKey(apiKey);

    // 2. 캐릭터 목록 추출
    List<CharacterListResponse.CharacterInfo> characters = characterList.getAllCharacters();

    // 3. 소유권 확인
    boolean ownsCharacter = verifyCharacterOwnership(userIgn, characters);

    if (!ownsCharacter) {
      throw new CharacterNotOwnedException(userIgn);
    }

    // 4. 모든 캐릭터 OCID 수집
    Set<String> myOcids =
        characters.stream()
            .map(CharacterListResponse.CharacterInfo::ocid)
            .collect(Collectors.toSet());

    log.info("API key validation successful: userIgn={}, ocids={}", userIgn, myOcids.size());

    return new CharacterOwnershipValidationResult(myOcids);
  }

  /**
   * 캐릭터 소유권 검증 결과
   *
   * @param myOcids 사용자가 소유한 모든 캐릭터 OCID 목록
   */
  public record CharacterOwnershipValidationResult(Set<String> myOcids) {
    public CharacterOwnershipValidationResult {
      if (myOcids == null || myOcids.isEmpty()) {
        throw new IllegalArgumentException("myOcids must not be null or empty");
      }
    }
  }
}
