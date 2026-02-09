package maple.expectation.global.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 로그 추적용 MDC 필터 (LogicExecutor 평탄화 완료)
 *
 * <h4>MDC 키</h4>
 *
 * <ul>
 *   <li>{@link #REQUEST_ID_KEY}: 요청 추적용 Correlation ID
 * </ul>
 *
 * <h4>비동기 전파</h4>
 *
 * <p>{@code ExecutorConfig.contextPropagatingDecorator()}가 이 MDC 값을 비동기 워커 스레드로 전파합니다.
 *
 * @see maple.expectation.config.ExecutorConfig#contextPropagatingDecorator()
 */
@Component
@RequiredArgsConstructor
public class MDCFilter implements Filter {

  private final LogicExecutor executor;

  /** HTTP 헤더 이름: X-Correlation-ID */
  public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

  /** MDC 키: requestId (비동기 전파 대상) */
  public static final String REQUEST_ID_KEY = "requestId";

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;

    // 1. Correlation ID 확보 및 설정 (비즈니스 로직 최상단 노출)
    String correlationId = resolveCorrelationId(httpRequest);
    setupMdcContext(correlationId, httpResponse);

    TaskContext context = TaskContext.of("Filter", "MDC", correlationId); //

    // ✅  try-finally를 executeWithFinally로 대체하여 평탄화
    executor.executeWithFinally(
        () -> {
          chain.doFilter(request, response);
          return null;
        },
        MDC::clear, // 요청 종료 시 반드시 비워줌 (메모리 누수 방지)
        context);
  }

  /** 외부 헤더 확인 후 없으면 생성 (로직 분리) */
  private String resolveCorrelationId(HttpServletRequest request) {
    String id = request.getHeader(CORRELATION_ID_HEADER);
    return (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
  }

  /** MDC 주입 및 응답 헤더 설정 */
  private void setupMdcContext(String correlationId, HttpServletResponse response) {
    MDC.put(REQUEST_ID_KEY, correlationId);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
  }
}
