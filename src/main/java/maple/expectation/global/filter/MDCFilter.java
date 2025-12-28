package maple.expectation.global.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class MDCFilter implements Filter {

    // 표준적으로 많이 사용되는 Correlation ID 헤더 명칭들
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String REQUEST_ID_MDC_KEY = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 1. 외부(로드밸런서, 게이트웨이 등)에서 전달된 ID가 있는지 확인
        String correlationId = httpRequest.getHeader(CORRELATION_ID_HEADER);

        // 2. 없다면 새롭게 생성 (기존 로직 유지)
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        // 3. 로그 추적을 위해 MDC에 삽입
        MDC.put(REQUEST_ID_MDC_KEY, correlationId);

        // 4. 클라이언트나 다음 서비스가 알 수 있도록 응답 헤더에도 추가
        httpResponse.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            // 요청이 끝나면 반드시 비워줌 (스레드 풀 환경 메모리 누수 방지)
            MDC.clear();
        }
    }
}