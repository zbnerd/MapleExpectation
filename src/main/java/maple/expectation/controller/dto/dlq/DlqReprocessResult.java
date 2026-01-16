package maple.expectation.controller.dto.dlq;

/**
 * DLQ 재처리 결과 응답 DTO
 *
 * @param dlqId 처리된 DLQ ID
 * @param newOutboxId 새로 생성된 Outbox ID
 * @param requestId 멱등성 키
 * @param message 처리 결과 메시지
 */
public record DlqReprocessResult(
        Long dlqId,
        Long newOutboxId,
        String requestId,
        String message
) {
    public static DlqReprocessResult success(Long dlqId, Long newOutboxId, String requestId) {
        return new DlqReprocessResult(
                dlqId,
                newOutboxId,
                requestId,
                "Successfully requeued to Outbox for reprocessing"
        );
    }
}
