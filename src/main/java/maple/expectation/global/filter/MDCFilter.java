package maple.expectation.global.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import maple.expectation.global.executor.LogicExecutor; // ✅ 주입
import maple.expectation.global.executor.TaskContext; // ✅ 관측성 확보
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 로그 추적용 MDC 필터 (LogicExecutor 평탄화 완료)
 */
@Component
@RequiredArgsConstructor // ✅ 생성자 주입 추가
public class MDCFilter implements Filter {

    private final LogicExecutor executor; // ✅ 지능형 실행기 주입

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String REQUEST_ID_MDC_KEY = "requestId";

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
                context
        );
    }

    /**
     * 외부 헤더 확인 후 없으면 생성 (로직 분리)
     */
    private String resolveCorrelationId(HttpServletRequest request) {
        String id = request.getHeader(CORRELATION_ID_HEADER);
        return (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
    }

    /**
     * MDC 주입 및 응답 헤더 설정
     */
    private void setupMdcContext(String correlationId, HttpServletResponse response) {
        MDC.put(REQUEST_ID_MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
    }
}