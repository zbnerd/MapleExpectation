package maple.expectation.global.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class MDCFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        // 1. 8자리 짧은 UUID 생성 (로그 가독성을 위해 단축)
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        
        // 2. MDC(ThreadLocal 기반)에 requestId 주입
        MDC.put(REQUEST_ID, requestId);

        try {
            // 3. 다음 필터 혹은 서블릿(컨트롤러)으로 요청 전달
            filterChain.doFilter(request, response);
        } finally {
            // 4. [중요] ThreadLocal 자원 누수 방지를 위해 요청 완료 후 반드시 제거
            // 제거하지 않으면 스레드 풀의 다른 요청에서 이전 ID가 보일 수 있음
            MDC.remove(REQUEST_ID);
        }
    }
}