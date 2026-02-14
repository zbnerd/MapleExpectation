package maple.expectation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.controller.dto.donation.SendCoffeeRequest;
import maple.expectation.controller.dto.donation.SendCoffeeResponse;
import maple.expectation.infrastructure.security.AuthenticatedUser;
import maple.expectation.response.ApiResponse;
import maple.expectation.service.v2.DonationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 도네이션(커피 후원) API 컨트롤러
 *
 * <p>게스트가 Admin(개발자)에게 커피를 사주는 기능입니다.
 *
 * <p>API 목록:
 *
 * <ul>
 *   <li>POST /api/v2/donation/coffee - Admin에게 커피 보내기
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/donation")
@RequiredArgsConstructor
@Tag(name = "Donation", description = "도네이션(커피 후원) API")
public class DonationController {

  private final DonationService donationService;

  /**
   * Admin(개발자)에게 커피 보내기
   *
   * <p>인증된 사용자만 사용할 수 있으며, ADMIN_FINGERPRINTS에 등록된 Admin에게만 후원할 수 있습니다.
   *
   * <h4>PR #189 Fix: Idempotency-Key 헤더 지원</h4>
   *
   * <p>클라이언트가 {@code Idempotency-Key} 헤더를 제공하면 해당 값을 requestId로 사용하여 동일 요청의 중복 처리를 방지합니다.
   *
   * @param user 인증된 사용자 정보 (발신자)
   * @param request 후원 요청 (수신자 fingerprint, 금액)
   * @param idempotencyKey 멱등성 보장을 위한 클라이언트 제공 키 (선택)
   * @return 후원 결과
   */
  @PostMapping("/coffee")
  @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
  @Operation(summary = "커피 후원", description = "Admin(개발자)에게 커피를 후원합니다.")
  public CompletableFuture<ResponseEntity<ApiResponse<SendCoffeeResponse>>> sendCoffee(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody SendCoffeeRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

    // PR #189: 멱등성 키 우선 사용 (없으면 서버에서 생성)
    String requestId =
        (idempotencyKey != null && !idempotencyKey.isBlank())
            ? idempotencyKey
            : UUID.randomUUID().toString();

    // 발신자는 인증된 사용자의 fingerprint를 UUID로 사용
    // (Member 테이블에 fingerprint가 uuid로 저장되어 있어야 함)
    String guestUuid = user.fingerprint();

    return CompletableFuture.runAsync(
            () ->
                donationService.sendCoffee(
                    guestUuid, request.adminFingerprint(), request.amount(), requestId))
        .thenApply(
            unused -> {
              log.info("[Donation] Coffee sent successfully: requestId={}", requestId);
              return ResponseEntity.ok(ApiResponse.success(SendCoffeeResponse.success(requestId)));
            });
  }
}
