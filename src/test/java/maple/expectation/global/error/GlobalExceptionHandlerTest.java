package maple.expectation.global.error;

import maple.expectation.global.error.exception.ApiTimeoutException;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ✅ GlobalExceptionHandler 스프링 wiring 검증
 * - @ExceptionHandler 라우팅이 실제로 작동하는지만 검증
 * - Facade를 Mock으로 두고 예외만 던져서 핸들러 동작 확인
 * - @SpringBootTest 사용으로 복잡한 의존성 해결
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private GameCharacterFacade gameCharacterFacade;

    @Test
    @DisplayName("CharacterNotFoundException → 404 NOT_FOUND + C002 (스프링 wiring 검증)")
    void handleCharacterNotFoundException_SpringWiring() throws Exception {
        String nonExistIgn = "유령캐릭터";

        given(gameCharacterFacade.findCharacterByUserIgn(nonExistIgn))
                .willThrow(new CharacterNotFoundException(nonExistIgn));

        mockMvc.perform(get("/api/v1/characters/" + nonExistIgn))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("C002"));
    }

    @Test
    @DisplayName("RuntimeException → 500 INTERNAL_SERVER_ERROR + S001 (스프링 wiring 검증)")
    void handleUnexpectedException_SpringWiring() throws Exception {
        given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
                .willThrow(new RuntimeException("알 수 없는 서버 오류"));

        mockMvc.perform(get("/api/v1/characters/anyIgn"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("S001"));
    }

    // ==================== Issue #168: CompletionException 처리 테스트 ====================

    @Test
    @DisplayName("CompletionException(RejectedExecutionException) → 503 + Retry-After 60s (Issue #168)")
    void handleCompletionException_RejectedExecution_Returns503WithRetryAfter() throws Exception {
        // Given: RejectedExecutionException을 감싼 CompletionException
        given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
                .willThrow(new CompletionException(
                        new RejectedExecutionException("ExpectationExecutor queue full")));

        // When & Then: 503 + Retry-After 헤더 + S007 코드
        mockMvc.perform(get("/api/v1/characters/anyIgn"))
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
                .willThrow(new CompletionException(
                        new TimeoutException("Async operation timeout")));

        // When & Then: 503 + Retry-After 30초 헤더
        mockMvc.perform(get("/api/v1/characters/anyIgn"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("S007"));
    }

    @Test
    @DisplayName("CompletionException(CharacterNotFoundException) → 404 (비즈니스 예외 위임)")
    void handleCompletionException_BusinessException_DelegatesHandler() throws Exception {
        // Given: BaseException을 감싼 CompletionException
        given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
                .willThrow(new CompletionException(
                        new CharacterNotFoundException("유령캐릭터")));

        // When & Then: 비즈니스 예외 핸들러로 위임 → 404
        mockMvc.perform(get("/api/v1/characters/anyIgn"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("C002"));
    }

    @Test
    @DisplayName("CompletionException(RuntimeException) → 500 (일반 시스템 예외)")
    void handleCompletionException_RuntimeException_Returns500() throws Exception {
        // Given: 일반 RuntimeException을 감싼 CompletionException
        given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
                .willThrow(new CompletionException(
                        new RuntimeException("Unknown system error")));

        // When & Then: 500 Internal Server Error
        mockMvc.perform(get("/api/v1/characters/anyIgn"))
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
        mockMvc.perform(get("/api/v1/characters/anyIgn"))
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
                .willThrow(new ApiTimeoutException("NexonEquipmentAPI",
                        new java.util.concurrent.TimeoutException("Connection timeout")));

        // When & Then: 503 + Retry-After 30초 헤더 + S010 코드
        mockMvc.perform(get("/api/v1/characters/anyIgn"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("S010"));
    }
}
