package maple.expectation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import maple.expectation.controller.dto.admin.AddAdminRequest;
import maple.expectation.global.response.ApiResponse;
import maple.expectation.global.security.AuthenticatedUser;
import maple.expectation.service.v2.auth.AdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * AdminController 단위 테스트
 *
 * <p>Spring Context 없이 MockMvc로 핵심 로직을 검증합니다.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminController 단위 테스트")
class AdminControllerUnitTest {

  @Mock private AdminService adminService;

  private MockMvc mockMvc;
  private ObjectMapper objectMapper;
  private AdminController adminController;

  // 정상적인 64자 hex fingerprint
  private static final String VALID_FINGERPRINT_64 =
      "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

  // 63자 fingerprint (경계값 - 너무 짧음)
  private static final String FINGERPRINT_63 =
      "abcdef0123456789abcdef0123456789abcdef0123456789abcdef012345678";

  // 65자 fingerprint (경계값 - 너무 김)
  private static final String FINGERPRINT_65 =
      "abcdef0123456789abcdef0123456789abcdef0123456789abcdef01234567890";

  @BeforeEach
  void setUp() {
    adminController = new AdminController(adminService);
    objectMapper = new ObjectMapper();

    mockMvc = MockMvcBuilders.standaloneSetup(adminController).build();
  }

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
      mockMvc
          .perform(
              post("/api/admin/admins")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("TC-151-02: null fingerprint → 400 + C001")
    void addAdmin_nullFingerprint_returns400() throws Exception {
      // Given
      setupAdminAuthentication();
      String jsonWithNull = "{\"fingerprint\":null}";

      // When & Then
      mockMvc
          .perform(
              post("/api/admin/admins")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(jsonWithNull))
          .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("TC-151-03: 63자 fingerprint (경계값) → 400 + C001")
    void addAdmin_63CharFingerprint_returns400() throws Exception {
      // Given
      setupAdminAuthentication();
      AddAdminRequest request = new AddAdminRequest(FINGERPRINT_63);

      // When & Then
      mockMvc
          .perform(
              post("/api/admin/admins")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("TC-151-04: 65자 fingerprint (경계값) → 400 + C001")
    void addAdmin_65CharFingerprint_returns400() throws Exception {
      // Given
      setupAdminAuthentication();
      AddAdminRequest request = new AddAdminRequest(FINGERPRINT_65);

      // When & Then
      mockMvc
          .perform(
              post("/api/admin/admins")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "'; DROP TABLE admins; --", // SQL Injection
          "<script>alert('xss')</script>", // XSS
          "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", // 비hex (64자)
          "GHIJKLMNOPQRSTUVWXYZ0123456789abcdef0123456789abcdef0123456789ab", // 비hex 대문자
          "   ", // 공백만
          "abcdef0123456789abcdef0123456789abcdef0123456789abcdef012345678X" // 마지막 비hex
        })
    @DisplayName("TC-151-05: SQL Injection/XSS/비hex 패턴 → 400 + C001")
    void addAdmin_invalidPatterns_returns400(String invalidFingerprint) throws Exception {
      // Given
      setupAdminAuthentication();
      AddAdminRequest request = new AddAdminRequest(invalidFingerprint);

      // When & Then
      mockMvc
          .perform(
              post("/api/admin/admins")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("TC-151-06: 정상 64자 hex fingerprint → 200")
    void addAdmin_validFingerprint_returns200() throws Exception {
      // Given
      AuthenticatedUser currentUser = setupAdminAuthentication();
      AddAdminRequest request = new AddAdminRequest(VALID_FINGERPRINT_64);

      // When & Then - For async controllers, call controller directly
      CompletableFuture<ResponseEntity<ApiResponse<String>>> future =
          adminController.addAdmin(request, currentUser);

      ResponseEntity<ApiResponse<String>> response = future.join();

      // Then
      assertThat(response.getStatusCode().value()).isEqualTo(200);
      assertThat(response.getBody().success()).isTrue();
    }
  }

  @Nested
  @DisplayName("removeAdmin() 검증 테스트")
  class RemoveAdminTests {

    @Test
    @DisplayName("TC-151-08: 자기 자신 삭제 시도 → 400 + SELF_REMOVAL_NOT_ALLOWED")
    void removeAdmin_selfRemoval_returns400() {
      // Given: 현재 사용자의 fingerprint로 삭제 시도
      String currentUserFingerprint = VALID_FINGERPRINT_64;
      AuthenticatedUser currentUser = createUser(currentUserFingerprint);

      // When
      ResponseEntity<ApiResponse<String>> response =
          adminController.removeAdmin(currentUserFingerprint, currentUser).join();

      // Then: 자기 자신이므로 400 Bad Request
      assertThat(response.getStatusCode().value()).isEqualTo(400);
      assertThat(response.getBody().success()).isFalse();
      assertThat(response.getBody().error().code()).contains("SELF_REMOVAL_NOT_ALLOWED");
    }
  }

  @Nested
  @DisplayName("getAdmins() 테스트")
  class GetAdminsTests {

    @Test
    @DisplayName("Admin 목록 조회 성공")
    void getAdmins_returns200() {
      // Given
      setupAdminAuthentication();
      given(adminService.getAllAdmins()).willReturn(Set.of(VALID_FINGERPRINT_64));

      // When
      var result = adminController.getAdmins().join();

      // Then
      assertThat(result.getBody().data()).isNotNull();
      assertThat(result.getBody().success()).isTrue();
      verify(adminService).getAllAdmins();
    }
  }

  // ==================== Helper Methods ====================

  /** ADMIN 권한으로 SecurityContext 설정 (기본 fingerprint) */
  private AuthenticatedUser setupAdminAuthentication() {
    return setupAdminAuthentication("default-admin-fingerprint-for-testing-1234567890abcdef1234");
  }

  /** ADMIN 권한으로 SecurityContext 설정 (지정된 fingerprint) */
  private AuthenticatedUser setupAdminAuthentication(String fingerprint) {
    AuthenticatedUser user =
        new AuthenticatedUser(
            "test-session-id", // sessionId
            fingerprint, // fingerprint
            "AdminUser", // userIgn
            "test-account-id", // accountId
            "test-api-key", // apiKey
            Collections.emptySet(), // myOcids
            "ADMIN" // role
            );

    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            user, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    SecurityContextHolder.getContext().setAuthentication(auth);
    return user;
  }

  /** 테스트용 AuthenticatedUser 생성 (SecurityContext 설정 없음) */
  private AuthenticatedUser createUser(String fingerprint) {
    return new AuthenticatedUser(
        "test-session-id", // sessionId
        fingerprint, // fingerprint
        "TestUser", // userIgn
        "test-account-id", // accountId
        "test-api-key", // apiKey
        Collections.emptySet(), // myOcids
        "ADMIN" // role
        );
  }
}
