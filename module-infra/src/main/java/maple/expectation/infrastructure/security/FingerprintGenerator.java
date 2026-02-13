package maple.expectation.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 기반 API Key Fingerprint 생성기
 *
 * <p>동일한 API Key는 항상 동일한 fingerprint를 생성하여 API Key를 저장하지 않고도 계정을 식별할 수 있게 합니다.
 *
 * <p>보안 고려사항:
 *
 * <ul>
 *   <li>fingerprint 비교 시 Timing Attack 방지를 위해 상수 시간 비교 사용
 *   <li>serverSecret은 환경변수로 주입받아 하드코딩 방지
 * </ul>
 */
@Slf4j
@Component
public class FingerprintGenerator {

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final byte[] serverSecretBytes;
  private final LogicExecutor executor;

  public FingerprintGenerator(
      @Value("${auth.fingerprint.secret}") String serverSecret, LogicExecutor executor) {
    Objects.requireNonNull(serverSecret, "auth.fingerprint.secret must not be null");
    this.serverSecretBytes = serverSecret.getBytes(StandardCharsets.UTF_8);
    this.executor = executor;
  }

  /**
   * API Key로부터 fingerprint를 생성합니다.
   *
   * @param apiKey Nexon API Key
   * @return Base64 URL-safe 인코딩된 fingerprint
   * @throws IllegalArgumentException apiKey가 null 또는 blank인 경우
   * @throws IllegalStateException HMAC 생성 실패 시
   */
  public String generate(String apiKey) {
    validateApiKey(apiKey);

    byte[] hash = computeHmac(apiKey);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }

  /**
   * API Key와 fingerprint가 일치하는지 검증합니다. Timing Attack 방지를 위해 상수 시간 비교를 사용합니다.
   *
   * @param apiKey 검증할 API Key
   * @param fingerprint 비교 대상 fingerprint
   * @return 일치 여부
   */
  public boolean verify(String apiKey, String fingerprint) {
    if (apiKey == null || fingerprint == null) {
      return false;
    }

    String computed = generate(apiKey);

    // Timing Attack 방지: 상수 시간 비교
    return MessageDigest.isEqual(
        computed.getBytes(StandardCharsets.UTF_8), fingerprint.getBytes(StandardCharsets.UTF_8));
  }

  private void validateApiKey(String apiKey) {
    Objects.requireNonNull(apiKey, "apiKey must not be null");
    if (apiKey.isBlank()) {
      throw new IllegalArgumentException("apiKey must not be blank");
    }
  }

  /** HMAC 계산 (CLAUDE.md Section 12 준수: LogicExecutor 패턴) */
  private byte[] computeHmac(String apiKey) {
    TaskContext context = TaskContext.of("Fingerprint", "ComputeHmac", "***");

    return executor.execute(
        () -> {
          Mac mac = Mac.getInstance(HMAC_ALGORITHM);
          mac.init(new SecretKeySpec(serverSecretBytes, HMAC_ALGORITHM));
          return mac.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
        },
        context);
  }
}
