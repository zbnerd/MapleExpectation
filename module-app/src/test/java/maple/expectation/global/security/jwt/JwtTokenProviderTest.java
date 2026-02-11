package maple.expectation.global.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * JwtTokenProvider 단위 테스트 (Issue #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>JWT 토큰 생성/검증 로직을 순수 단위 테스트로 검증합니다.
 *
 * <h4>테스트 범위</h4>
 *
 * <ul>
 *   <li>토큰 생성 (generateToken)
 *   <li>토큰 파싱 (parseToken)
 *   <li>토큰 검증 (validateToken)
 *   <li>프로덕션 환경 보안 검증
 *   <li>Secret Key 길이 검증
 * </ul>
 */
@Tag("unit")
class JwtTokenProviderTest {

  private static final String VALID_SECRET = "test-secret-key-for-jwt-testing-32chars";
  private static final long EXPIRATION_SECONDS = 3600L;

  private Environment environment;
  private LogicExecutor executor;
  private JwtTokenProvider tokenProvider;

  @BeforeEach
  void setUp() {
    environment = mock(Environment.class);
    executor = createMockLogicExecutor();
    given(environment.getActiveProfiles()).willReturn(new String[] {"test"});

    tokenProvider = new JwtTokenProvider(VALID_SECRET, EXPIRATION_SECONDS, environment, executor);
    tokenProvider.init();
  }

  @Nested
  @DisplayName("토큰 생성 generateToken")
  class GenerateTokenTest {

    @Test
    @DisplayName("JwtPayload로 토큰 생성 성공")
    void whenValidPayload_shouldGenerateToken() {
      // given
      JwtPayload payload = JwtPayload.of("session-123", "fingerprint-abc", "USER", 3600L);

      // when
      String token = tokenProvider.generateToken(payload);

      // then
      assertThat(token).isNotBlank();
      assertThat(token.split("\\.")).hasSize(3); // JWT는 3개의 부분으로 구성
    }

    @Test
    @DisplayName("sessionId, fingerprint, role로 토큰 생성 성공")
    void whenValidParams_shouldGenerateToken() {
      // given
      String sessionId = "session-456";
      String fingerprint = "fingerprint-def";
      String role = "ADMIN";

      // when
      String token = tokenProvider.generateToken(sessionId, fingerprint, role);

      // then
      assertThat(token).isNotBlank();
      assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("생성된 토큰이 파싱 가능")
    void generatedToken_shouldBeParseable() {
      // given
      String sessionId = "session-789";
      String fingerprint = "fingerprint-ghi";
      String role = "USER";
      String token = tokenProvider.generateToken(sessionId, fingerprint, role);

      // when
      Optional<JwtPayload> parsed = tokenProvider.parseToken(token);

      // then
      assertThat(parsed).isPresent();
      assertThat(parsed.get().sessionId()).isEqualTo(sessionId);
      assertThat(parsed.get().fingerprint()).isEqualTo(fingerprint);
      assertThat(parsed.get().role()).isEqualTo(role);
    }
  }

  @Nested
  @DisplayName("토큰 파싱 parseToken")
  class ParseTokenTest {

    @Test
    @DisplayName("유효한 토큰 파싱 성공")
    void whenValidToken_shouldParseSuccessfully() {
      // given
      JwtPayload originalPayload = JwtPayload.of("session-abc", "fp-123", "USER", 3600L);
      String token = tokenProvider.generateToken(originalPayload);

      // when
      Optional<JwtPayload> result = tokenProvider.parseToken(token);

      // then
      assertThat(result).isPresent();
      JwtPayload parsed = result.get();
      assertThat(parsed.sessionId()).isEqualTo("session-abc");
      assertThat(parsed.fingerprint()).isEqualTo("fp-123");
      assertThat(parsed.role()).isEqualTo("USER");
    }

    @Test
    @DisplayName("잘못된 형식의 토큰은 빈 Optional 반환")
    void whenInvalidToken_shouldReturnEmpty() {
      // given
      String invalidToken = "invalid.token.format";

      // when
      Optional<JwtPayload> result = tokenProvider.parseToken(invalidToken);

      // then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("null 토큰은 빈 Optional 반환")
    void whenNullToken_shouldReturnEmpty() {
      // when
      Optional<JwtPayload> result = tokenProvider.parseToken(null);

      // then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("빈 문자열 토큰은 빈 Optional 반환")
    void whenEmptyToken_shouldReturnEmpty() {
      // when
      Optional<JwtPayload> result = tokenProvider.parseToken("");

      // then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("토큰 검증 validateToken")
  class ValidateTokenTest {

    @Test
    @DisplayName("유효한 토큰 검증 성공")
    void whenValidToken_shouldReturnTrue() {
      // given
      String token = tokenProvider.generateToken("session", "fp", "USER");

      // when
      boolean isValid = tokenProvider.validateToken(token);

      // then
      assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("잘못된 토큰 검증 실패")
    void whenInvalidToken_shouldReturnFalse() {
      // given
      String invalidToken = "completely.invalid.token";

      // when
      boolean isValid = tokenProvider.validateToken(invalidToken);

      // then
      assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("변조된 토큰 검증 실패")
    void whenTamperedToken_shouldReturnFalse() {
      // given
      String token = tokenProvider.generateToken("session", "fp", "USER");
      String tamperedToken = token.substring(0, token.length() - 5) + "XXXXX";

      // when
      boolean isValid = tokenProvider.validateToken(tamperedToken);

      // then
      assertThat(isValid).isFalse();
    }
  }

  @Nested
  @DisplayName("보안 검증")
  class SecurityValidationTest {

    @Test
    @DisplayName("프로덕션 환경에서 기본 secret 사용 시 예외 발생")
    void whenProductionWithDefaultSecret_shouldThrowException() {
      // given
      Environment prodEnv = mock(Environment.class);
      given(prodEnv.getActiveProfiles()).willReturn(new String[] {"prod"});
      String defaultSecret = "dev-secret-key-for-testing-32char";

      // when & then
      assertThatThrownBy(
              () -> {
                JwtTokenProvider prodProvider =
                    new JwtTokenProvider(defaultSecret, EXPIRATION_SECONDS, prodEnv, executor);
                prodProvider.init();
              })
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("production");
    }

    @Test
    @DisplayName("32자 미만 secret 사용 시 예외 발생")
    void whenShortSecret_shouldThrowException() {
      // given
      String shortSecret = "short-secret-only-20-chars";

      // when & then
      assertThatThrownBy(
              () -> {
                JwtTokenProvider shortKeyProvider =
                    new JwtTokenProvider(shortSecret, EXPIRATION_SECONDS, environment, executor);
                shortKeyProvider.init();
              })
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("32 characters");
    }

    @Test
    @DisplayName("개발 환경에서는 기본 secret 허용")
    void whenDevWithDefaultSecret_shouldNotThrowException() {
      // given
      Environment devEnv = mock(Environment.class);
      given(devEnv.getActiveProfiles()).willReturn(new String[] {"local"});
      String defaultSecret = "dev-secret-key-for-testing-32-ch";

      // when & then - 예외 없이 초기화
      JwtTokenProvider devProvider =
          new JwtTokenProvider(defaultSecret, EXPIRATION_SECONDS, devEnv, executor);
      devProvider.init();

      assertThat(devProvider.getExpirationSeconds()).isEqualTo(EXPIRATION_SECONDS);
    }

    @Test
    @DisplayName("환경변수 placeholder 포함 시 예외 발생 (Issue #19)")
    void whenSecretContainsPlaceholder_shouldThrowException() {
      // given - 환경변수가 설정되지 않아 placeholder가 그대로 남은 경우
      String placeholderSecret = "${JWT_SECRET}";

      // when & then
      assertThatThrownBy(
              () -> {
                JwtTokenProvider placeholderProvider =
                    new JwtTokenProvider(
                        placeholderSecret, EXPIRATION_SECONDS, environment, executor);
                placeholderProvider.init();
              })
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("environment variable is not set")
          .hasMessageContaining("placeholder");
    }

    @Test
    @DisplayName("빈 secret 값 시 예외 발생 (Issue #19)")
    void whenSecretIsEmpty_shouldThrowException() {
      // given
      String emptySecret = "";

      // when & then
      assertThatThrownBy(
              () -> {
                JwtTokenProvider emptyProvider =
                    new JwtTokenProvider(emptySecret, EXPIRATION_SECONDS, environment, executor);
                emptyProvider.init();
              })
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not be null or blank");
    }

    @Test
    @DisplayName("null secret 값 시 예외 발생 (Issue #19)")
    void whenSecretIsNull_shouldThrowException() {
      // given
      String nullSecret = null;

      // when & then
      assertThatThrownBy(
              () -> {
                JwtTokenProvider nullProvider =
                    new JwtTokenProvider(nullSecret, EXPIRATION_SECONDS, environment, executor);
                nullProvider.init();
              })
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not be null or blank");
    }

    @Test
    @DisplayName("공백만 있는 secret 값 시 예외 발생 (Issue #19)")
    void whenSecretIsBlank_shouldThrowException() {
      // given
      String blankSecret = "   ";

      // when & then
      assertThatThrownBy(
              () -> {
                JwtTokenProvider blankProvider =
                    new JwtTokenProvider(blankSecret, EXPIRATION_SECONDS, environment, executor);
                blankProvider.init();
              })
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not be null or blank");
    }
  }

  @Nested
  @DisplayName("만료 시간")
  class ExpirationTest {

    @Test
    @DisplayName("만료 시간 반환")
    void shouldReturnExpirationSeconds() {
      // when
      long expiration = tokenProvider.getExpirationSeconds();

      // then
      assertThat(expiration).isEqualTo(EXPIRATION_SECONDS);
    }

    @Test
    @DisplayName("생성된 토큰의 만료 시간 검증")
    void generatedToken_shouldHaveCorrectExpiration() {
      // given
      // JWT 만료 시간은 초 단위로 저장되므로 초 단위로 truncate
      Instant beforeGeneration = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
      String token = tokenProvider.generateToken("session", "fp", "USER");
      Instant afterGeneration =
          Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

      // when
      Optional<JwtPayload> parsed = tokenProvider.parseToken(token);

      // then
      assertThat(parsed).isPresent();
      Instant expiration = parsed.get().expiration();

      // 만료 시간이 현재 + EXPIRATION_SECONDS 범위 내 (1초 여유)
      Instant expectedMinExpiration = beforeGeneration.plusSeconds(EXPIRATION_SECONDS);
      Instant expectedMaxExpiration = afterGeneration.plusSeconds(EXPIRATION_SECONDS);

      assertThat(expiration).isAfterOrEqualTo(expectedMinExpiration);
      assertThat(expiration).isBeforeOrEqualTo(expectedMaxExpiration);
    }
  }

  // ==================== Helper Methods ====================

  /** LogicExecutor Mock 생성 executeOrDefault 호출 시 실제 작업을 실행하도록 설정 */
  @SuppressWarnings("unchecked")
  private LogicExecutor createMockLogicExecutor() {
    LogicExecutor mockExecutor = mock(LogicExecutor.class);

    // executeOrDefault: 실제 작업 실행
    given(mockExecutor.executeOrDefault(any(ThrowingSupplier.class), any(), any(TaskContext.class)))
        .willAnswer(
            invocation -> {
              ThrowingSupplier<?> task = invocation.getArgument(0);
              Object defaultValue = invocation.getArgument(1);
              try {
                return task.get();
              } catch (Throwable e) {
                return defaultValue;
              }
            });

    return mockExecutor;
  }
}
