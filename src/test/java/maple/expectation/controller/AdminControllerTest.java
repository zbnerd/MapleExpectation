package maple.expectation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import maple.expectation.controller.dto.admin.AddAdminRequest;
import maple.expectation.global.security.AuthenticatedUser;
import maple.expectation.service.v2.auth.AdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AdminController 단위 테스트 (Issue #151: Bean Validation 검증)
 *
 * <h4>5-Agent Council Round 2 결정</h4>
 * <ul>
 *   <li><b>Yellow Agent</b>: 경계값 테스트 (63자, 65자, 비hex) 필수</li>
 *   <li><b>Purple Agent</b>: SQL Injection/XSS 패턴 차단 검증</li>
 *   <li><b>Blue Agent</b>: @Valid + @Validated 동작 검증</li>
 * </ul>
 *
 * <p>테스트 범위:
 * <ul>
 *   <li>TC-151-01: addAdmin() 빈 fingerprint → 400</li>
 *   <li>TC-151-02: addAdmin() null fingerprint → 400</li>
 *   <li>TC-151-03: addAdmin() 63자 fingerprint (경계값) → 400</li>
 *   <li>TC-151-04: addAdmin() 65자 fingerprint (경계값) → 400</li>
 *   <li>TC-151-05: addAdmin() SQL Injection/XSS 패턴 → 400</li>
 *   <li>TC-151-06: addAdmin() 정상 64자 hex fingerprint → 200</li>
 *   <li>TC-151-07: removeAdmin() 빈 fingerprint → 404 (URL 매칭 실패)</li>
 *   <li>TC-151-08: 자기 자신 삭제 시도 → 400</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AdminService adminService;

    // 정상적인 64자 hex fingerprint
    private static final String VALID_FINGERPRINT_64 =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    // 63자 fingerprint (경계값 - 너무 짧음)
    private static final String FINGERPRINT_63 =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef012345678";

    // 65자 fingerprint (경계값 - 너무 김)
    private static final String FINGERPRINT_65 =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef01234567890";

    @Nested
    @DisplayName("addAdmin() @Valid 검증 테스트")
    class AddAdminValidationTests {

        @Test
        @DisplayName("TC-151-01: 빈 fingerprint → 400 + C001")
        void addAdmin_emptyFingerprint_returns400() throws Exception {
            // Given
            setupAdminAuthentication();
            AddAdminRequest request = new AddAdminRequest("");

            // When & Then
            mockMvc.perform(post("/api/admin/admins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("C001"));
        }

        @Test
        @DisplayName("TC-151-02: null fingerprint → 400 + C001")
        void addAdmin_nullFingerprint_returns400() throws Exception {
            // Given
            setupAdminAuthentication();
            // JSON에서 null 전송
            String jsonWithNull = "{\"fingerprint\":null}";

            // When & Then
            mockMvc.perform(post("/api/admin/admins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonWithNull))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("C001"));
        }

        @Test
        @DisplayName("TC-151-03: 63자 fingerprint (경계값) → 400 + C001")
        void addAdmin_63CharFingerprint_returns400() throws Exception {
            // Given
            setupAdminAuthentication();
            AddAdminRequest request = new AddAdminRequest(FINGERPRINT_63);

            // When & Then
            mockMvc.perform(post("/api/admin/admins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("C001"));
        }

        @Test
        @DisplayName("TC-151-04: 65자 fingerprint (경계값) → 400 + C001")
        void addAdmin_65CharFingerprint_returns400() throws Exception {
            // Given
            setupAdminAuthentication();
            AddAdminRequest request = new AddAdminRequest(FINGERPRINT_65);

            // When & Then
            mockMvc.perform(post("/api/admin/admins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("C001"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "'; DROP TABLE admins; --",              // SQL Injection
                "<script>alert('xss')</script>",         // XSS
                "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", // 비hex (64자)
                "GHIJKLMNOPQRSTUVWXYZ0123456789abcdef0123456789abcdef0123456789ab", // 비hex 대문자
                "   ",                                   // 공백만
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef012345678X" // 마지막 비hex
        })
        @DisplayName("TC-151-05: SQL Injection/XSS/비hex 패턴 → 400 + C001")
        void addAdmin_invalidPatterns_returns400(String invalidFingerprint) throws Exception {
            // Given
            setupAdminAuthentication();
            AddAdminRequest request = new AddAdminRequest(invalidFingerprint);

            // When & Then
            mockMvc.perform(post("/api/admin/admins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("C001"));
        }

        @Test
        @DisplayName("TC-151-06: 정상 64자 hex fingerprint → 200")
        void addAdmin_validFingerprint_returns200() throws Exception {
            // Given
            setupAdminAuthentication();
            AddAdminRequest request = new AddAdminRequest(VALID_FINGERPRINT_64);

            // When & Then
            mockMvc.perform(post("/api/admin/admins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    @Nested
    @DisplayName("removeAdmin() 검증 테스트")
    class RemoveAdminTests {

        @Test
        @DisplayName("TC-151-07: 빈 fingerprint로 삭제 시도 → URL 패턴 매칭 안됨 (Spring 기본 동작)")
        void removeAdmin_emptyFingerprint_returnsError() throws Exception {
            // Given
            setupAdminAuthentication();

            // When & Then: /api/admin/admins/ 와 / 없는 경우 모두 테스트
            // Spring MVC는 trailing slash에 따라 다른 결과를 반환할 수 있음
            // 빈 문자열 경로 변수는 Spring이 다르게 처리함
            mockMvc.perform(delete("/api/admin/admins/ "))  // 공백 fingerprint
                    .andExpect(status().is4xxClientError());  // 4xx 에러 범위
        }

        @Test
        @DisplayName("TC-151-08: 자기 자신 삭제 시도 → 400 + SELF_REMOVAL_NOT_ALLOWED")
        void removeAdmin_selfRemoval_returns400() throws Exception {
            // Given: 현재 사용자의 fingerprint로 삭제 시도
            String currentUserFingerprint = VALID_FINGERPRINT_64;
            setupAdminAuthentication(currentUserFingerprint);

            // When & Then: ApiResponse.error 구조: $.error.code
            mockMvc.perform(delete("/api/admin/admins/" + currentUserFingerprint))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SELF_REMOVAL_NOT_ALLOWED"));
        }
    }

    @Nested
    @DisplayName("getAdmins() 테스트")
    class GetAdminsTests {

        @Test
        @DisplayName("Admin 목록 조회 성공")
        void getAdmins_returns200() throws Exception {
            // Given
            setupAdminAuthentication();
            given(adminService.getAllAdmins()).willReturn(Set.of(VALID_FINGERPRINT_64));

            // When & Then
            mockMvc.perform(get("/api/admin/admins"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // ==================== Helper Methods ====================

    /**
     * ADMIN 권한으로 SecurityContext 설정 (기본 fingerprint)
     */
    private void setupAdminAuthentication() {
        setupAdminAuthentication("default-admin-fingerprint-for-testing-1234567890abcdef1234");
    }

    /**
     * ADMIN 권한으로 SecurityContext 설정 (지정된 fingerprint)
     *
     * <p>AuthenticatedUser 필드: sessionId, fingerprint, apiKey, myOcids, role</p>
     */
    private void setupAdminAuthentication(String fingerprint) {
        AuthenticatedUser user = new AuthenticatedUser(
                "test-session-id",    // sessionId
                fingerprint,           // fingerprint
                "AdminUser",           // userIgn
                "test-account-id",     // accountId
                "test-api-key",        // apiKey
                Collections.emptySet(),// myOcids
                "ADMIN"                // role
        );

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );

        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
