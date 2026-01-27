package maple.expectation.controller;

import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.service.v4.EquipmentExpectationServiceV4;
import maple.expectation.service.v4.warmup.PopularCharacterTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * GameCharacterControllerV4 단위 테스트 (Issue #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 * <p>순수 단위 테스트로 Controller 메서드만 직접 테스트합니다.</p>
 *
 * <h4>테스트 범위</h4>
 * <ul>
 *   <li>GET /{userIgn}/expectation - 전체 기대값 조회 (GZIP/JSON)</li>
 *   <li>GET /{userIgn}/expectation/preset/{presetNo} - 프리셋별 조회</li>
 *   <li>POST /{userIgn}/expectation/recalculate - 재계산</li>
 *   <li>L1 Fast Path 최적화 (#264)</li>
 *   <li>Auto Warmup 호출 기록 (#275)</li>
 * </ul>
 */
@Tag("unit")
class GameCharacterControllerV4Test {

    private EquipmentExpectationServiceV4 expectationService;
    private PopularCharacterTracker popularCharacterTracker;
    private GameCharacterControllerV4 controller;

    @BeforeEach
    void setUp() {
        expectationService = mock(EquipmentExpectationServiceV4.class);
        popularCharacterTracker = mock(PopularCharacterTracker.class);
        controller = new GameCharacterControllerV4(expectationService, popularCharacterTracker);
    }

    @Nested
    @DisplayName("전체 기대값 조회 getExpectation")
    class GetExpectationTest {

        @Test
        @DisplayName("GZIP 요청 + L1 캐시 히트 시 Fast Path 반환")
        void whenGzipAndL1Hit_shouldReturnFastPath() throws Exception {
            // given
            String userIgn = "FastUser";
            byte[] cachedGzipData = new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00};
            given(expectationService.getGzipFromL1CacheDirect(userIgn))
                    .willReturn(Optional.of(cachedGzipData));

            // when
            CompletableFuture<ResponseEntity<?>> future =
                    controller.getExpectation(userIgn, false, "gzip");
            ResponseEntity<?> response = future.join();

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
            assertThat(response.getBody()).isEqualTo(cachedGzipData);
            verify(popularCharacterTracker).recordAccess(userIgn);
        }

        @Test
        @DisplayName("GZIP 요청 + L1 캐시 미스 시 비동기 경로로 Fallback")
        void whenGzipAndL1Miss_shouldFallbackToAsync() throws Exception {
            // given
            String userIgn = "FallbackUser";
            byte[] gzipData = new byte[]{0x1f, (byte) 0x8b};
            given(expectationService.getGzipFromL1CacheDirect(userIgn))
                    .willReturn(Optional.empty());
            given(expectationService.getGzipExpectationAsync(eq(userIgn), eq(false)))
                    .willReturn(CompletableFuture.completedFuture(gzipData));

            // when
            CompletableFuture<ResponseEntity<?>> future =
                    controller.getExpectation(userIgn, false, "gzip, deflate");
            ResponseEntity<?> response = future.join();

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
            verify(expectationService).getGzipExpectationAsync(userIgn, false);
        }

        @Test
        @DisplayName("force=true 시 Fast Path 스킵")
        void whenForceTrue_shouldSkipFastPath() throws Exception {
            // given
            String userIgn = "ForceUser";
            byte[] gzipData = new byte[]{0x1f, (byte) 0x8b};
            given(expectationService.getGzipExpectationAsync(eq(userIgn), eq(true)))
                    .willReturn(CompletableFuture.completedFuture(gzipData));

            // when
            CompletableFuture<ResponseEntity<?>> future =
                    controller.getExpectation(userIgn, true, "gzip");
            future.join();

            // then - L1 캐시 조회하지 않음
            verify(expectationService, never()).getGzipFromL1CacheDirect(anyString());
            verify(expectationService).getGzipExpectationAsync(userIgn, true);
        }

        @Test
        @DisplayName("JSON 요청 시 JSON 응답 반환")
        void whenNoGzipHeader_shouldReturnJsonResponse() throws Exception {
            // given
            String userIgn = "JsonUser";
            EquipmentExpectationResponseV4 mockResponse = createMockResponse(userIgn);
            given(expectationService.calculateExpectationAsync(eq(userIgn), eq(false)))
                    .willReturn(CompletableFuture.completedFuture(mockResponse));

            // when
            CompletableFuture<ResponseEntity<?>> future =
                    controller.getExpectation(userIgn, false, null);
            ResponseEntity<?> response = future.join();

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isNull();
            assertThat(response.getBody()).isInstanceOf(EquipmentExpectationResponseV4.class);
        }

        @Test
        @DisplayName("PopularCharacterTracker에 접근 기록")
        void shouldRecordAccessToTracker() {
            // given
            String userIgn = "TrackedUser";
            given(expectationService.calculateExpectationAsync(anyString(), anyBoolean()))
                    .willReturn(CompletableFuture.completedFuture(createMockResponse(userIgn)));

            // when
            controller.getExpectation(userIgn, false, null);

            // then
            verify(popularCharacterTracker, times(1)).recordAccess(userIgn);
        }
    }

    @Nested
    @DisplayName("프리셋별 기대값 조회 getExpectationByPreset")
    class GetExpectationByPresetTest {

        @Test
        @DisplayName("특정 프리셋 조회 성공")
        void whenPresetExists_shouldReturnFilteredResponse() throws Exception {
            // given
            String userIgn = "PresetUser";
            Integer presetNo = 1;
            EquipmentExpectationResponseV4 fullResponse = createMockResponseWithPresets(userIgn);
            given(expectationService.calculateExpectationAsync(userIgn))
                    .willReturn(CompletableFuture.completedFuture(fullResponse));

            // when
            CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> future =
                    controller.getExpectationByPreset(userIgn, presetNo);
            ResponseEntity<EquipmentExpectationResponseV4> response = future.join();

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getPresets())
                    .allMatch(p -> p.getPresetNo() == presetNo);
        }

        @Test
        @DisplayName("존재하지 않는 프리셋 조회 시 빈 프리셋 리스트 반환")
        void whenPresetNotExists_shouldReturnEmptyPresets() throws Exception {
            // given
            String userIgn = "NoPresetUser";
            Integer presetNo = 99; // 존재하지 않는 프리셋
            EquipmentExpectationResponseV4 fullResponse = createMockResponseWithPresets(userIgn);
            given(expectationService.calculateExpectationAsync(userIgn))
                    .willReturn(CompletableFuture.completedFuture(fullResponse));

            // when
            CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> future =
                    controller.getExpectationByPreset(userIgn, presetNo);
            ResponseEntity<EquipmentExpectationResponseV4> response = future.join();

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getPresets()).isEmpty();
            assertThat(response.getBody().getTotalExpectedCost()).isEqualTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("기대값 재계산 recalculateExpectation")
    class RecalculateExpectationTest {

        @Test
        @DisplayName("재계산 성공")
        void whenRecalculate_shouldReturnNewResult() throws Exception {
            // given
            String userIgn = "RecalcUser";
            EquipmentExpectationResponseV4 mockResponse = createMockResponse(userIgn);
            given(expectationService.calculateExpectationAsync(userIgn))
                    .willReturn(CompletableFuture.completedFuture(mockResponse));

            // when
            CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> future =
                    controller.recalculateExpectation(userIgn);
            ResponseEntity<EquipmentExpectationResponseV4> response = future.join();

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            verify(expectationService).calculateExpectationAsync(userIgn);
        }
    }

    @Nested
    @DisplayName("GZIP 응답 빌드")
    class GzipResponseBuildTest {

        @Test
        @DisplayName("GZIP 응답에 올바른 헤더 설정")
        void whenGzipResponse_shouldSetCorrectHeaders() throws Exception {
            // given
            String userIgn = "GzipHeaderUser";
            byte[] gzipData = new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00, 0x01, 0x02};
            given(expectationService.getGzipFromL1CacheDirect(userIgn))
                    .willReturn(Optional.of(gzipData));

            // when
            CompletableFuture<ResponseEntity<?>> future =
                    controller.getExpectation(userIgn, false, "gzip");
            ResponseEntity<?> response = future.join();

            // then
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(response.getHeaders().getContentLength()).isEqualTo(gzipData.length);
        }
    }

    // ==================== Helper Methods ====================

    private EquipmentExpectationResponseV4 createMockResponse(String userIgn) {
        return EquipmentExpectationResponseV4.builder()
                .userIgn(userIgn)
                .calculatedAt(LocalDateTime.now())
                .fromCache(false)
                .totalExpectedCost(BigDecimal.valueOf(10000000))
                .totalCostBreakdown(EquipmentExpectationResponseV4.CostBreakdownDto.empty())
                .presets(List.of())
                .build();
    }

    private EquipmentExpectationResponseV4 createMockResponseWithPresets(String userIgn) {
        EquipmentExpectationResponseV4.PresetExpectation preset1 = EquipmentExpectationResponseV4.PresetExpectation.builder()
                .presetNo(1)
                .totalExpectedCost(BigDecimal.valueOf(5000000))
                .costBreakdown(EquipmentExpectationResponseV4.CostBreakdownDto.empty())
                .items(List.of())
                .build();

        EquipmentExpectationResponseV4.PresetExpectation preset2 = EquipmentExpectationResponseV4.PresetExpectation.builder()
                .presetNo(2)
                .totalExpectedCost(BigDecimal.valueOf(3000000))
                .costBreakdown(EquipmentExpectationResponseV4.CostBreakdownDto.empty())
                .items(List.of())
                .build();

        return EquipmentExpectationResponseV4.builder()
                .userIgn(userIgn)
                .calculatedAt(LocalDateTime.now())
                .fromCache(false)
                .totalExpectedCost(BigDecimal.valueOf(8000000))
                .totalCostBreakdown(EquipmentExpectationResponseV4.CostBreakdownDto.empty())
                .presets(List.of(preset1, preset2))
                .build();
    }
}
