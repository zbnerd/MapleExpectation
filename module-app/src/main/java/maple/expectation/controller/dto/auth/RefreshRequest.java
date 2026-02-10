package maple.expectation.controller.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Token Refresh 요청 DTO (Issue #279)
 *
 * @param refreshToken Refresh Token ID
 */
public record RefreshRequest(@NotBlank(message = "refreshToken은 필수입니다.") String refreshToken) {}
