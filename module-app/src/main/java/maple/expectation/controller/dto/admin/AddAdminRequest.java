package maple.expectation.controller.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Admin 추가 요청 DTO
 *
 * <h4>Issue #151: Bean Validation 적용</h4>
 *
 * <ul>
 *   <li>@NotBlank: 빈 문자열 및 null 방지
 *   <li>@Size: 정확히 64자 검증 (SHA-256 hex digest)
 *   <li>@Pattern: SQL Injection/XSS 패턴 차단 (16진수만 허용)
 * </ul>
 *
 * <h4>5-Agent Council Round 2 결정</h4>
 *
 * <ul>
 *   <li><b>Blue Agent</b>: SRP 준수 - Controller 내부에서 분리
 *   <li><b>Purple Agent</b>: toString() 마스킹으로 PII 보호
 *   <li><b>Yellow Agent</b>: 경계값 테스트 (63자, 65자, 비hex) 필수
 * </ul>
 *
 * <p>CLAUDE.md 섹션 19 준수: toString() 마스킹 필수
 */
public record AddAdminRequest(
    @NotBlank(message = "fingerprint는 필수입니다")
        @Size(min = 64, max = 64, message = "fingerprint는 64자여야 합니다")
        @Pattern(regexp = "^[a-fA-F0-9]+$", message = "fingerprint는 16진수만 허용됩니다")
        String fingerprint) {
  /**
   * 마스킹된 fingerprint 반환
   *
   * <p>로그 및 응답 메시지에서 사용
   *
   * @return 앞 4자리 + **** + 뒤 4자리 형식 (예: "abcd****efgh")
   */
  public String maskedFingerprint() {
    if (fingerprint == null || fingerprint.length() < 8) {
      return "****";
    }
    return fingerprint.substring(0, 4) + "****" + fingerprint.substring(fingerprint.length() - 4);
  }

  /**
   * 민감정보 마스킹된 toString
   *
   * <p>CRITICAL: TraceAspect 로깅 시 fingerprint 노출 방지
   *
   * <p>Java Record의 기본 toString()은 모든 필드를 노출하므로 오버라이드 필수
   */
  @Override
  public String toString() {
    return "AddAdminRequest[fingerprint=" + maskedFingerprint() + "]";
  }
}
