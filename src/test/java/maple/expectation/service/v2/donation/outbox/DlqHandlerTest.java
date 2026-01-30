package maple.expectation.service.v2.donation.outbox;

import maple.expectation.domain.v2.DonationDlq;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.repository.v2.DonationDlqRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * DlqHandler 단위 테스트
 *
 * <h3>P0-3 검증: ClassCastException 제거</h3>
 * <p>Throwable(Error 포함)이 3차 안전망까지 안전하게 전달되는지 검증</p>
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 * <p>Spring Context 없이 Mockito만으로 검증</p>
 */
@Tag("unit")
class DlqHandlerTest {

    private DonationDlqRepository dlqRepository;
    private ShutdownDataPersistenceService fileBackupService;
    private DiscordAlertService discordAlertService;
    private LogicExecutor executor;
    private OutboxMetrics metrics;

    private DlqHandler dlqHandler;

    @BeforeEach
    void setUp() {
        dlqRepository = mock(DonationDlqRepository.class);
        fileBackupService = mock(ShutdownDataPersistenceService.class);
        discordAlertService = mock(DiscordAlertService.class);
        executor = createPassThroughExecutor();
        metrics = mock(OutboxMetrics.class);

        dlqHandler = new DlqHandler(
                dlqRepository, fileBackupService, discordAlertService, executor, metrics
        );
    }

    @Nested
    @DisplayName("Triple Safety Net")
    class TripleSafetyNetTest {

        @Test
        @DisplayName("1차: DB DLQ INSERT 성공")
        void shouldSaveToDbDlq() {
            // given
            DonationOutbox entry = createTestOutbox();

            // when
            dlqHandler.handleDeadLetter(entry, "Max retry exceeded");

            // then
            verify(dlqRepository).save(any(DonationDlq.class));
            verify(metrics).incrementDlq();
        }

        @Test
        @DisplayName("1차 실패 -> 2차: File Backup")
        void shouldFallbackToFileBackup() {
            // given
            DonationOutbox entry = createTestOutbox();
            given(dlqRepository.save(any())).willThrow(new RuntimeException("DB down"));

            // when
            dlqHandler.handleDeadLetter(entry, "Max retry exceeded");

            // then
            verify(fileBackupService).appendOutboxEntry(eq("req-001"), anyString());
            verify(metrics).incrementFileBackup();
        }

        @Test
        @DisplayName("1차/2차 실패 -> 3차: Discord Critical Alert")
        void shouldFallbackToDiscordAlert() {
            // given
            DonationOutbox entry = createTestOutbox();
            given(dlqRepository.save(any())).willThrow(new RuntimeException("DB down"));
            doThrow(new RuntimeException("Disk full")).when(fileBackupService)
                    .appendOutboxEntry(anyString(), anyString());

            // when
            dlqHandler.handleDeadLetter(entry, "Max retry exceeded");

            // then
            verify(discordAlertService).sendCriticalAlert(
                    contains("OUTBOX CRITICAL FAILURE"),
                    contains("req-001"),
                    any(Throwable.class)
            );
            verify(metrics).incrementCriticalFailure();
        }
    }

    @Nested
    @DisplayName("P0-3: ClassCastException 제거")
    class ClassCastExceptionFixTest {

        @Test
        @DisplayName("Error(OOM) 발생 시에도 3차 안전망이 정상 동작")
        void shouldHandleErrorWithoutClassCastException() {
            // given
            DonationOutbox entry = createTestOutbox();
            given(dlqRepository.save(any())).willThrow(new RuntimeException("DB down"));
            doThrow(new OutOfMemoryError("Heap space"))
                    .when(fileBackupService).appendOutboxEntry(anyString(), anyString());

            // when — P0-3 Fix 전에는 ClassCastException 발생
            dlqHandler.handleDeadLetter(entry, "Max retry exceeded");

            // then: Throwable(Error)이 안전하게 전달됨
            verify(discordAlertService).sendCriticalAlert(
                    anyString(),
                    anyString(),
                    any(Throwable.class)
            );
            verify(metrics).incrementCriticalFailure();
        }

        @Test
        @DisplayName("StackOverflowError 발생 시에도 3차 안전망 동작")
        void shouldHandleStackOverflowError() {
            // given
            DonationOutbox entry = createTestOutbox();
            given(dlqRepository.save(any())).willThrow(new RuntimeException("DB down"));
            doThrow(new StackOverflowError("Stack overflow"))
                    .when(fileBackupService).appendOutboxEntry(anyString(), anyString());

            // when
            dlqHandler.handleDeadLetter(entry, "Max retry exceeded");

            // then
            verify(discordAlertService).sendCriticalAlert(
                    anyString(),
                    anyString(),
                    any(Throwable.class)
            );
        }
    }

    // ==================== Helper Methods ====================

    private DonationOutbox createTestOutbox() {
        DonationOutbox outbox = DonationOutbox.create(
                "req-001", "DONATION_COMPLETED", "{\"amount\":1000}");
        ReflectionTestUtils.setField(outbox, "id", 1L);
        return outbox;
    }

    @SuppressWarnings("unchecked")
    private LogicExecutor createPassThroughExecutor() {
        LogicExecutor mockExecutor = mock(LogicExecutor.class);

        // executeOrCatch
        given(mockExecutor.executeOrCatch(any(ThrowingSupplier.class), any(Function.class), any(TaskContext.class)))
                .willAnswer(invocation -> {
                    ThrowingSupplier<?> task = invocation.getArgument(0);
                    Function<Throwable, ?> recovery = invocation.getArgument(1);
                    try {
                        return task.get();
                    } catch (Throwable e) {
                        return recovery.apply(e);
                    }
                });

        // executeVoid
        doAnswer(invocation -> {
            ThrowingRunnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(mockExecutor).executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));

        return mockExecutor;
    }
}
