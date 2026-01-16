package maple.expectation.controller.dto.dlq;

import maple.expectation.domain.v2.DonationDlq;

import java.time.LocalDateTime;

/**
 * DLQ 상세 조회 응답 DTO
 *
 * <p>Admin 전용: 전체 payload 포함 (민감 데이터 주의)</p>
 *
 * @param id DLQ ID
 * @param originalOutboxId 원본 Outbox ID
 * @param requestId 멱등성 키
 * @param eventType 이벤트 타입
 * @param payload 전체 payload (JSON)
 * @param failureReason 실패 사유
 * @param movedAt DLQ 이동 시각
 */
public record DlqDetailResponse(
        Long id,
        Long originalOutboxId,
        String requestId,
        String eventType,
        String payload,
        String failureReason,
        LocalDateTime movedAt
) {
    public static DlqDetailResponse from(DonationDlq dlq) {
        return new DlqDetailResponse(
                dlq.getId(),
                dlq.getOriginalOutboxId(),
                dlq.getRequestId(),
                dlq.getEventType(),
                dlq.getPayload(),
                dlq.getFailureReason(),
                dlq.getMovedAt()
        );
    }
}
