package maple.expectation.domain.v2;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import maple.expectation.global.executor.CheckedLogicExecutor;
import maple.expectation.global.executor.TaskContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * Nexon API용 Transactional Outbox 엔티티 (N19 리팩토링)
 *
 * <p>N19: 외부 API 6시간 장애 → Outbox 적재 → Replay/Reconciliation</p>
 *
 * <h3>사용 목적</h3>
 * <ul>
 *   <li>Nexon API 호출 실패 시 Outbox에 적재</li>
 *   <li>OutboxProcessor가 주기적으로 재시도</li>
 *   <li>장애 복구 후 자동 재처리</li>
 * </ul>
 */
@Entity
@Table(name = "nexon_api_outbox",
        indexes = {
                @Index(name = "idx_pending_poll", columnList = "status, next_retry_at, id"),
                @Index(name = "idx_locked", columnList = "locked_by, locked_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NexonApiOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false, unique = true, length = 100)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NexonApiEventType eventType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    /**
     * Content Hash (무결성 검증)
     */
    @Column(nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(length = 100)
    private String lockedBy;

    private LocalDateTime lockedAt;

    @Column(nullable = false)
    private int retryCount = 0;

    @Column(nullable = false)
    private int maxRetries = 3;

    @Column(length = 500)
    private String lastError;

    private LocalDateTime nextRetryAt;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * Nexon API 이벤트 타입
     */
    public enum NexonApiEventType {
        GET_OCID,
        GET_CHARACTER_BASIC,
        GET_ITEM_DATA,
        GET_CUBES
    }

    /**
     * Outbox 상태
     */
    public enum OutboxStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        DEAD_LETTER
    }

    /**
     * 팩토리 메서드 (Outbox 적재)
     */
    public static NexonApiOutbox create(String requestId, NexonApiEventType eventType, String payload) {
        NexonApiOutbox outbox = new NexonApiOutbox();
        outbox.requestId = requestId;
        outbox.eventType = eventType;
        outbox.payload = payload;
        outbox.contentHash = computeContentHash(requestId, eventType, payload);
        outbox.status = OutboxStatus.PENDING;
        outbox.nextRetryAt = LocalDateTime.now();
        outbox.createdAt = LocalDateTime.now();
        outbox.updatedAt = LocalDateTime.now();
        return outbox;
    }

    private static String computeContentHash(String requestId, NexonApiEventType eventType, String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((requestId + "|" + eventType + "|" + payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 JVM 필수 알고리즘이므로 여기 도달 시 JVM 결함
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 처리 시작 마킹
     */
    public void markProcessing(String instanceId) {
        this.status = OutboxStatus.PROCESSING;
        this.lockedBy = instanceId;
        this.lockedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 처리 완료 마킹
     */
    public void markCompleted() {
        this.status = OutboxStatus.COMPLETED;
        clearLock();
    }

    /**
     * 처리 실패 마킹 (Exponential Backoff)
     */
    public void markFailed(String error) {
        this.retryCount++;
        this.lastError = truncate(error, 500);
        this.status = shouldMoveToDlq() ? OutboxStatus.DEAD_LETTER : OutboxStatus.FAILED;
        long backoffSeconds = Math.min((long) Math.pow(2, retryCount) * 30, 3600); // Max 1시간
        this.nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds);
        clearLock();
    }

    /**
     * DLQ 이동 여부 판단
     */
    public boolean shouldMoveToDlq() {
        return retryCount >= maxRetries;
    }

    /**
     * 강제 DLQ 이동 (무결성 실패 등)
     */
    public void forceDeadLetter() {
        this.status = OutboxStatus.DEAD_LETTER;
        clearLock();
    }

    /**
     * Stalled 상태에서 재시도 가능 상태로 복원
     */
    public void resetToRetry() {
        this.status = OutboxStatus.PENDING;
        clearLock();
    }

    /**
     * 락 해제
     */
    private void clearLock() {
        this.lockedBy = null;
        this.lockedAt = null;
        this.updatedAt = LocalDateTime.now();
    }

    private static String truncate(String str, int maxLen) {
        return str != null && str.length() > maxLen ? str.substring(0, maxLen) : str;
    }

    /**
     * 무결성 검증
     */
    public boolean verifyIntegrity() {
        String expectedHash = computeContentHash(requestId, eventType, payload);
        return expectedHash.equals(contentHash);
    }

    /**
     * PII 마스킹
     */
    @Override
    public String toString() {
        return "NexonApiOutbox[id=" + id +
                ", requestId=" + requestId +
                ", status=" + status +
                ", eventType=" + eventType +
                ", payload=MASKED]";
    }
}
