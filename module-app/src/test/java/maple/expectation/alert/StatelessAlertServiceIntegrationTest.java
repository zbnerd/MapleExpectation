package maple.expectation.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import maple.expectation.alert.channel.AlertChannel;
import maple.expectation.alert.channel.AlertTestConfig;
import maple.expectation.alert.channel.InMemoryAlertBuffer;
import maple.expectation.alert.channel.LocalFileAlertChannel;
import maple.expectation.alert.message.AlertMessage;
import maple.expectation.alert.strategy.AlertChannelStrategy;
import maple.expectation.support.AppIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Stateless Alert Service Integration Test
 *
 * <p>통합 테스트: 전체 폴백 체인 검증 (Discord → InMemory → LocalFile)
 *
 * <h3>테스트 커버리지:</h3>
 *
 * <ul>
 *   <li>폴백 체인: Discord 실패 시 InMemory → LocalFile 순차적 시도
 *   <li>커넥션 풀 고갈 시나리오
 *   <li>웹훅 실패 처리
 *   <li>서킷 브레이커 활성화/복구
 *   <li>메트릭 방출 검증
 * </ul>
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@SpringBootTest(classes = {maple.expectation.ExpectationApplication.class, AlertTestConfig.class})
@ActiveProfiles("test")
@Tag("integration")
@DisplayName("상태less 알림 서비스 통합 테스트")
class StatelessAlertServiceIntegrationTest extends AppIntegrationTestSupport {

  @Autowired private StatelessAlertService alertService;

  @Autowired private AlertChannelStrategy channelStrategy;

  @Autowired(required = false)
  private InMemoryAlertBuffer inMemoryBuffer;

  @Autowired(required = false)
  private LocalFileAlertChannel localFileChannel;

  @Autowired(required = false)
  private CircuitBreakerRegistry circuitBreakerRegistry;

  @MockitoBean(name = "alertWebClient")
  private WebClient mockAlertWebClient;

  private Path tempLogFilePath;

  @BeforeEach
  void setUp() throws IOException {
    // 임시 로그 파일 생성
    tempLogFilePath = Files.createTempFile("alert-test-", ".log");
    Files.deleteIfExists(tempLogFilePath);

    // Circuit Breaker 상태 초기화
    if (circuitBreakerRegistry != null) {
      circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    // In-Memory 버퍼 초기화
    if (inMemoryBuffer != null) {
      while (inMemoryBuffer.getBufferSize() > 0) {
        inMemoryBuffer.drainTo(mock(AlertChannel.class));
      }
    }
  }

  @Test
  @DisplayName("정상 흐름: Discord 알림 성공")
  void testNormalFlow_DiscordSuccess() {
    // Given: WebClient가 성공 응답 반환
    WebClient.RequestBodyUriSpec requestBodySpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec requestBodySpec2 = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

    when(mockAlertWebClient.post()).thenReturn(requestBodySpec);
    when(requestBodySpec.uri(anyString())).thenReturn(requestBodySpec2);
    when(requestBodySpec2.bodyValue(any())).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.toBodilessEntity())
        .thenReturn(Mono.just(org.springframework.http.ResponseEntity.ok().build()));

    // When: CRITICAL 알림 전송
    assertDoesNotThrow(() -> alertService.sendCritical("테스트 제목", "테스트 메시지", null));

    // Then: 예외가 발생하지 않음
    verify(mockAlertWebClient, atLeastOnce()).post();
  }

  @Test
  @DisplayName("폴백 체인: Discord 실패 → InMemory 버퍼 저장")
  void testFallbackChain_DiscordFailure_ToInMemory() {
    // Given: WebClient가 예외 발생
    WebClient.RequestBodyUriSpec requestBodySpec = mock(WebClient.RequestBodyUriSpec.class);
    when(mockAlertWebClient.post()).thenReturn(requestBodySpec);
    when(requestBodySpec.uri(anyString()))
        .thenThrow(new ResourceAccessException("Connection pool exhausted"));

    int initialBufferSize = inMemoryBuffer != null ? inMemoryBuffer.getBufferSize() : 0;

    // When: CRITICAL 알림 전송
    alertService.sendCritical("폴백 테스트", "Discord 실패로 InMemory로 폴백", null);

    // Then: In-Memory 버퍼에 저장됨
    if (inMemoryBuffer != null) {
      assertTrue(inMemoryBuffer.getBufferSize() > initialBufferSize, "In-Memory 버퍼에 알림이 저장되어야 함");
    }
  }

  @Test
  @DisplayName("폴백 체인: Discord → InMemory 실패 → LocalFile 저장")
  void testFallbackChain_DiscordToInMemory_ToLocalFile() throws IOException {
    // Given: WebClient 실패 + InMemory 버퍼 꽉 참
    WebClient.RequestBodyUriSpec requestBodySpec = mock(WebClient.RequestBodyUriSpec.class);
    when(mockAlertWebClient.post()).thenReturn(requestBodySpec);
    when(requestBodySpec.uri(anyString()))
        .thenThrow(new ResourceAccessException("Network unreachable"));

    // In-Memory 버퍼를 꽉 채움 (1000개)
    if (inMemoryBuffer != null) {
      for (int i = 0; i < 1000; i++) {
        inMemoryBuffer.send(
            new AlertMessage("Filler", "Fill message " + i, null, "http://test.webhook"));
      }
    }

    // When: 알림 전송 (InMemory 버퍼가 꽉 찼으므로 LocalFile로 폴백)
    alertService.sendCritical("최종 폴백", "모든 채널 실패 시 LocalFile로 저장", null);

    // Then: LocalFile에 기록됨
    assertTrue(Files.exists(tempLogFilePath), "LocalFile에 로그가 기록되어야 함");

    String content = Files.readString(tempLogFilePath);
    assertTrue(content.contains("최종 폴백"), "LocalFile에 알림 내용이 포함되어야 함");
  }

  @Test
  @DisplayName("커넥션 풀 고갈: 대기열 초과 시 타임아웃")
  void testConnectionPoolExhaustion_Timeout() {
    // Given: WebClient가 타임아웃 예외 발생
    WebClient.RequestBodyUriSpec requestBodySpec = mock(WebClient.RequestBodyUriSpec.class);
    when(mockAlertWebClient.post()).thenReturn(requestBodySpec);
    when(requestBodySpec.uri(anyString()))
        .thenThrow(
            new ResourceAccessException("Pool exhausted: Timeout waiting for idle connection"));

    // When: 여러 알림 동시 전송
    for (int i = 0; i < 10; i++) {
      alertService.sendCritical("커넥션 풀 테스트 " + i, "메시지 " + i, null);
    }

    // Then: 모든 요청이 예외 없이 처리됨 (폴백으로 인해)
    if (inMemoryBuffer != null) {
      assertTrue(inMemoryBuffer.getBufferSize() > 0, "In-Memory 버퍼에 알림이 저장되어야 함");
    }
  }

  @Test
  @DisplayName("웹훅 실패: 4xx 응답 시 폴백 미발생")
  void testWebhookFailure_4xx_NoFallback() {
    // Given: WebClient가 400 Bad Request 반환
    WebClient.RequestBodyUriSpec requestBodySpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec requestBodySpec2 = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

    when(mockAlertWebClient.post()).thenReturn(requestBodySpec);
    when(requestBodySpec.uri(anyString())).thenReturn(requestBodySpec2);
    when(requestBodySpec2.bodyValue(any())).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.toBodilessEntity())
        .thenThrow(
            new org.springframework.web.reactive.function.client.WebClientResponseException(
                400, "Bad Request", null, null, null));

    // When: 알림 전송
    alertService.sendNormal("4xx 테스트", "잘못된 요청");

    // Then: 폴백 발생 안 함 (4xx는 클라이언트 오류로 간주)
    if (inMemoryBuffer != null) {
      assertEquals(0, inMemoryBuffer.getBufferSize(), "4xx 오류는 폴백을 트리거하지 않아야 함");
    }
  }

  @Test
  @DisplayName("웹훅 실패: 5xx 응답 시 폴백 발생")
  void testWebhookFailure_5xx_TriggerFallback() {
    // Given: WebClient가 500 Internal Server Error 반환
    WebClient.RequestBodyUriSpec requestBodySpec = mock(WebClient.RequestBodyUriSpec.class);
    when(mockAlertWebClient.post()).thenReturn(requestBodySpec);
    when(requestBodySpec.uri(anyString()))
        .thenThrow(
            new org.springframework.web.reactive.function.client.WebClientResponseException(
                500, "Internal Server Error", null, null, null));

    int initialBufferSize = inMemoryBuffer != null ? inMemoryBuffer.getBufferSize() : 0;

    // When: 알림 전송
    alertService.sendCritical("5xx 테스트", "서버 오류", null);

    // Then: 폴백 발생
    if (inMemoryBuffer != null) {
      assertTrue(inMemoryBuffer.getBufferSize() > initialBufferSize, "5xx 오류는 폴백을 트리거해야 함");
    }
  }

  @Test
  @DisplayName("서킷 브레이커: 연속 실패로 서킷 오픈")
  void testCircuitBreaker_ConsecutiveFailures_OpensCircuit() {
    // Given: Circuit Breaker 설정 확인
    if (circuitBreakerRegistry == null) {
      return; // 테스트 스킵
    }

    CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("discordAlert");
    assertNotNull(circuitBreaker, "Discord Alert Circuit Breaker가 존재해야 함");

    // Given: WebClient가 지속적으로 실패
    WebClient.RequestBodyUriSpec requestBodySpec = mock(WebClient.RequestBodyUriSpec.class);
    when(mockAlertWebClient.post()).thenReturn(requestBodySpec);
    when(requestBodySpec.uri(anyString()))
        .thenThrow(new ResourceAccessException("Connection refused"));

    // When: 연속 실패 발생 (slidingWindowSize: 10, failureRateThreshold: 50%)
    for (int i = 0; i < 10; i++) {
      alertService.sendCritical("서킷 테스트 " + i, "메시지", null);
    }

    // Then: 서킷이 오픈 상태로 전환
    CircuitBreaker.State state = circuitBreaker.getState();
    assertTrue(
        state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.HALF_OPEN,
        "서킷이 OPEN 또는 HALF_OPEN 상태여야 함");
  }

  @Test
  @DisplayName("메트릭 검증: 알림 전송 성공 카운트")
  void testMetrics_SuccessfulAlerts_Counted() {
    // Given: WebClient 성공
    WebClient.RequestBodyUriSpec requestBodySpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec requestBodySpec2 = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

    when(mockAlertWebClient.post()).thenReturn(requestBodySpec);
    when(requestBodySpec.uri(anyString())).thenReturn(requestBodySpec2);
    when(requestBodySpec2.bodyValue(any())).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.toBodilessEntity())
        .thenReturn(Mono.just(org.springframework.http.ResponseEntity.ok().build()));

    // When: 여러 알림 전송
    int alertCount = 5;
    for (int i = 0; i < alertCount; i++) {
      alertService.sendNormal("메트릭 테스트 " + i, "메시지");
    }

    // Then: WebClient 호출 횟수 검증
    verify(mockAlertWebClient, times(alertCount)).post();
  }

  @Test
  @DisplayName("동시성 테스트: 다중 스레드에서 알림 전송")
  void testConcurrency_MultipleThreads_NoDataLoss() throws InterruptedException {
    // Given: WebClient 성공
    WebClient.RequestBodyUriSpec requestBodySpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec requestBodySpec2 = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

    when(mockAlertWebClient.post()).thenReturn(requestBodySpec);
    when(requestBodySpec.uri(anyString())).thenReturn(requestBodySpec2);
    when(requestBodySpec2.bodyValue(any())).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.toBodilessEntity())
        .thenReturn(Mono.just(org.springframework.http.ResponseEntity.ok().build()));

    AtomicInteger successCount = new AtomicInteger(0);
    int threadCount = 10;
    int alertsPerThread = 5;
    Thread[] threads = new Thread[threadCount];

    // When: 다중 스레드에서 알림 전송
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      threads[i] =
          new Thread(
              () -> {
                for (int j = 0; j < alertsPerThread; j++) {
                  try {
                    alertService.sendNormal("스레드 " + threadId, "메시지 " + j);
                    successCount.incrementAndGet();
                  } catch (Exception e) {
                    // 예외 무시 (폴백으로 처리됨)
                  }
                }
              });
      threads[i].start();
    }

    // 모든 스레드 완료 대기
    for (Thread thread : threads) {
      thread.join();
    }

    // Then: 모든 알림이 성공적으로 처리됨
    assertEquals(threadCount * alertsPerThread, successCount.get(), "모든 알림이 성공적으로 처리되어야 함");
  }

  @Test
  @DisplayName("우선순위별 채널 선택: CRITICAL vs NORMAL")
  void testChannelSelection_ByPriority() {
    // Given: CRITICAL 및 NORMAL 우선순위용 채널 준비
    AlertChannel criticalChannel = channelStrategy.getChannel(AlertPriority.CRITICAL);
    AlertChannel normalChannel = channelStrategy.getChannel(AlertPriority.NORMAL);

    // When & Then: 채널이 존재함
    assertNotNull(criticalChannel, "CRITICAL 채널이 존재해야 함");
    assertNotNull(normalChannel, "NORMAL 채널이 존재해야 함");

    // 채널 이름 검증
    String criticalChannelName = criticalChannel.getChannelName();
    String normalChannelName = normalChannel.getChannelName();

    assertNotNull(criticalChannelName, "CRITICAL 채널 이름이 존재해야 함");
    assertNotNull(normalChannelName, "NORMAL 채널 이름이 존재해야 함");
  }

  @Test
  @DisplayName("In-Memory 버퍼 드레인: 저장된 알림을 다른 채널로 이동")
  void testInMemoryBuffer_DrainToChannel() {
    // Given: In-Memory 버퍼에 알림 저장
    if (inMemoryBuffer == null) {
      return; // 테스트 스킵
    }

    AlertMessage testMessage = new AlertMessage("드레인 테스트", "메시지", null, "http://test.webhook");
    inMemoryBuffer.send(testMessage);

    assertEquals(1, inMemoryBuffer.getBufferSize(), "버퍼에 1개의 알림이 있어야 함");

    // When: 버퍼를 LocalFile 채널로 드레인
    AlertChannel targetChannel = mock(AlertChannel.class);
    when(targetChannel.send(any())).thenReturn(true);
    when(targetChannel.getChannelName()).thenReturn("test-channel");

    int drained = inMemoryBuffer.drainTo(targetChannel);

    // Then: 모든 알림이 드레인됨
    assertEquals(1, drained, "1개의 알림이 드레인되어야 함");
    assertEquals(0, inMemoryBuffer.getBufferSize(), "버퍼가 비어야 함");
    verify(targetChannel, times(1)).send(testMessage);
  }
}
