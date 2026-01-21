package maple.expectation.controller.dto.dlq;

import maple.expectation.domain.v2.DonationDlq;

import java.time.LocalDateTime;

/**
 * DLQ 조회 응답 DTO
 *
 * <p>CLAUDE.md 19 준수: payload 마스킹 처리</p>
 *
 * @param id DLQ ID
 * @param originalOutboxId 원본 Outbox ID
 * @param requestId 멱등성 키
 * @param eventType 이벤트 타입
 * @param payloadPreview payload 미리보기 (100자)
 * @param failureReason 실패 사유
 * @param movedAt DLQ 이동 시각
 */
public record DlqEntryResponse(
        Long id,
        Long originalOutboxId,
        String requestId,
        String eventType,
        String payloadPreview,
        String failureReason,
        LocalDateTime movedAt
) {
    private static final int PREVIEW_LENGTH = 100;

    public static DlqEntryResponse from(DonationDlq dlq) {
        return new DlqEntryResponse(
                dlq.getId(),
                dlq.getOriginalOutboxId(),
                dlq.getRequestId(),
                dlq.getEventType(),
                truncatePayload(dlq.getPayload()),
                dlq.getFailureReason(),
                dlq.getMovedAt()
        );
    }

    private static String truncatePayload(String payload) {
        if (payload == null) return null;
        if (payload.length() <= PREVIEW_LENGTH) return payload;
        return payload.substring(0, PREVIEW_LENGTH) + "...";
    }
}
