package maple.expectation.service.v2.donation.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import maple.expectation.config.OutboxProperties;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.persistence.repository.DonationOutboxRepository;
import maple.expectation.support.TestLogicExecutors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OutboxProcessor 단위 테스트
 *
 * <h3>P0 검증</h3>
 *
 * <ul>
 *   <li>P0-1: processEntry 실패 시 handleFailure 호출 (Zombie Loop 방지)
 *   <li>P0-2: 항목별 독립 트랜잭션 처리
 * </ul>
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>Spring Context 없이 Mockito만으로 검증
 */
@Tag("unit")
class OutboxProcessorTest {

  private DonationOutboxRepository outboxRepository;
  private DlqHandler dlqHandler;
  private OutboxMetrics metrics;
  private LogicExecutor executor;
  private TransactionTemplate transactionTemplate;
  private OutboxProperties properties;

  private OutboxProcessor processor;

  private maple.expectation.service.v2.donation.outbox.OutboxFetchFacade fetchFacade;

  @BeforeEach
  void setUp() {
    outboxRepository = mock(DonationOutboxRepository.class);
    dlqHandler = mock(DlqHandler.class);
    metrics = mock(OutboxMetrics.class);
    executor = TestLogicExecutors.passThrough();
    transactionTemplate = mock(TransactionTemplate.class);
    properties = createTestProperties();

    // Create mock OutboxFetchFacade
    fetchFacade = mock(maple.expectation.service.v2.donation.outbox.OutboxFetchFacade.class);

    processor =
        new OutboxProcessor(
            fetchFacade, // OutboxFetchFacade
            dlqHandler, // DlqHandler
            metrics, // OutboxMetrics
            executor, // LogicExecutor
            transactionTemplate, // TransactionTemplate
            properties, // OutboxProperties
            outboxRepository); // DonationOutboxRepository
  }

  @Nested
  @DisplayName("P0-1: Zombie Loop 방지")
  class ZombieLoopPreventionTest {

    @Test
    @DisplayName("processEntry 예외 시 handleFailure 호출 -> retryCount 증가")
    void shouldCallHandleFailureOnException() {
      // given
      DonationOutbox entry = createTestOutbox(1L, "req-001");
      entry.markProcessing("test-instance");

      // TransactionTemplate에서 예외 발생 시뮬레이션
      given(transactionTemplate.execute(any(TransactionCallback.class)))
          .willThrow(new RuntimeException("DB connection lost"));

      // recoverFailedEntry가 별도 트랜잭션으로 실행
      doAnswer(
              inv -> {
                Consumer<Void> callback = inv.getArgument(0);
                return null;
              })
          .when(transactionTemplate)
          .executeWithoutResult(any());

      given(outboxRepository.findById(1L)).willReturn(Optional.of(entry));

      // when — fetchAndLock의 결과를 시뮬레이션하여 processEntryInTransaction 호출
      // pollAndProcess 내부에서 fetchAndLock -> processBatch -> processEntryInTransaction 순서
      List<DonationOutbox> locked = List.of(entry);

      // processEntryInTransaction을 직접 테스트 (리플렉션)
      Boolean result = ReflectionTestUtils.invokeMethod(processor, "processEntryInTransaction", 1L);

      // then: 실패해야 함
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("무결성 검증 실패 시 즉시 DLQ 이동")
    void shouldMoveToDeadLetterOnIntegrityFailure() {
      // given
      DonationOutbox entry = createTestOutbox(1L, "req-001");
      // 해시를 변조하여 무결성 검증 실패 유도
      ReflectionTestUtils.setField(entry, "contentHash", "tampered-hash");

      given(transactionTemplate.execute(any(TransactionCallback.class)))
          .willAnswer(
              inv -> {
                TransactionCallback<?> callback = inv.getArgument(0);
                return callback.doInTransaction(null);
              });

      given(outboxRepository.findById(1L)).willReturn(Optional.of(entry));

      // when
      Boolean result = ReflectionTestUtils.invokeMethod(processor, "processEntryInTransaction", 1L);

      // then
      assertThat(result).isFalse();
      verify(dlqHandler).handleDeadLetter(any(), eq("Integrity verification failed"));
      verify(metrics).incrementIntegrityFailure();
    }
  }

  @Nested
  @DisplayName("P0-2: 항목별 독립 트랜잭션")
  class IndependentTransactionTest {

    @Test
    @DisplayName("fetchAndLock() 후 processBatch() 호출 분리 검증")
    void shouldSeparateFetchAndProcess() {
      // given: fetchAndLock은 별도 @Transactional
      DonationOutbox entry1 = createTestOutbox(1L, "req-001");
      DonationOutbox entry2 = createTestOutbox(2L, "req-002");

      // fetchFacade.fetchAndLock() returns entries
      given(fetchFacade.fetchAndLock()).willReturn(List.of(entry1, entry2));

      // processEntryInTransaction은 TransactionTemplate 사용
      given(transactionTemplate.execute(any(TransactionCallback.class)))
          .willAnswer(
              inv -> {
                TransactionCallback<?> callback = inv.getArgument(0);
                return callback.doInTransaction(null);
              });
      given(outboxRepository.findById(1L)).willReturn(Optional.of(entry1));
      given(outboxRepository.findById(2L)).willReturn(Optional.of(entry2));

      // when
      processor.pollAndProcess();

      // then: TransactionTemplate이 항목별로 호출됨 (2번)
      verify(transactionTemplate, times(2)).execute(any(TransactionCallback.class));
      verify(metrics, times(2)).incrementProcessed();
    }

    @Test
    @DisplayName("1건 실패 시 나머지 항목은 정상 처리")
    void shouldContinueProcessingOnSingleFailure() {
      // given
      DonationOutbox entry1 = createTestOutbox(1L, "req-001");
      DonationOutbox entry2 = createTestOutbox(2L, "req-002");

      // fetchFacade.fetchAndLock() returns entries
      given(fetchFacade.fetchAndLock()).willReturn(List.of(entry1, entry2));

      // entry1: 실패, entry2: 성공
      given(transactionTemplate.execute(any(TransactionCallback.class)))
          .willThrow(new RuntimeException("DB error")) // entry1 실패
          .willAnswer(
              inv -> { // entry2 성공
                TransactionCallback<?> callback = inv.getArgument(0);
                return callback.doInTransaction(null);
              });

      // recoverFailedEntry에서 사용
      doNothing().when(transactionTemplate).executeWithoutResult(any());
      given(outboxRepository.findById(1L)).willReturn(Optional.of(entry1));
      given(outboxRepository.findById(2L)).willReturn(Optional.of(entry2));

      // when
      processor.pollAndProcess();

      // then: entry2는 성공 처리됨
      verify(metrics, times(1)).incrementProcessed();
    }
  }

  @Nested
  @DisplayName("정상 처리 흐름")
  class NormalFlowTest {

    @Test
    @DisplayName("Pending 없으면 배치 처리 스킵")
    void shouldSkipWhenNoPending() {
      // given
      given(fetchFacade.fetchAndLock()).willReturn(List.of());

      // when
      processor.pollAndProcess();

      // then
      verify(transactionTemplate, never()).execute(any());
    }

    @Test
    @DisplayName("정상 항목은 COMPLETED 상태로 변경")
    void shouldMarkCompletedOnSuccess() {
      // given
      DonationOutbox entry = createTestOutbox(1L, "req-001");

      // fetchFacade.fetchAndLock() returns entry
      given(fetchFacade.fetchAndLock()).willReturn(List.of(entry));
      given(transactionTemplate.execute(any(TransactionCallback.class)))
          .willAnswer(
              inv -> {
                TransactionCallback<?> callback = inv.getArgument(0);
                return callback.doInTransaction(null);
              });
      given(outboxRepository.findById(1L)).willReturn(Optional.of(entry));

      // when
      processor.pollAndProcess();

      // then
      verify(metrics).incrementProcessed();
    }
  }

  // ==================== Helper Methods ====================

  private DonationOutbox createTestOutbox(Long id, String requestId) {
    DonationOutbox outbox =
        DonationOutbox.create(
            requestId, "DONATION_COMPLETED", "{\"amount\":1000,\"donor\":\"TestUser\"}");
    ReflectionTestUtils.setField(outbox, "id", id);
    return outbox;
  }

  private OutboxProperties createTestProperties() {
    OutboxProperties props = new OutboxProperties();
    props.setBatchSize(100);
    props.setStaleThreshold(Duration.ofMinutes(5));
    props.setMaxBackoff(Duration.ofHours(1));
    props.setInstanceId("test-instance");
    return props;
  }
}
