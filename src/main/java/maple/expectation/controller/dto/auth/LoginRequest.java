package maple.expectation.controller.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청 DTO
 *
 * <p>보안 고려사항:
 * <ul>
 *   <li>toString() 오버라이드: API Key 마스킹 (로그 노출 방지)</li>
 *   <li>마스킹 형식: 앞 4자리 + **** + 뒤 4자리</li>
 * </ul>
 * </p>
 *
 * @param apiKey  Nexon API Key
 * @param userIgn 사용자 캐릭터 닉네임 (소유권 검증용)
 */
public record LoginRequest(
    @NotBlank(message = "API Key는 필수입니다.")
    String apiKey,

    @NotBlank(message = "캐릭터 닉네임은 필수입니다.")
    String userIgn
) {

    /**
     * API Key 마스킹된 문자열 반환 (로그 보안)
     *
     * <p>TraceAspect 등에서 자동 로깅 시 API Key 노출 방지</p>
     */
    @Override
    public String toString() {
        return "LoginRequest[" +
                "apiKey=" + maskApiKey(apiKey) +
                ", userIgn=" + userIgn +
                "]";
    }

    /**
     * API Key 마스킹 (앞 4자리 + **** + 뒤 4자리)
     */
    private String maskApiKey(String key) {
        if (key == null || key.length() < 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
