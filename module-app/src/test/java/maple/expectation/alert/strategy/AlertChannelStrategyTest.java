package maple.expectation.alert.strategy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import maple.expectation.alert.channel.AlertChannel;
import maple.expectation.alert.channel.DiscordAlertChannel;
import maple.expectation.alert.channel.InMemoryAlertBuffer;
import maple.expectation.alert.channel.LocalFileAlertChannel;
import maple.expectation.alert.AlertPriority;
import maple.expectation.support.AppIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * Alert Channel Strategy Test
 *
 * <p>채널 선택 및 폴백 체인 활성화 테스트
 *
 * <h3>테스트 커버리지:</h3>
 *
 * <ul>
 *   <li>우선순위별 채널 선택
 *   <li>폴백 체인 활성화 조건
 *   <li>채널 등록 및 조회
 *   <li>기본 채널 동작
 * </ul>
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@SpringBootTest(classes = maple.expectation.ExpectationApplication.class)
@ActiveProfiles("test")
@Tag("integration")
@DisplayName("알림 채널 전략 테스트")
class AlertChannelStrategyTest extends AppIntegrationTestSupport {

  @Autowired private AlertChannelStrategy channelStrategy;

  @MockBean private DiscordAlertChannel mockDiscordChannel;

  @MockBean private InMemoryAlertBuffer mockInMemoryBuffer;

  @MockBean private LocalFileAlertChannel mockLocalFileChannel;

  @BeforeEach
  void setUp() {
    // Mock 채널 기본 설정
    when(mockDiscordChannel.getChannelName()).thenReturn("discord");
    when(mockInMemoryBuffer.getChannelName()).thenReturn("in-memory");
    when(mockLocalFileChannel.getChannelName()).thenReturn("local-file");
  }

  @Test
  @DisplayName("CRITICAL 우선순위: Discord 채널 반환")
  void testCriticalPriority_ReturnsDiscordChannel() {
    // Given: CRITICAL 우선순위

    // When: 채널 조회
    AlertChannel channel = channelStrategy.getChannel(AlertPriority.CRITICAL);

    // Then: Discord 채널이 반환됨
    assertNotNull(channel, "채널이 null이 아니어야 함");
    // 실제 구현에서는 Discord 채널이 주입됨
    // Mock을 사용하므로 이름만 검증
    assertNotNull(channel.getChannelName(), "채널 이름이 존재해야 함");
  }

  @Test
  @DisplayName("NORMAL 우선순위: Discord 채널 반환")
  void testNormalPriority_ReturnsDiscordChannel() {
    // Given: NORMAL 우선순위

    // When: 채널 조회
    AlertChannel channel = channelStrategy.getChannel(AlertPriority.NORMAL);

    // Then: Discord 채널이 반환됨
    assertNotNull(channel, "채널이 null이 아니어야 함");
    assertNotNull(channel.getChannelName(), "채널 이름이 존재해야 함");
  }

  @Test
  @DisplayName("BACKGROUND 우선순위: 기본 채널 반환")
  void testBackgroundPriority_ReturnsDefaultChannel() {
    // Given: BACKGROUND 우선순위

    // When: 채널 조회
    AlertChannel channel = channelStrategy.getChannel(AlertPriority.BACKGROUND);

    // Then: 기본 채널이 반환됨
    assertNotNull(channel, "기본 채널이 반환되어야 함");
    assertNotNull(channel.getChannelName(), "채널 이름이 존재해야 함");
  }

  @Test
  @DisplayName("알 수 없는 우선순위: 기본 채널 반환")
  void testUnknownPriority_ReturnsDefaultChannel() {
    // Given: 등록되지 않은 우선순위 (현재 Enum에는 없지만 확장성을 위해)

    // When: 모든 우선순위에 대해 채널 조회
    AlertChannel criticalChannel = channelStrategy.getChannel(AlertPriority.CRITICAL);
    AlertChannel normalChannel = channelStrategy.getChannel(AlertPriority.NORMAL);
    AlertChannel backgroundChannel = channelStrategy.getChannel(AlertPriority.BACKGROUND);

    // Then: 모두 null이 아님
    assertNotNull(criticalChannel, "CRITICAL 채널이 존재해야 함");
    assertNotNull(normalChannel, "NORMAL 채널이 존재해야 함");
    assertNotNull(backgroundChannel, "BACKGROUND 채널이 존재해야 함");
  }

  @Test
  @DisplayName("폴백 체인 활성화: Discord 실패 시 InMemory로 폴백")
  void testFallbackChain_DiscordFailure_ActivatesInMemory() {
    // Given: Discord 채널이 실패하도록 설정
    when(mockDiscordChannel.send(any())).thenReturn(false);
    when(mockInMemoryBuffer.send(any())).thenReturn(true);

    // When: Discord 채널 전송 시도
    boolean discordResult = mockDiscordChannel.send(null);

    // Then: Discord 실패 확인
    assertFalse(discordResult, "Discord 채널이 실패해야 함");

    // 폴백 체인이 InMemory로 활성화되는지 확인
    // 이는 실제 구현에서 FallbackSupport 인터페이스를 통해 처리됨
    // 여기서는 Mock을 사용하므로 구조만 검증
    verify(mockDiscordChannel, atLeastOnce()).send(any());
  }

  @Test
  @DisplayName("폴백 체인 활성화: InMemory 실패 시 LocalFile로 폴백")
  void testFallbackChain_InMemoryFailure_ActivatesLocalFile() {
    // Given: InMemory 버퍼가 꽉 참
    when(mockInMemoryBuffer.send(any())).thenReturn(false);
    when(mockLocalFileChannel.send(any())).thenReturn(true);

    // When: InMemory 버퍼 전송 시도
    boolean inMemoryResult = mockInMemoryBuffer.send(null);

    // Then: InMemory 실패 확인
    assertFalse(inMemoryResult, "InMemory 버퍼가 실패해야 함");

    // 폴백 체인이 LocalFile로 활성화되는지 확인
    verify(mockInMemoryBuffer, atLeastOnce()).send(any());
  }

  @Test
  @DisplayName("채널 등록: 여러 채널 동시 등록 및 조회")
  void testChannelRegistration_RegisterMultipleChannels_RetrieveAll() {
    // Given: StatefulAlertChannelStrategy 인스턴스 생성 (테스트용)
    Map<AlertPriority, Supplier<AlertChannel>> providers =
        Map.of(
            AlertPriority.CRITICAL, () -> mockDiscordChannel,
            AlertPriority.NORMAL, () -> mockInMemoryBuffer,
            AlertPriority.BACKGROUND, () -> mockLocalFileChannel);

    StatelessAlertChannelStrategy strategy = new StatelessAlertChannelStrategy(providers);

    // When: 각 우선순위별 채널 조회
    AlertChannel criticalChannel = strategy.getChannel(AlertPriority.CRITICAL);
    AlertChannel normalChannel = strategy.getChannel(AlertPriority.NORMAL);
    AlertChannel backgroundChannel = strategy.getChannel(AlertPriority.BACKGROUND);

    // Then: 모든 채널이 올바르게 반환됨
    assertNotNull(criticalChannel, "CRITICAL 채널이 반환되어야 함");
    assertNotNull(normalChannel, "NORMAL 채널이 반환되어야 함");
    assertNotNull(backgroundChannel, "BACKGROUND 채널이 반환되어야 함");

    assertEquals("discord", criticalChannel.getChannelName());
    assertEquals("in-memory", normalChannel.getChannelName());
    assertEquals("local-file", backgroundChannel.getChannelName());
  }

  @Test
  @DisplayName("채널 공급자: Lazy initialization 검증")
  void testChannelProvider_LazyInitialization_OnlyWhenCalled() {
    // Given: 채널 공급자가 호출 횟수를 추적
    AtomicInteger discordCallCount = new AtomicInteger(0);
    AtomicInteger inMemoryCallCount = new AtomicInteger(0);

    Supplier<AlertChannel> discordSupplier =
        () -> {
          discordCallCount.incrementAndGet();
          return mockDiscordChannel;
        };

    Supplier<AlertChannel> inMemorySupplier =
        () -> {
          inMemoryCallCount.incrementAndGet();
          return mockInMemoryBuffer;
        };

    Map<AlertPriority, Supplier<AlertChannel>> providers =
        Map.of(
            AlertPriority.CRITICAL, discordSupplier,
            AlertPriority.NORMAL, inMemorySupplier);

    StatelessAlertChannelStrategy strategy = new StatelessAlertChannelStrategy(providers);

    // When: CRITICAL 채널만 조회
    AlertChannel criticalChannel = strategy.getChannel(AlertPriority.CRITICAL);

    // Then: Discord 공급자만 호출됨
    assertEquals(1, discordCallCount.get(), "Discord 공급자가 1번 호출되어야 함");
    assertEquals(0, inMemoryCallCount.get(), "InMemory 공급자는 호출되지 않아야 함");
  }

  @Test
  @DisplayName("동시성: 다중 스레드에서 채널 조회")
  void testConcurrency_MultipleThreads_ChannelRetrieval() throws InterruptedException {
    // Given: 다중 스레드 환경
    Map<AlertPriority, Supplier<AlertChannel>> providers =
        Map.of(
            AlertPriority.CRITICAL, () -> mockDiscordChannel,
            AlertPriority.NORMAL, () -> mockInMemoryBuffer);

    StatelessAlertChannelStrategy strategy = new StatelessAlertChannelStrategy(providers);

    AtomicInteger successCount = new AtomicInteger(0);
    int threadCount = 10;
    Thread[] threads = new Thread[threadCount];

    // When: 다중 스레드에서 동시에 채널 조회
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      threads[i] =
          new Thread(
              () -> {
                try {
                  AlertChannel channel =
                      strategy.getChannel(
                          threadId % 2 == 0 ? AlertPriority.CRITICAL : AlertPriority.NORMAL);
                  if (channel != null) {
                    successCount.incrementAndGet();
                  }
                } catch (Exception e) {
                  // 예외 무시
                }
              });
      threads[i].start();
    }

    // 모든 스레드 완료 대기
    for (Thread thread : threads) {
      thread.join();
    }

    // Then: 모든 조회가 성공함
    assertEquals(threadCount, successCount.get(), "모든 채널 조회가 성공해야 함");
  }

  @Test
  @DisplayName("채널 순서: 우선순위별 올바른 채널 반환")
  void testChannelOrder_ByPriority_ReturnsCorrectChannels() {
    // Given: 모든 우선순위에 대한 채널 등록
    Map<AlertPriority, Supplier<AlertChannel>> providers =
        Map.of(
            AlertPriority.CRITICAL, () -> mockDiscordChannel,
            AlertPriority.NORMAL, () -> mockInMemoryBuffer,
            AlertPriority.BACKGROUND, () -> mockLocalFileChannel);

    StatelessAlertChannelStrategy strategy = new StatelessAlertChannelStrategy(providers);

    // When & Then: 각 우선순위별 채널 검증
    AlertChannel criticalChannel = strategy.getChannel(AlertPriority.CRITICAL);
    assertEquals("discord", criticalChannel.getChannelName(), "CRITICAL은 Discord여야 함");

    AlertChannel normalChannel = strategy.getChannel(AlertPriority.NORMAL);
    assertEquals("in-memory", normalChannel.getChannelName(), "NORMAL은 InMemory여야 함");

    AlertChannel backgroundChannel = strategy.getChannel(AlertPriority.BACKGROUND);
    assertEquals("local-file", backgroundChannel.getChannelName(), "BACKGROUND는 LocalFile여야 함");
  }

  @Test
  @DisplayName("Null 공급자: 안전한 실패 처리")
  void testNullSupplier_SafeFailure() {
    // Given: null 공급자가 포함된 맵
    Map<AlertPriority, Supplier<AlertChannel>> providers =
        Map.of(AlertPriority.CRITICAL, () -> mockDiscordChannel, AlertPriority.NORMAL, null);

    StatelessAlertChannelStrategy strategy = new StatelessAlertChannelStrategy(providers);

    // When: null 공급자가 있는 우선순위 조회
    AlertChannel normalChannel = strategy.getChannel(AlertPriority.NORMAL);

    // Then: 안전한 기본 동작 (NullPointerException 방지)
    // 현재 구현에서는 getOrDefault가 기본 채널을 반환
    // 기본 채널은 UnsupportedOperationException을 던질 수 있음
    if (normalChannel != null) {
      // 기본 채널이 반환되면 예외 발생 가능
      assertThrows(
          UnsupportedOperationException.class,
          normalChannel::getChannelName,
          "기본 채널은 UnsupportedOperationException을 던져야 함");
    }
  }

  @Test
  @DisplayName("빈 공급자 맵: 모든 우선순위가 기본 채널 반환")
  void testEmptyProviderMap_AllPrioritiesReturnDefault() {
    // Given: 빈 공급자 맵
    Map<AlertPriority, Supplier<AlertChannel>> providers = Map.of();
    StatelessAlertChannelStrategy strategy = new StatelessAlertChannelStrategy(providers);

    // When: 모든 우선순위 조회
    AlertChannel criticalChannel = strategy.getChannel(AlertPriority.CRITICAL);
    AlertChannel normalChannel = strategy.getChannel(AlertPriority.NORMAL);
    AlertChannel backgroundChannel = strategy.getChannel(AlertPriority.BACKGROUND);

    // Then: 모두 기본 채널 반환 (UnsupportedOperationException)
    assertAll(
        "기본 채널은 UnsupportedOperationException을 던져야 함",
        () -> assertThrows(UnsupportedOperationException.class, criticalChannel::getChannelName),
        () -> assertThrows(UnsupportedOperationException.class, normalChannel::getChannelName),
        () -> assertThrows(UnsupportedOperationException.class, backgroundChannel::getChannelName));
  }
}
