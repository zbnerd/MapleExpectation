package maple.expectation.controller.dto.donation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 커피 후원 요청 DTO
 *
 * @param adminFingerprint 수신자 Admin fingerprint (로그인 응답에서 확인 가능)
 * @param amount 후원 금액 (양수)
 */
public record SendCoffeeRequest(
    @NotBlank(message = "Admin fingerprint는 필수입니다") String adminFingerprint,
    @NotNull(message = "금액은 필수입니다") @Positive(message = "금액은 양수여야 합니다") Long amount) {
  /** 보안: toString()에서 fingerprint 마스킹 */
  @Override
  public String toString() {
    String masked =
        (adminFingerprint != null && adminFingerprint.length() >= 8)
            ? adminFingerprint.substring(0, 4) + "****"
            : "****";
    return "SendCoffeeRequest[adminFingerprint=" + masked + ", amount=" + amount + "]";
  }
}
