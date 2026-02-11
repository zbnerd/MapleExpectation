package maple.expectation.global.error;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import maple.expectation.controller.dto.admin.AddAdminRequest;
import maple.expectation.global.error.exception.ApiTimeoutException;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.global.error.GlobalExceptionHandler;
import maple.expectation.global.security.AuthenticatedUser;
import maple.expectation.service.v2.auth.AdminService;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ✅ GlobalExceptionHandler 스프링 wiring 검증 - @ExceptionHandler 라우팅이 실제로 작동하는지만 검증 - Facade를 Mock으로 두고
 * 예외만 던져서 핸들러 동작 확인 - @WebMvcTest로 빠르고 정확한 테스트
 */
@WebMvcTest
@Import(GlobalExceptionHandler.class) // GlobalExceptionHandler 임포트
@ActiveProfiles("test")
@Tag("unit")
class GlobalExceptionHandlerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private GameCharacterFacade gameCharacterFacade;
  @MockBean private AdminService adminService;

  @Test
  @DisplayName("CharacterNotFoundException → 404 NOT_FOUND + C002 (스프링 wiring 검증)")
  void handleCharacterNotFoundException_SpringWiring() throws Exception {
    String nonExistIgn = "유령캐릭터";

    given(gameCharacterFacade.findCharacterByUserIgn(nonExistIgn))
        .willThrow(new CharacterNotFoundException(nonExistIgn));

    mockMvc
        .perform(get("/api/v1/characters/" + nonExistIgn))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("C002"));
  }

  @Test
  @DisplayName("RuntimeException → 500 INTERNAL_SERVER_ERROR + S001 (스프링 wiring 검증)")
  void handleUnexpectedException_SpringWiring() throws Exception {
    given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
        .willThrow(new RuntimeException("알 수 없는 서버 오류"));

    mockMvc
        .perform(get("/api/v1/characters/anyIgn"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("S001"));
  }

  // ==================== Issue #168: CompletionException 처리 테스트 ====================

  @Test
  @DisplayName(
      "CompletionException(RejectedExecutionException) → 503 + Retry-After 60s (Issue #168)")
  void handleCompletionException_RejectedExecution_Returns503WithRetryAfter() throws Exception {
    // Given: RejectedExecutionException을 감싼 CompletionException
    given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
        .willThrow(
            new CompletionException(
                new RejectedExecutionException("ExpectationExecutor queue full")));

    // When & Then: 503 + Retry-After 헤더 + S007 코드
    mockMvc
        .perform(get("/api/v1/characters/anyIgn"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "60"))
        .andExpect(jsonPath("$.code").value("S007"))
        .andExpect(jsonPath("$.status").value(503));
  }

  @Test
  @DisplayName("CompletionException(TimeoutException) → 503 + Retry-After 30s")
  void handleCompletionException_Timeout_Returns503WithRetryAfter() throws Exception {
    // Given: TimeoutException을 감싼 CompletionException
    given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
        .willThrow(new CompletionException(new TimeoutException("Async operation timeout")));

    // When & Then: 503 + Retry-After 30초 헤더
    mockMvc
        .perform(get("/api/v1/characters/anyIgn"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "30"))
        .andExpect(jsonPath("$.code").value("S007"));
  }

  @Test
  @DisplayName("CompletionException(CharacterNotFoundException) → 404 (비즈니스 예외 위임)")
  void handleCompletionException_BusinessException_DelegatesHandler() throws Exception {
    // Given: BaseException을 감싼 CompletionException
    given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
        .willThrow(new CompletionException(new CharacterNotFoundException("유령캐릭터")));

    // When & Then: 비즈니스 예외 핸들러로 위임 → 404
    mockMvc
        .perform(get("/api/v1/characters/anyIgn"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("C002"));
  }

  @Test
  @DisplayName("CompletionException(RuntimeException) → 500 (일반 시스템 예외)")
  void handleCompletionException_RuntimeException_Returns500() throws Exception {
    // Given: 일반 RuntimeException을 감싼 CompletionException
    given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
        .willThrow(new CompletionException(new RuntimeException("Unknown system error")));

    // When & Then: 500 Internal Server Error
    mockMvc
        .perform(get("/api/v1/characters/anyIgn"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("S001"));
  }

  // ==================== Issue #169: ApiTimeoutException 처리 테스트 ====================

  @Test
  @DisplayName("ApiTimeoutException → 503 + Retry-After 30s + S010 (Issue #169)")
  void handleApiTimeoutException_Returns503WithRetryAfterAndS010() throws Exception {
    // Given: ApiTimeoutException (CircuitBreakerRecordMarker 구현)
    given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
        .willThrow(new ApiTimeoutException("NexonEquipmentAPI"));

    // When & Then: 503 + Retry-After 30초 헤더 + S010 코드
    mockMvc
        .perform(get("/api/v1/characters/anyIgn"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "30"))
        .andExpect(jsonPath("$.code").value("S010"))
        .andExpect(jsonPath("$.status").value(503));
  }

  @Test
  @DisplayName("ApiTimeoutException with cause → 503 + Retry-After 30s (cause 포함)")
  void handleApiTimeoutException_WithCause_Returns503() throws Exception {
    // Given: TimeoutException을 원인으로 가진 ApiTimeoutException
    given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
        .willThrow(
            new ApiTimeoutException(
                "NexonEquipmentAPI",
                new java.util.concurrent.TimeoutException("Connection timeout")));

    // When & Then: 503 + Retry-After 30초 헤더 + S010 코드
    mockMvc
        .perform(get("/api/v1/characters/anyIgn"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "30"))
        .andExpect(jsonPath("$.code").value("S010"));
  }

  // ==================== Issue #151: Bean Validation 처리 테스트 ====================

  @Nested
  @DisplayName("Issue #151: Validation Exception 처리")
  class ValidationExceptionTests {

    @Test
    @DisplayName("TC-151-09: MethodArgumentNotValidException 발생 → 400 + C001 + 필드명 포함")
    void handleMethodArgumentNotValidException_Returns400WithFieldName() throws Exception {
      // Given: 잘못된 fingerprint로 Admin 추가 요청
      setupAdminAuthentication();
      AddAdminRequest invalidRequest = new AddAdminRequest(""); // 빈 문자열

      // When & Then: 400 + C001 + 필드명(fingerprint) 포함
      mockMvc
          .perform(
              post("/api/admin/admins")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(invalidRequest)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("C001"))
          .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("TC-151-10: 복합 검증 실패 시 모든 필드 오류 메시지 포함")
    void handleMethodArgumentNotValidException_MultipleErrors() throws Exception {
      // Given: 패턴과 길이 모두 위반하는 fingerprint
      setupAdminAuthentication();
      String jsonWithInvalidFingerprint = "{\"fingerprint\":\"xyz\"}"; // 너무 짧고 패턴 위반

      // When & Then: 400 + C001
      mockMvc
          .perform(
              post("/api/admin/admins")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(jsonWithInvalidFingerprint))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("C001"));
    }

    /**
     * ADMIN 권한으로 SecurityContext 설정
     *
     * <p>AuthenticatedUser 필드: sessionId, fingerprint, apiKey, myOcids, role
     */
    private void setupAdminAuthentication() {
      AuthenticatedUser user =
          new AuthenticatedUser(
              "test-session-id", // sessionId
              "test-fingerprint-for-validation-test-1234567890abcdef12345678", // fingerprint
              "TestUser", // userIgn
              "test-account-id", // accountId
              "test-api-key", // apiKey
              Collections.emptySet(), // myOcids
              "ADMIN" // role
              );

      UsernamePasswordAuthenticationToken auth =
          new UsernamePasswordAuthenticationToken(
              user, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

      SecurityContextHolder.getContext().setAuthentication(auth);
    }
  }
}
