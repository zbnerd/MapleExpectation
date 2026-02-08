package maple.expectation.controller;

import jakarta.validation.Valid;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.controller.dto.auth.LoginRequest;
import maple.expectation.controller.dto.auth.LoginResponse;
import maple.expectation.controller.dto.auth.RefreshRequest;
import maple.expectation.controller.dto.auth.TokenResponse;
import maple.expectation.global.response.ApiResponse;
import maple.expectation.global.security.AuthenticatedUser;
import maple.expectation.service.v2.auth.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 인증 API 컨트롤러
 *
 * <p>API 목록:
 *
 * <ul>
 *   <li>POST /auth/login - 로그인 (JWT + Refresh Token 발급)
 *   <li>POST /auth/refresh - 토큰 갱신 (Issue #279)
 *   <li>DELETE /auth/logout - 로그아웃 (세션 + Refresh Token 삭제)
 *   <li>GET /auth/me - 현재 사용자 정보 조회
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  /**
   * 로그인 API
   *
   * @param request 로그인 요청 (apiKey, userIgn)
   * @return 로그인 응답 (accessToken, expiresIn, role, refreshToken, refreshExpiresIn)
   */
  @PostMapping("/login")
  public CompletableFuture<ResponseEntity<ApiResponse<LoginResponse>>> login(
      @Valid @RequestBody LoginRequest request) {

    return CompletableFuture.supplyAsync(
        () -> {
          LoginResponse response = authService.login(request);
          return ResponseEntity.ok(ApiResponse.success(response));
        });
  }

  /**
   * 토큰 갱신 API (Issue #279)
   *
   * <p>Token Rotation 패턴:
   *
   * <ul>
   *   <li>기존 Refresh Token 무효화
   *   <li>새 Access Token + Refresh Token 발급
   * </ul>
   *
   * @param request 갱신 요청 (refreshToken)
   * @return 새 토큰 응답 (accessToken, accessExpiresIn, refreshToken, refreshExpiresIn)
   */
  @PostMapping("/refresh")
  public ResponseEntity<ApiResponse<TokenResponse>> refresh(
      @Valid @RequestBody RefreshRequest request) {

    TokenResponse response = authService.refresh(request.refreshToken());

    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /**
   * 로그아웃 API
   *
   * @param user 인증된 사용자 정보
   */
  @DeleteMapping("/logout")
  public ResponseEntity<ApiResponse<Void>> logout(@AuthenticationPrincipal AuthenticatedUser user) {

    authService.logout(user.sessionId());

    return ResponseEntity.ok(ApiResponse.success(null));
  }

  /**
   * 현재 사용자 정보 조회 API
   *
   * @param user 인증된 사용자 정보
   * @return 사용자 정보 (apiKey 제외)
   */
  @GetMapping("/me")
  public ResponseEntity<ApiResponse<UserInfoResponse>> me(
      @AuthenticationPrincipal AuthenticatedUser user) {

    UserInfoResponse response =
        new UserInfoResponse(
            user.sessionId(), user.fingerprint(), user.role(), user.myOcids().size());

    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /** 현재 사용자 정보 응답 DTO (apiKey 제외) */
  public record UserInfoResponse(
      String sessionId, String fingerprint, String role, int characterCount) {}
}
