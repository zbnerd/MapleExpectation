package maple.expectation.monitoring;

import maple.expectation.monitoring.security.PiiMaskingFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII 마스킹 필터 테스트 (Issue #251)
 *
 * <p>[P0-Purple] PII 마스킹이 올바르게 동작하는지 검증</p>
 */
@DisplayName("PiiMaskingFilter 테스트")
class PiiMaskingFilterTest {

    private final PiiMaskingFilter filter = new PiiMaskingFilter();

    @Test
    @DisplayName("이메일 주소가 마스킹되어야 한다")
    void shouldMaskEmail() {
        // Given
        String input = "User email is user@example.com and admin@company.co.kr";

        // When
        String result = filter.mask(input);

        // Then
        assertThat(result)
                .doesNotContain("user@example.com")
                .doesNotContain("admin@company.co.kr")
                .contains("[EMAIL_MASKED]");
    }

    @Test
    @DisplayName("IP 주소가 마스킹되어야 한다")
    void shouldMaskIpAddress() {
        // Given
        String input = "Client IP: 192.168.1.100, Server: 10.0.0.1";

        // When
        String result = filter.mask(input);

        // Then
        assertThat(result)
                .doesNotContain("192.168.1.100")
                .doesNotContain("10.0.0.1")
                .contains("[IP_MASKED]");
    }

    @Test
    @DisplayName("UUID가 마스킹되어야 한다")
    void shouldMaskUuid() {
        // Given
        String input = "RequestId: 550e8400-e29b-41d4-a716-446655440000";

        // When
        String result = filter.mask(input);

        // Then
        assertThat(result)
                .doesNotContain("550e8400-e29b-41d4-a716-446655440000")
                .contains("[UUID_MASKED]");
    }

    @Test
    @DisplayName("JWT 토큰이 마스킹되어야 한다")
    void shouldMaskJwtToken() {
        // Given
        String input = "Authorization: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";

        // When
        String result = filter.mask(input);

        // Then
        assertThat(result)
                .contains("[JWT_MASKED]")
                .doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
    }

    @Test
    @DisplayName("Bearer 토큰이 마스킹되어야 한다")
    void shouldMaskBearerToken() {
        // Given
        String input = "Header: Bearer abc123def456.xyz789";

        // When
        String result = filter.mask(input);

        // Then
        assertThat(result)
                .contains("[TOKEN_MASKED]")
                .doesNotContain("abc123def456");
    }

    @Test
    @DisplayName("API 키가 마스킹되어야 한다")
    void shouldMaskApiKey() {
        // Given
        String input = "api_key: api_key_abcd1234efgh5678ijkl, secret=my_super_secret_value";

        // When
        String result = filter.mask(input);

        // Then
        assertThat(result)
                .contains("[REDACTED]")
                .doesNotContain("api_key_abcd1234efgh5678ijkl")
                .doesNotContain("my_super_secret_value");
    }

    @Test
    @DisplayName("null 입력은 null을 반환해야 한다")
    void shouldReturnNullForNullInput() {
        // When
        String result = filter.mask(null);

        // Then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("빈 문자열은 그대로 반환해야 한다")
    void shouldReturnEmptyForEmptyInput() {
        // When
        String result = filter.mask("");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("스택 트레이스의 사용자 디렉토리가 마스킹되어야 한다")
    void shouldMaskUserDirectoryInStackTrace() {
        // Given
        String stackTrace = "at com.example.MyClass.method(MyClass.java:42)\n" +
                "at /home/john/projects/app/Main.java:10\n" +
                "at /Users/jane/workspace/Service.java:20";

        // When
        String result = filter.maskStackTrace(stackTrace);

        // Then
        assertThat(result)
                .contains("/home/[USER]/")
                .contains("/Users/[USER]/")
                .doesNotContain("/home/john/")
                .doesNotContain("/Users/jane/");
    }

    @Test
    @DisplayName("예외 메시지가 올바르게 마스킹되어야 한다")
    void shouldMaskExceptionMessage() {
        // Given
        RuntimeException exception = new RuntimeException(
                "Connection failed for user@example.com from 192.168.1.1"
        );

        // When
        String result = filter.maskExceptionMessage(exception);

        // Then
        assertThat(result)
                .contains("[EMAIL_MASKED]")
                .contains("[IP_MASKED]")
                .doesNotContain("user@example.com")
                .doesNotContain("192.168.1.1");
    }

    @Test
    @DisplayName("메시지가 없는 예외는 클래스 이름을 반환해야 한다")
    void shouldReturnClassNameForNullMessage() {
        // Given
        RuntimeException exception = new RuntimeException();

        // When
        String result = filter.maskExceptionMessage(exception);

        // Then
        assertThat(result).isEqualTo("RuntimeException");
    }
}
