package maple.expectation.monitoring.security;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * PII 마스킹 필터 (Issue #251)
 *
 * <h3>[P0-Purple] 보안 요구사항</h3>
 * <p>AI 분석 전송 전 민감 정보를 마스킹합니다.</p>
 *
 * <h4>마스킹 대상</h4>
 * <ul>
 *   <li>이메일 주소</li>
 *   <li>IP 주소</li>
 *   <li>UUID (userId, requestId 등)</li>
 *   <li>API 키 패턴</li>
 *   <li>JWT 토큰</li>
 * </ul>
 *
 * @see maple.expectation.monitoring.ai.AiSreService
 */
@Component
public class PiiMaskingFilter {

    // 이메일 패턴: user@domain.com
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    // IPv4 패턴: 192.168.1.1
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    // UUID 패턴: 8-4-4-4-12 형식
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");

    // API 키 패턴 (일반적인 형식)
    private static final Pattern API_KEY_PATTERN =
            Pattern.compile("(?i)(api[_-]?key|apikey|secret|password|token)\\s*[:=]\\s*['\"]?([^'\"\\s]{8,})['\"]?");

    // JWT 토큰 패턴: eyJ로 시작하는 Base64 인코딩
    private static final Pattern JWT_PATTERN =
            Pattern.compile("eyJ[a-zA-Z0-9_-]*\\.[a-zA-Z0-9_-]*\\.[a-zA-Z0-9_-]*");

    // Bearer 토큰 패턴
    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(?i)bearer\\s+[a-zA-Z0-9._-]+");

    /**
     * 입력 문자열에서 PII를 마스킹합니다.
     *
     * @param input 원본 문자열
     * @return PII가 마스킹된 문자열
     */
    public String mask(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        String result = input;

        // 순서 중요: JWT/Bearer를 먼저 처리 (이메일보다 넓은 패턴)
        result = JWT_PATTERN.matcher(result).replaceAll("[JWT_MASKED]");
        result = BEARER_PATTERN.matcher(result).replaceAll("Bearer [TOKEN_MASKED]");
        result = API_KEY_PATTERN.matcher(result).replaceAll("$1: [REDACTED]");
        result = EMAIL_PATTERN.matcher(result).replaceAll("[EMAIL_MASKED]");
        result = IPV4_PATTERN.matcher(result).replaceAll("[IP_MASKED]");
        result = UUID_PATTERN.matcher(result).replaceAll("[UUID_MASKED]");

        return result;
    }

    /**
     * 스택 트레이스에서 PII를 마스킹합니다.
     *
     * @param stackTrace 원본 스택 트레이스
     * @return PII가 마스킹된 스택 트레이스
     */
    public String maskStackTrace(String stackTrace) {
        if (stackTrace == null) {
            return null;
        }

        // 기본 마스킹 적용
        String masked = mask(stackTrace);

        // 추가: 파일 경로에서 사용자 디렉토리 마스킹
        masked = masked.replaceAll("/home/[^/]+/", "/home/[USER]/");
        masked = masked.replaceAll("/Users/[^/]+/", "/Users/[USER]/");
        masked = masked.replaceAll("C:\\\\Users\\\\[^\\\\]+\\\\", "C:\\\\Users\\\\[USER]\\\\");

        return masked;
    }

    /**
     * Exception 메시지에서 PII를 마스킹합니다.
     *
     * @param exception 예외 객체
     * @return PII가 마스킹된 메시지
     */
    public String maskExceptionMessage(Throwable exception) {
        if (exception == null) {
            return "Unknown error";
        }

        String message = exception.getMessage();
        if (message == null) {
            return exception.getClass().getSimpleName();
        }

        return mask(message);
    }
}
