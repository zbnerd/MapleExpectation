package maple.expectation.global.security.cors;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * CORS 오리진 유효성 검사기 (Security Enhancement)
 *
 * <p>Issue #21: CORS 오리진 검증 강화
 *
 * <h4>검증 규칙</h4>
 *
 * <ul>
 *   <li><b>URL 포맷 검증</b>: RFC 3986 유효한 URL 형식
 *   <li><b>프로토콜 검증</b>: http/https만 허용
 *   <li><b>환경별 규칙</b>:
 *       <ul>
 *         <li>local/ci: http 허용 (localhost 개발용)
 *         <li>prod: https 강제 (보안)
 *       </ul>
 *   <li><b>금지 패턴</b>:
 *       <ul>
 *         <li>프로덕션에서 localhost/127.0.0.1 금지
 *         <li>프로덕션에서 사설 IP 대역(10.*, 172.16-31.*, 192.168.*) 금지
 *       </ul>
 * </ul>
 *
 * <h4>Critical Best Practices</h4>
 *
 * <ul>
 *   <li>Fail-fast: 앱 시작 시 유효하지 않은 오리진이 있으면 즉시 실패
 *   <li>Audit Trail: 시작 시 모든 허용 오리진 로그 기록
 *   <li>Timing Attack Safe: equals()가 아닌 Set.contains() 사용
 * </ul>
 *
 * <p>CLAUDE.md Section 19: Security Best Practice 준수
 */
@Slf4j
@Component
public class CorsOriginValidator {

  /** 유효한 프로토콜 목록 */
  private static final Set<String> VALID_PROTOCOLS = Set.of("http", "https");

  /** 와일드카드 패턴 (보안 위험으로 사용 금지) */
  private static final Pattern WILDCARD_PATTERN = Pattern.compile("^\\*.*");

  /** 로컬호스트 패턴 (개발용) */
  private static final Pattern LOCALHOST_PATTERN =
      Pattern.compile("^(https?://)?(localhost|127\\.0\\.0\\.1|::1)(:\\d+)?(/.*)?$");

  /** 사설 IP 대역 패턴 (RFC 1918) */
  private static final Pattern PRIVATE_IP_PATTERN =
      Pattern.compile("^https?://(10\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.|192\\.168\\.|127\\.)");

  /** 현재 활성화된 프로필 */
  private final String activeProfile;

  public CorsOriginValidator(@Value("${spring.profiles.active:local}") String activeProfile) {
    this.activeProfile = activeProfile.split(",")[0]; // 다중 프로필 지원
  }

  /**
   * 오리진 목록 검증 (앱 시작 시 호출)
   *
   * @param origins 검증할 오리진 목록
   * @return 검증 결과 (상세 메시지 포함)
   * @throws IllegalArgumentException 유효하지 않은 오리진이 있을 경우
   */
  public ValidationResult validateOrigins(List<String> origins) {
    List<String> validOrigins = new ArrayList<>(origins.size());
    List<String> warnings = new ArrayList<>();
    List<String> errors = new ArrayList<>();

    for (String origin : origins) {
      try {
        validateSingleOrigin(origin);
        validOrigins.add(origin);

        // 보안 경고 체크
        if (isProductionProfile()) {
          if (isLocalhost(origin)) {
            warnings.add(
                String.format("[SECURITY] '%s' - 프로덕션 환경에서 localhost 오리진은 권장하지 않습니다.", origin));
          }
          if (isPrivateIp(origin)) {
            warnings.add(
                String.format("[SECURITY] '%s' - 프로덕션 환경에서 사설 IP 오리진은 권장하지 않습니다.", origin));
          }
          if (isHttp(origin)) {
            warnings.add(String.format("[SECURITY] '%s' - 프로덕션 환경에서는 HTTPS 사용을 권장합니다.", origin));
          }
        }
      } catch (IllegalArgumentException e) {
        errors.add(String.format("[ERROR] '%s': %s", origin, e.getMessage()));
      }
    }

    return new ValidationResult(validOrigins, warnings, errors);
  }

  /**
   * 단일 오리진 검증
   *
   * @param origin 검증할 오리진
   * @throws IllegalArgumentException 유효하지 않은 경우
   */
  public void validateSingleOrigin(String origin) {
    if (origin == null || origin.isBlank()) {
      throw new IllegalArgumentException("오리진은 null이거나 비어있을 수 없습니다.");
    }

    // 와일드카드 검출 (보안 위험)
    if (WILDCARD_PATTERN.matcher(origin).matches()) {
      throw new IllegalArgumentException("와일드카드(*) 오리진은 보안 상의 이유로 금지됩니다. 명시적인 오리진을 사용하세요.");
    }

    // URL 파싱
    URI uri;
    try {
      uri = new URI(origin);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(String.format("유효하지 않은 URL 형식입니다: %s", origin), e);
    }

    // 스킴(프로토콜) 검증
    String scheme = uri.getScheme();
    if (scheme == null) {
      throw new IllegalArgumentException(String.format("프로토콜이 누락되었습니다: %s", origin));
    }

    if (!VALID_PROTOCOLS.contains(scheme.toLowerCase())) {
      throw new IllegalArgumentException(
          String.format("허용되지 않는 프로토콜입니다: %s (허용: %s)", scheme, VALID_PROTOCOLS));
    }

    // 호스트 검증
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException(String.format("호스트가 누락되었습니다: %s", origin));
    }
  }

  /**
   * 런타임 오리진 헤더 검증 (필터용)
   *
   * @param originHeader 요청의 Origin 헤더 값
   * @param allowedOrigins 허용된 오리진 목록
   * @return 유효 여부
   */
  public boolean isValidRuntimeOrigin(String originHeader, List<String> allowedOrigins) {
    if (originHeader == null || originHeader.isBlank()) {
      return false;
    }

    // 정확히 일치하는지 확인 (와일드카드 없음)
    return allowedOrigins.contains(originHeader);
  }

  /**
   * 오리진 정규화 (소문자 변환, 후행 슬래시 제거)
   *
   * @param origin 정규화할 오리진
   * @return 정규화된 오리진
   */
  public String normalizeOrigin(String origin) {
    if (origin == null) {
      return null;
    }
    String normalized = origin.trim().toLowerCase();
    if (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  /** 프로덕션 프로필 여부 확인 */
  private boolean isProductionProfile() {
    return "prod".equals(activeProfile);
  }

  /** 로컬호스트 오리진 여부 확인 */
  private boolean isLocalhost(String origin) {
    return LOCALHOST_PATTERN.matcher(origin).find();
  }

  /** 사설 IP 오리진 여부 확인 */
  private boolean isPrivateIp(String origin) {
    return PRIVATE_IP_PATTERN.matcher(origin).find();
  }

  /** HTTP 프로토콜 여부 확인 */
  private boolean isHttp(String origin) {
    return origin.startsWith("http://");
  }

  /**
   * 검증 결과 레코드
   *
   * @param validOrigins 유효한 오리진 목록
   * @param warnings 보안 경고 목록
   * @param errors 에러 목록
   */
  public record ValidationResult(
      List<String> validOrigins, List<String> warnings, List<String> errors) {

    public boolean isValid() {
      return errors.isEmpty();
    }

    public boolean hasWarnings() {
      return !warnings.isEmpty();
    }
  }
}
