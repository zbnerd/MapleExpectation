package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import maple.expectation.global.response.ApiResponse;
import maple.expectation.global.security.AuthenticatedUser;
import maple.expectation.service.v2.auth.AdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * Admin 관리 API
 *
 * <p>권한: ADMIN만 접근 가능 (SecurityConfig에서 설정)</p>
 *
 * <p>엔드포인트:
 * <ul>
 *   <li>GET /api/admin/admins - Admin 목록 조회</li>
 *   <li>POST /api/admin/admins - 새 Admin 추가</li>
 *   <li>DELETE /api/admin/admins/{fingerprint} - Admin 제거</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    /**
     * 전체 Admin 목록 조회
     */
    @GetMapping("/admins")
    public ResponseEntity<ApiResponse<Set<String>>> getAdmins() {
        Set<String> admins = adminService.getAllAdmins();
        return ResponseEntity.ok(ApiResponse.success(admins));
    }

    /**
     * 새 Admin 추가
     *
     * @param request fingerprint가 담긴 요청
     */
    @PostMapping("/admins")
    public ResponseEntity<ApiResponse<String>> addAdmin(
            @RequestBody AddAdminRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser) {

        adminService.addAdmin(request.fingerprint());

        return ResponseEntity.ok(ApiResponse.success(
            "Admin added successfully: " + maskFingerprint(request.fingerprint())
        ));
    }

    /**
     * Admin 제거
     *
     * @param fingerprint 제거할 Admin의 fingerprint
     */
    @DeleteMapping("/admins/{fingerprint}")
    public ResponseEntity<ApiResponse<String>> removeAdmin(
            @PathVariable String fingerprint,
            @AuthenticationPrincipal AuthenticatedUser currentUser) {

        // 자기 자신은 제거 불가
        if (fingerprint.equals(currentUser.fingerprint())) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("SELF_REMOVAL_NOT_ALLOWED", "자기 자신의 Admin 권한은 제거할 수 없습니다."));
        }

        boolean removed = adminService.removeAdmin(fingerprint);

        if (!removed) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("BOOTSTRAP_ADMIN", "Bootstrap Admin은 제거할 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(
            "Admin removed successfully: " + maskFingerprint(fingerprint)
        ));
    }

    private String maskFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() < 8) {
            return "****";
        }
        return fingerprint.substring(0, 4) + "****" + fingerprint.substring(fingerprint.length() - 4);
    }

    /**
     * Admin 추가 요청 DTO
     */
    public record AddAdminRequest(String fingerprint) {}
}
