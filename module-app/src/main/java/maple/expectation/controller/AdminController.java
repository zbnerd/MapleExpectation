package maple.expectation.controller;

import jakarta.validation.Valid;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import maple.expectation.controller.dto.admin.AddAdminRequest;
import maple.expectation.infrastructure.security.AuthenticatedUser;
import maple.expectation.response.ApiResponse;
import maple.expectation.service.v2.auth.AdminService;
import maple.expectation.util.StringMaskingUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 관리 API
 *
 * <p>권한: ADMIN만 접근 가능 (SecurityConfig에서 설정)
 *
 * <h4>Issue #151: Bean Validation 적용</h4>
 *
 * <ul>
 *   <li>@Validated: 클래스 레벨 검증 활성화 (@PathVariable 검증)
 *   <li>@Valid: @RequestBody DTO 검증
 *   <li>AddAdminRequest: 별도 파일로 분리 (SRP 준수)
 * </ul>
 *
 * <p>엔드포인트:
 *
 * <ul>
 *   <li>GET /api/admin/admins - Admin 목록 조회
 *   <li>POST /api/admin/admins - 새 Admin 추가
 *   <li>DELETE /api/admin/admins/{fingerprint} - Admin 제거
 * </ul>
 */
@Validated // ✅ Issue #151: @PathVariable 검증 활성화
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

  private final AdminService adminService;

  /** 전체 Admin 목록 조회 */
  @GetMapping("/admins")
  public CompletableFuture<ResponseEntity<ApiResponse<Set<String>>>> getAdmins() {
    return CompletableFuture.supplyAsync(
        () -> {
          Set<String> admins = adminService.getAllAdmins();
          return ResponseEntity.ok(ApiResponse.success(admins));
        });
  }

  /**
   * 새 Admin 추가
   *
   * <h4>Issue #151: @Valid 적용</h4>
   *
   * <p>AddAdminRequest에 @NotBlank, @Size, @Pattern 검증 적용
   *
   * @param request fingerprint가 담긴 요청 (검증됨)
   */
  @PostMapping("/admins")
  public CompletableFuture<ResponseEntity<ApiResponse<String>>> addAdmin(
      @Valid @RequestBody AddAdminRequest request, // ✅ @Valid 추가
      @AuthenticationPrincipal AuthenticatedUser currentUser) {

    return CompletableFuture.supplyAsync(
        () -> {
          adminService.addAdmin(request.fingerprint());

          return ResponseEntity.ok(
              ApiResponse.success(
                  "Admin added successfully: " + request.maskedFingerprint() // DTO의 마스킹 메서드 사용
                  ));
        });
  }

  /**
   * Admin 제거
   *
   * @param fingerprint 제거할 Admin의 fingerprint
   */
  @DeleteMapping("/admins/{fingerprint}")
  public CompletableFuture<ResponseEntity<ApiResponse<String>>> removeAdmin(
      @PathVariable String fingerprint, @AuthenticationPrincipal AuthenticatedUser currentUser) {

    return CompletableFuture.supplyAsync(
        () -> {
          // 자기 자신은 제거 불가
          if (fingerprint.equals(currentUser.fingerprint())) {
            return ResponseEntity.badRequest()
                .body(
                    ApiResponse.error("SELF_REMOVAL_NOT_ALLOWED", "자기 자신의 Admin 권한은 제거할 수 없습니다."));
          }

          boolean removed = adminService.removeAdmin(fingerprint);

          if (!removed) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("BOOTSTRAP_ADMIN", "Bootstrap Admin은 제거할 수 없습니다."));
          }

          return ResponseEntity.ok(
              ApiResponse.success(
                  "Admin removed successfully: "
                      + StringMaskingUtils.maskFingerprintWithSuffix(fingerprint)));
        });
  }
}
