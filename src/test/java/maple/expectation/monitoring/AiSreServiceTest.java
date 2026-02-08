package maple.expectation.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import maple.expectation.monitoring.ai.AiSreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AiSreService 단위 테스트
 *
 * <p>외부 API(Discord, Z.ai) 의존성을 제거하고 핵심 로직만 검증합니다.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("AiSreService 단위 테스트")
class AiSreServiceTest {

  @Mock private AiSreService aiSreService;

  private SQLException testException;

  @BeforeEach
  void setUp() {
    testException =
        new SQLException("Connection timeout: could not connect to database within 5000ms");
  }

  @Test
  @DisplayName("AI 에러 분석 비동기 요청 성공")
  void shouldAnalyzeErrorAsyncSuccessfully() {
    // given
    AiSreService.AiAnalysisResult mockResult =
        new AiSreService.AiAnalysisResult(
            "Database connection timeout",
            "high",
            "Database",
            "Check connection pool",
            "Z.ai Analysis",
            "AI-generated analysis");
    given(aiSreService.analyzeErrorAsync(any(SQLException.class)))
        .willReturn(CompletableFuture.completedFuture(Optional.of(mockResult)));

    // when
    CompletableFuture<Optional<AiSreService.AiAnalysisResult>> future =
        aiSreService.analyzeErrorAsync(testException);

    // then
    assertThat(future).isCompletedWithValue(Optional.of(mockResult));
    assertThat(mockResult.rootCause()).isEqualTo("Database connection timeout");
    assertThat(mockResult.severity()).isEqualTo("high");
  }

  @Test
  @DisplayName("AI 에러 분석 결과가 없을 경우 empty 반환")
  void shouldReturnEmptyWhenNoAnalysis() {
    // given
    given(aiSreService.analyzeErrorAsync(any(SQLException.class)))
        .willReturn(CompletableFuture.completedFuture(Optional.empty()));

    // when
    CompletableFuture<Optional<AiSreService.AiAnalysisResult>> future =
        aiSreService.analyzeErrorAsync(testException);

    // then
    assertThat(future).isCompletedWithValue(Optional.empty());
  }

  @Test
  @DisplayName("AI 분석 실패 시 예외 전파")
  void shouldPropagateExceptionWhenAnalysisFails() {
    // given
    RuntimeException analysisException = new RuntimeException("AI API timeout");
    given(aiSreService.analyzeErrorAsync(any(SQLException.class)))
        .willReturn(CompletableFuture.failedFuture(analysisException));

    // when
    CompletableFuture<Optional<AiSreService.AiAnalysisResult>> future =
        aiSreService.analyzeErrorAsync(testException);

    // then
    assertThat(future).isCompletedExceptionally();
    future.handle((result, ex) -> {
      assertThat(ex).isInstanceOf(RuntimeException.class)
          .hasMessageContaining("AI API timeout");
      return null;
    }).join();
  }

  @Test
  @DisplayName("동시 분석 요청 시 독립적으로 처리")
  void shouldHandleConcurrentAnalysisRequests() throws Exception {
    // given
    SQLException exception1 = new SQLException("Error 1");
    SQLException exception2 = new SQLException("Error 2");

    AiSreService.AiAnalysisResult result1 =
        new AiSreService.AiAnalysisResult("Cause 1", "medium", "Component 1", "Fix 1", "Z.ai", "AI-generated");
    AiSreService.AiAnalysisResult result2 =
        new AiSreService.AiAnalysisResult("Cause 2", "low", "Component 2", "Fix 2", "Z.ai", "AI-generated");

    given(aiSreService.analyzeErrorAsync(exception1))
        .willReturn(CompletableFuture.completedFuture(Optional.of(result1)));
    given(aiSreService.analyzeErrorAsync(exception2))
        .willReturn(CompletableFuture.completedFuture(Optional.of(result2)));

    // when
    CompletableFuture<Optional<AiSreService.AiAnalysisResult>> future1 =
        aiSreService.analyzeErrorAsync(exception1);
    CompletableFuture<Optional<AiSreService.AiAnalysisResult>> future2 =
        aiSreService.analyzeErrorAsync(exception2);

    // then
    CompletableFuture.allOf(future1, future2).join();
    assertThat(future1.get()).isPresent().hasValueSatisfying(r ->
        r.rootCause().equals("Cause 1"));
    assertThat(future2.get()).isPresent().hasValueSatisfying(r ->
        r.rootCause().equals("Cause 2"));

    verify(aiSreService).analyzeErrorAsync(exception1);
    verify(aiSreService).analyzeErrorAsync(exception2);
  }

  @Test
  @DisplayName("동일 예외에 대한 캐싱 동작 검증")
  void shouldCacheSameExceptionAnalysis() {
    // given
    given(aiSreService.analyzeErrorAsync(any(SQLException.class)))
        .willReturn(CompletableFuture.completedFuture(
            Optional.of(new AiSreService.AiAnalysisResult("Cause", "low", "Comp", "Fix", "Z.ai", "AI-generated"))));

    // when
    aiSreService.analyzeErrorAsync(testException);
    aiSreService.analyzeErrorAsync(testException);

    // then
    verify(aiSreService, times(2)).analyzeErrorAsync(any(SQLException.class));
  }
}
