package maple.expectation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.service.v2.EquipmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * GameCharacterControllerV3 단위 테스트 (Issue #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>순수 단위 테스트로 Controller 메서드만 직접 테스트합니다.
 *
 * <h4>테스트 범위</h4>
 *
 * <ul>
 *   <li>GET /{userIgn}/equipment - Zero-Copy GZIP 스트리밍
 *   <li>GET /{userIgn}/expectation - 비동기 기대값 조회
 * </ul>
 */
@Tag("unit")
class GameCharacterControllerV3Test {

  private EquipmentService equipmentService;
  private GameCharacterControllerV3 controller;

  @BeforeEach
  void setUp() {
    equipmentService = mock(EquipmentService.class);
    controller = new GameCharacterControllerV3(equipmentService);
  }

  @Nested
  @DisplayName("장비 스트리밍 getEquipmentStream")
  class GetEquipmentStreamTest {

    @Test
    @DisplayName("스트리밍 응답 성공 - Zero-Copy GZIP")
    void whenStreamingEquipment_shouldReturnGzipResponse() throws Exception {
      // given
      String userIgn = "StreamUser";
      byte[] mockGzipData = new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00}; // GZIP magic bytes

      doAnswer(
              invocation -> {
                OutputStream os = invocation.getArgument(1);
                os.write(mockGzipData);
                return null;
              })
          .when(equipmentService)
          .streamEquipmentDataRaw(eq(userIgn), any(OutputStream.class));

      // when
      ResponseEntity<StreamingResponseBody> response = controller.getEquipmentStream(userIgn);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
      assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
      assertThat(response.getBody()).isNotNull();
    }

    @Test
    @DisplayName("StreamingResponseBody가 올바르게 데이터 전송")
    void whenStreamingBody_shouldWriteToOutputStream() throws Exception {
      // given
      String userIgn = "DataUser";
      byte[] expectedData = "test-gzip-data".getBytes();

      doAnswer(
              invocation -> {
                OutputStream os = invocation.getArgument(1);
                os.write(expectedData);
                return null;
              })
          .when(equipmentService)
          .streamEquipmentDataRaw(eq(userIgn), any(OutputStream.class));

      // when
      ResponseEntity<StreamingResponseBody> response = controller.getEquipmentStream(userIgn);
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      response.getBody().writeTo(outputStream);

      // then
      assertThat(outputStream.toByteArray()).isEqualTo(expectedData);
      verify(equipmentService).streamEquipmentDataRaw(eq(userIgn), any(OutputStream.class));
    }

    @Test
    @DisplayName("Content-Encoding 헤더가 gzip으로 설정")
    void shouldSetGzipContentEncodingHeader() {
      // given
      String userIgn = "HeaderUser";

      // when
      ResponseEntity<StreamingResponseBody> response = controller.getEquipmentStream(userIgn);

      // then
      assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
    }

    @Test
    @DisplayName("Content-Type 헤더가 application/json으로 설정")
    void shouldSetJsonContentTypeHeader() {
      // given
      String userIgn = "ContentTypeUser";

      // when
      ResponseEntity<StreamingResponseBody> response = controller.getEquipmentStream(userIgn);

      // then
      assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }
  }

  @Nested
  @DisplayName("기대값 조회 getEquipmentExpectation")
  class GetEquipmentExpectationTest {

    @Test
    @DisplayName("비동기 기대값 조회 성공")
    void whenCalculation_shouldReturnExpectation() throws Exception {
      // given
      String userIgn = "ExpectUser";
      TotalExpectationResponse mockResponse =
          TotalExpectationResponse.builder().totalCost(5000000L).build();
      given(equipmentService.calculateTotalExpectationAsync(userIgn))
          .willReturn(CompletableFuture.completedFuture(mockResponse));

      // when
      CompletableFuture<ResponseEntity<TotalExpectationResponse>> future =
          controller.getEquipmentExpectation(userIgn);
      ResponseEntity<TotalExpectationResponse> response = future.join();

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().getTotalCost()).isEqualTo(5000000L);
    }

    @Test
    @DisplayName("CompletableFuture 반환으로 톰캣 스레드 즉시 반환")
    void shouldReturnCompletableFutureImmediately() {
      // given
      String userIgn = "AsyncUser";
      CompletableFuture<TotalExpectationResponse> pendingFuture = new CompletableFuture<>();
      given(equipmentService.calculateTotalExpectationAsync(userIgn)).willReturn(pendingFuture);

      // when
      CompletableFuture<ResponseEntity<TotalExpectationResponse>> result =
          controller.getEquipmentExpectation(userIgn);

      // then - Future가 완료되지 않아도 즉시 반환
      assertThat(result).isNotNull();
      assertThat(result.isDone()).isFalse();

      // cleanup
      pendingFuture.complete(TotalExpectationResponse.builder().build());
    }

    @Test
    @DisplayName("EquipmentService 호출 검증")
    void shouldCallEquipmentService() {
      // given
      String userIgn = "ServiceCallUser";
      given(equipmentService.calculateTotalExpectationAsync(userIgn))
          .willReturn(
              CompletableFuture.completedFuture(TotalExpectationResponse.builder().build()));

      // when
      controller.getEquipmentExpectation(userIgn);

      // then
      verify(equipmentService, times(1)).calculateTotalExpectationAsync(userIgn);
    }
  }
}
