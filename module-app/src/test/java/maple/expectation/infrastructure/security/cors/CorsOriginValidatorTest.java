package maple.expectation.infrastructure.security.cors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/**
 * CORS 오리진 검증기 단위 테스트 (Issue #21)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>Spring Context 없이 순수 자바로 CORS 오리진 검증 로직을 검증합니다.
 *
 * <h4>테스트 범위</h4>
 *
 * <ul>
 *   <li>URL 포맷 검증 (RFC 3986)
 *   <li>프로토콜 검증 (http/https)
 *   <li>와일드카드 금지
 *   <li>환경별 규칙 (local vs prod)
 *   <li>금지 패턴 검출 (localhost, 사설 IP)
 * </ul>
 */
@Tag("unit")
@DisplayName("CORS 오리진 검증기 테스트")
class CorsOriginValidatorTest {

  private CorsOriginValidator localValidator;
  private CorsOriginValidator prodValidator;

  @BeforeEach
  void setUp() {
    localValidator = new CorsOriginValidator("local");
    prodValidator = new CorsOriginValidator("prod");
  }

  @Nested
  @DisplayName("단일 오리진 검증")
  class SingleOriginValidationTest {

    @Test
    @DisplayName("유효한 HTTPS 오리진 검증 성공")
    void shouldValidateValidHttpsOrigin() {
      // given
      String origin = "https://example.com";

      // then - 예외 발생하지 않음
      localValidator.validateSingleOrigin(origin);
    }

    @Test
    @DisplayName("유효한 HTTP 오리진 검증 성공 (개발 환경)")
    void shouldValidateValidHttpOriginForLocal() {
      // given
      String origin = "http://localhost:3000";

      // then - 예외 발생하지 않음
      localValidator.validateSingleOrigin(origin);
    }

    @Test
    @DisplayName("포트가 포함된 오리진 검증 성공")
    void shouldValidateOriginWithPort() {
      // given
      String origin = "https://example.com:8443";

      // then - 예외 발생하지 않음
      localValidator.validateSingleOrigin(origin);
    }

    @Test
    @DisplayName("경로가 포함된 오리진 검증 성공")
    void shouldValidateOriginWithPath() {
      // given
      String origin = "https://example.com/api";

      // then - 예외 발생하지 않음
      localValidator.validateSingleOrigin(origin);
    }

    @Test
    @DisplayName("null 오리진 검증 실패")
    void shouldRejectNullOrigin() {
      // then
      assertThatThrownBy(() -> localValidator.validateSingleOrigin(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("오리진은 null이거나 비어있을 수 없습니다");
    }

    @Test
    @DisplayName("빈 문자열 오리진 검증 실패")
    void shouldRejectEmptyOrigin() {
      // then
      assertThatThrownBy(() -> localValidator.validateSingleOrigin(""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("오리진은 null이거나 비어있을 수 없습니다");
    }

    @Test
    @DisplayName("와일드카드 오리진 검증 실패")
    void shouldRejectWildcardOrigin() {
      // then
      assertThatThrownBy(() -> localValidator.validateSingleOrigin("*"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("와일드카드");

      assertThatThrownBy(() -> localValidator.validateSingleOrigin("*.example.com"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("와일드카드");
    }

    @Test
    @DisplayName("프로토콜 누락 시 검증 실패")
    void shouldRejectOriginWithoutProtocol() {
      // then
      assertThatThrownBy(() -> localValidator.validateSingleOrigin("example.com"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("프로토콜이 누락");
    }

    @Test
    @DisplayName("잘못된 프로토콜 검증 실패")
    void shouldRejectInvalidProtocol() {
      // then
      assertThatThrownBy(() -> localValidator.validateSingleOrigin("ftp://example.com"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("허용되지 않는 프로토콜");
    }

    @Test
    @DisplayName("호스트 누락 시 검증 실패")
    void shouldRejectOriginWithoutHost() {
      // then - URI 생성 시 URISyntaxException 발생, 이를 IllegalArgumentException로 래핑
      assertThatThrownBy(() -> localValidator.validateSingleOrigin("https://"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("유효하지 않은 URL 형식");
    }

    @Test
    @DisplayName("잘못된 URL 형식 검증 실패")
    void shouldRejectInvalidUrlFormat() {
      // then
      assertThatThrownBy(() -> localValidator.validateSingleOrigin("not a url"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("유효하지 않은 URL 형식");
    }
  }

  @Nested
  @DisplayName("오리진 목록 검증")
  class OriginListValidationTest {

    @Test
    @DisplayName("모두 유효한 오리진 목록 검증 성공")
    void shouldValidateAllValidOrigins() {
      // given
      List<String> origins =
          List.of(
              "https://example.com",
              "https://www.example.com",
              "http://localhost:3000",
              "http://127.0.0.1:8080");

      // when
      CorsOriginValidator.ValidationResult result = localValidator.validateOrigins(origins);

      // then
      assertThat(result.isValid()).isTrue();
      assertThat(result.validOrigins()).hasSize(4);
      assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("유효하지 않은 오리진 포함 시 검증 실패")
    void shouldRejectListWithInvalidOrigin() {
      // given
      List<String> origins =
          List.of("https://example.com", "invalid-origin", "https://www.example.com");

      // when
      CorsOriginValidator.ValidationResult result = localValidator.validateOrigins(origins);

      // then
      assertThat(result.isValid()).isFalse();
      assertThat(result.errors()).hasSize(1);
      assertThat(result.errors().get(0)).contains("invalid-origin");
    }

    @Test
    @DisplayName("빈 목록 검증 성공")
    void shouldValidateEmptyList() {
      // given
      List<String> origins = List.of();

      // when
      CorsOriginValidator.ValidationResult result = localValidator.validateOrigins(origins);

      // then
      assertThat(result.isValid()).isTrue();
      assertThat(result.validOrigins()).isEmpty();
    }
  }

  @Nested
  @DisplayName("프로덕션 환경 보안 규칙")
  @ActiveProfiles("prod")
  class ProductionSecurityRulesTest {

    @Test
    @DisplayName("프로덕션에서 localhost 오리진 시 경고 발생")
    void shouldWarnForLocalhostInProduction() {
      // given
      List<String> origins = List.of("https://example.com", "http://localhost:3000");

      // when
      CorsOriginValidator.ValidationResult result = prodValidator.validateOrigins(origins);

      // then
      assertThat(result.isValid()).isTrue();
      assertThat(result.hasWarnings()).isTrue();
      assertThat(result.warnings().get(0)).contains("localhost").contains("권장하지 않습니다");
    }

    @Test
    @DisplayName("프로덕션에서 127.0.0.1 오리진 시 경고 발생")
    void shouldWarnForLoopbackInProduction() {
      // given
      List<String> origins = List.of("https://example.com", "http://127.0.0.1:8080");

      // when
      CorsOriginValidator.ValidationResult result = prodValidator.validateOrigins(origins);

      // then
      assertThat(result.isValid()).isTrue();
      assertThat(result.hasWarnings()).isTrue();
      assertThat(result.warnings().get(0)).contains("localhost");
    }

    @Test
    @DisplayName("프로덕션에서 사설 IP 오리진 시 경고 발생")
    void shouldWarnForPrivateIpInProduction() {
      // given
      List<String> origins = List.of("https://example.com", "http://192.168.1.100:8080");

      // when
      CorsOriginValidator.ValidationResult result = prodValidator.validateOrigins(origins);

      // then
      assertThat(result.isValid()).isTrue();
      assertThat(result.hasWarnings()).isTrue();
      assertThat(result.warnings().get(0)).contains("사설 IP").contains("권장하지 않습니다");
    }

    @Test
    @DisplayName("프로덕션에서 HTTP 오리진 시 경고 발생")
    void shouldWarnForHttpInProduction() {
      // given
      List<String> origins = List.of("https://example.com", "http://example.com");

      // when
      CorsOriginValidator.ValidationResult result = prodValidator.validateOrigins(origins);

      // then
      assertThat(result.isValid()).isTrue();
      assertThat(result.hasWarnings()).isTrue();
      assertThat(result.warnings().get(0)).contains("HTTPS").contains("권장");
    }

    @Test
    @DisplayName("프로덕션에서 HTTPS만 사용 시 경고 없음")
    void shouldNotWarnForOnlyHttpsInProduction() {
      // given
      List<String> origins = List.of("https://example.com", "https://www.example.com");

      // when
      CorsOriginValidator.ValidationResult result = prodValidator.validateOrigins(origins);

      // then
      assertThat(result.isValid()).isTrue();
      assertThat(result.hasWarnings()).isFalse();
    }
  }

  @Nested
  @DisplayName("런타임 오리진 검증")
  class RuntimeOriginValidationTest {

    private static final List<String> ALLOWED_ORIGINS =
        List.of("https://example.com", "https://www.example.com", "http://localhost:3000");

    @Test
    @DisplayName("허용된 오리진 matches")
    void shouldAcceptAllowedOrigin() {
      // given
      String origin = "https://example.com";

      // when
      boolean isValid = localValidator.isValidRuntimeOrigin(origin, ALLOWED_ORIGINS);

      // then
      assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("허용되지 않은 오리진 rejected")
    void shouldRejectNotAllowedOrigin() {
      // given
      String origin = "https://evil.com";

      // when
      boolean isValid = localValidator.isValidRuntimeOrigin(origin, ALLOWED_ORIGINS);

      // then
      assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("null 오리진 rejected")
    void shouldRejectNullOrigin() {
      // when
      boolean isValid = localValidator.isValidRuntimeOrigin(null, ALLOWED_ORIGINS);

      // then
      assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("빈 문자열 오리진 rejected")
    void shouldRejectEmptyOrigin() {
      // when
      boolean isValid = localValidator.isValidRuntimeOrigin("", ALLOWED_ORIGINS);

      // then
      assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("대소문자 구분 (exact match)")
    void beCaseSensitive() {
      // given
      String origin = "https://Example.com";

      // when
      boolean isValid = localValidator.isValidRuntimeOrigin(origin, ALLOWED_ORIGINS);

      // then - 정확히 일치해야 함 (대소문자 구분)
      assertThat(isValid).isFalse();
    }
  }

  @Nested
  @DisplayName("오리진 정규화")
  class OriginNormalizationTest {

    @Test
    @DisplayName("후행 슬래시 제거")
    void shouldRemoveTrailingSlash() {
      // given
      String origin = "https://example.com/";

      // when
      String normalized = localValidator.normalizeOrigin(origin);

      // then
      assertThat(normalized).isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("대문자를 소문자로 변환")
    void shouldConvertToLowerCase() {
      // given
      String origin = "https://Example.Com";

      // when
      String normalized = localValidator.normalizeOrigin(origin);

      // then
      assertThat(normalized).isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("공백 제거")
    void shouldTrimWhitespace() {
      // given
      String origin = "  https://example.com  ";

      // when
      String normalized = localValidator.normalizeOrigin(origin);

      // then
      assertThat(normalized).isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("null 입력 시 null 반환")
    void shouldReturnNullForNullInput() {
      // when
      String normalized = localValidator.normalizeOrigin(null);

      // then
      assertThat(normalized).isNull();
    }
  }
}
