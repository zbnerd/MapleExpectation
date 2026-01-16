package maple.expectation.controller.dto.donation;

/**
 * 커피 후원 응답 DTO
 *
 * @param requestId 멱등성 키 (중복 요청 방지용)
 * @param message   성공 메시지
 */
public record SendCoffeeResponse(
        String requestId,
        String message
) {
    public static SendCoffeeResponse success(String requestId) {
        return new SendCoffeeResponse(requestId, "커피 후원이 완료되었습니다.");
    }
}
