package maple.expectation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.controller.dto.donation.SendCoffeeRequest;
import maple.expectation.controller.dto.donation.SendCoffeeResponse;
import maple.expectation.global.response.ApiResponse;
import maple.expectation.global.security.AuthenticatedUser;
import maple.expectation.service.v2.DonationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 도네이션(커피 후원) API 컨트롤러
 *
 * <p>게스트가 Admin(개발자)에게 커피를 사주는 기능입니다.</p>
 *
 * <p>API 목록:
 * <ul>
 *   <li>POST /api/v2/donation/coffee - Admin에게 커피 보내기</li>
 * </ul>
 * </p>
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
     * <p>인증된 사용자만 사용할 수 있으며, ADMIN_FINGERPRINTS에 등록된
     * Admin에게만 후원할 수 있습니다.</p>
     *
     * @param user    인증된 사용자 정보 (발신자)
     * @param request 후원 요청 (수신자 fingerprint, 금액)
     * @return 후원 결과
     */
    @PostMapping("/coffee")
    @Operation(summary = "커피 후원", description = "Admin(개발자)에게 커피를 후원합니다.")
    public ResponseEntity<ApiResponse<SendCoffeeResponse>> sendCoffee(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody SendCoffeeRequest request) {

        // 멱등성 키 생성 (클라이언트에서 제공하지 않으면 서버에서 생성)
        String requestId = UUID.randomUUID().toString();

        // 발신자는 인증된 사용자의 fingerprint를 UUID로 사용
        // (Member 테이블에 fingerprint가 uuid로 저장되어 있어야 함)
        String guestUuid = user.fingerprint();

        donationService.sendCoffee(
                guestUuid,
                request.adminFingerprint(),
                request.amount(),
                requestId
        );

        log.info("[Donation] Coffee sent successfully: requestId={}", requestId);

        return ResponseEntity.ok(
                ApiResponse.success(SendCoffeeResponse.success(requestId))
        );
    }
}
