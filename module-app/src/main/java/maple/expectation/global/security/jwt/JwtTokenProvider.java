package maple.expectation.global.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * JWT 토큰 생성 및 검증을 담당하는 Provider
 *
 * <p>JJWT 0.12.x Best Practice 적용:
 *
 * <ul>
 *   <li>HS256 알고리즘 사용
 *   <li>parseSignedClaims() 사용 (deprecated된 parseClaimsJws 대체)
 *   <li>프로덕션 환경에서 기본 secret 사용 시 시작 거부
 * </ul>
 */
@Slf4j
@Component
public class JwtTokenProvider {

  private static final String ISSUER = "maple-expectation";
  private static final String CLAIM_FINGERPRINT = "fgp";
  private static final String CLAIM_ROLE = "role";
  private static final String DEFAULT_SECRET_PREFIX = "dev-secret";

  private final String secret;
  private final long expirationSeconds;
  private final Environment environment;
  private final LogicExecutor executor;

  private SecretKey secretKey;

  public JwtTokenProvider(
      @Value("${auth.jwt.secret}") String secret,
      @Value("${auth.jwt.expiration}") long expirationSeconds,
      Environment environment,
      LogicExecutor executor) {
    this.secret = secret;
    this.expirationSeconds = expirationSeconds;
    this.environment = environment;
    this.executor = executor;
  }

  @PostConstruct
  public void init() {
    validateSecretKeyForProduction();
    this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    log.info("JWT TokenProvider initialized with expiration: {}s", expirationSeconds);
  }

  /** 프로덕션 환경에서 기본 secret 사용 시 애플리케이션 시작을 거부합니다. */
  private void validateSecretKeyForProduction() {
    boolean isProduction = Arrays.asList(environment.getActiveProfiles()).contains("prod");
    boolean isDefaultSecret = secret.startsWith(DEFAULT_SECRET_PREFIX);

    if (isProduction && isDefaultSecret) {
      throw new IllegalStateException(
          "JWT_SECRET must be set in production environment. "
              + "Default development secret is not allowed in production.");
    }

    if (secret.length() < 32) {
      throw new IllegalStateException(
          "JWT secret must be at least 32 characters for HS256 algorithm");
    }
  }

  /**
   * JWT 토큰을 생성합니다.
   *
   * @param payload 토큰에 담을 페이로드
   * @return 생성된 JWT 토큰 문자열
   */
  public String generateToken(JwtPayload payload) {
    return Jwts.builder()
        .issuer(ISSUER)
        .subject(payload.sessionId())
        .claim(CLAIM_FINGERPRINT, payload.fingerprint())
        .claim(CLAIM_ROLE, payload.role())
        .issuedAt(Date.from(payload.issuedAt()))
        .expiration(Date.from(payload.expiration()))
        .signWith(secretKey, Jwts.SIG.HS256)
        .compact();
  }

  /**
   * 세션 ID, fingerprint, role로 토큰을 생성합니다.
   *
   * @param sessionId 세션 ID
   * @param fingerprint fingerprint
   * @param role 권한
   * @return 생성된 JWT 토큰 문자열
   */
  public String generateToken(String sessionId, String fingerprint, String role) {
    JwtPayload payload = JwtPayload.of(sessionId, fingerprint, role, expirationSeconds);
    return generateToken(payload);
  }

  /**
   * JWT 토큰을 파싱하여 페이로드를 추출합니다. (CLAUDE.md Section 12 준수: LogicExecutor 패턴)
   *
   * @param token JWT 토큰 문자열
   * @return 파싱된 JwtPayload (Optional)
   */
  public Optional<JwtPayload> parseToken(String token) {
    return executor.executeOrDefault(
        () -> parseTokenInternal(token),
        Optional.empty(),
        TaskContext.of("JWT", "ParseToken", maskToken(token)));
  }

  private Optional<JwtPayload> parseTokenInternal(String token) {
    Jws<Claims> jws = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token);

    Claims claims = jws.getPayload();

    JwtPayload payload =
        new JwtPayload(
            claims.getSubject(),
            claims.get(CLAIM_FINGERPRINT, String.class),
            claims.get(CLAIM_ROLE, String.class),
            claims.getIssuedAt().toInstant(),
            claims.getExpiration().toInstant());

    return Optional.of(payload);
  }

  private String maskToken(String token) {
    if (token == null || token.length() < 10) {
      return "***";
    }
    return token.substring(0, 6) + "...";
  }

  /**
   * JWT 토큰의 유효성을 검증합니다.
   *
   * @param token JWT 토큰 문자열
   * @return 유효 여부
   */
  public boolean validateToken(String token) {
    return parseToken(token).isPresent();
  }

  /**
   * 토큰의 기본 만료 시간(초)을 반환합니다.
   *
   * @return 만료 시간 (초)
   */
  public long getExpirationSeconds() {
    return expirationSeconds;
  }
}
