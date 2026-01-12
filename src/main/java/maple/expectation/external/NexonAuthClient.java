package maple.expectation.external;

import maple.expectation.external.dto.v2.CharacterListResponse;

import java.util.Optional;

/**
 * 인증용 Nexon API 클라이언트 인터페이스
 *
 * <p>BYOK (Bring Your Own Key) 방식:
 * 사용자가 제공한 API Key를 직접 사용하여 Nexon API 호출</p>
 */
public interface NexonAuthClient {

    /**
     * 사용자의 캐릭터 목록을 조회합니다.
     *
     * @param apiKey 사용자의 Nexon API Key
     * @return 캐릭터 목록 응답 (Optional)
     */
    Optional<CharacterListResponse> getCharacterList(String apiKey);

    /**
     * API Key 유효성을 검증합니다.
     *
     * @param apiKey 사용자의 Nexon API Key
     * @return 유효 여부
     */
    boolean validateApiKey(String apiKey);
}
