package maple.expectation.global.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Issue #143: MDCFilter 단위 테스트 P1-3 해결: MDCFilter 테스트 부재 → 테스트 추가
 *
 * <p>테스트 케이스: 1. X-Correlation-ID 헤더가 있으면 해당 ID를 사용 2. 헤더가 없으면 UUID를 자동 생성 3. 응답 헤더에
 * X-Correlation-ID가 포함됨
 */
@ExtendWith(MockitoExtension.class)
class MDCFilterTest {

  @Mock private LogicExecutor executor;

  @Mock private FilterChain filterChain;

  private MDCFilter mdcFilter;

  @BeforeEach
  void setUp() {
    mdcFilter = new MDCFilter(executor);

    // LogicExecutor passthrough 설정 (CLAUDE.md 섹션 10 준수)
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              ThrowingSupplier<Object> task = invocation.getArgument(0);
              Runnable finalizer = invocation.getArgument(1);
              try {
                return task.get();
              } finally {
                finalizer.run();
              }
            })
        .when(executor)
        .executeWithFinally(
            any(ThrowingSupplier.class), any(Runnable.class), any(TaskContext.class));
  }

  @Test
  @DisplayName("X-Correlation-ID 헤더가 있으면 해당 ID를 사용한다")
  void shouldUseProvidedCorrelationId() throws Exception {
    // given
    String expectedId = "test-correlation-id-12345";
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Correlation-ID", expectedId);
    MockHttpServletResponse response = new MockHttpServletResponse();

    // when
    mdcFilter.doFilter(request, response, filterChain);

    // then
    assertThat(response.getHeader("X-Correlation-ID")).isEqualTo(expectedId);
  }

  @Test
  @DisplayName("X-Correlation-ID 헤더가 없으면 UUID를 자동 생성한다")
  void shouldGenerateUuidWhenNoHeader() throws Exception {
    // given
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    // when
    mdcFilter.doFilter(request, response, filterChain);

    // then
    String correlationId = response.getHeader("X-Correlation-ID");
    assertThat(correlationId).isNotNull();
    assertThat(correlationId).isNotBlank();

    // UUID 형식 검증
    assertThat(UUID.fromString(correlationId)).isNotNull();
  }

  @Test
  @DisplayName("빈 문자열 헤더가 있으면 UUID를 자동 생성한다")
  void shouldGenerateUuidWhenEmptyHeader() throws Exception {
    // given
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Correlation-ID", "");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // when
    mdcFilter.doFilter(request, response, filterChain);

    // then
    String correlationId = response.getHeader("X-Correlation-ID");
    assertThat(correlationId).isNotNull();
    assertThat(correlationId).isNotBlank();

    // UUID 형식 검증
    assertThat(UUID.fromString(correlationId)).isNotNull();
  }

  @Test
  @DisplayName("공백만 있는 헤더가 있으면 UUID를 자동 생성한다")
  void shouldGenerateUuidWhenBlankHeader() throws Exception {
    // given
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Correlation-ID", "   ");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // when
    mdcFilter.doFilter(request, response, filterChain);

    // then
    String correlationId = response.getHeader("X-Correlation-ID");
    assertThat(correlationId).isNotNull();
    assertThat(correlationId).isNotBlank();

    // UUID 형식 검증
    assertThat(UUID.fromString(correlationId)).isNotNull();
  }
}
