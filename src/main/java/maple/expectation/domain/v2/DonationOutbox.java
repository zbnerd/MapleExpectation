package maple.expectation.domain.v2;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import maple.expectation.global.error.exception.InternalSystemException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * Transactional Outbox 엔티티 (Issue #80)
 *
 * <h3>Financial-Grade 특성</h3>
 * <ul>
 *   <li>{@code @Version}: Optimistic Locking으로 동시 수정 감지</li>
 *   <li>{@code contentHash}: 개별 레코드 무결성 검증 (분산 환경 안전)</li>
 *   <li>SKIP LOCKED 호환: status + nextRetryAt 인덱스</li>
 * </ul>
 *
 * @see maple.expectation.repository.v2.DonationOutboxRepository
 */
@Entity
@Table(name = "donation_outbox",
        indexes = {
                @Index(name = "idx_pending_poll", columnList = "status, next_retry_at, id"),
                @Index(name = "idx_locked", columnList = "locked_by, locked_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DonationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false, unique = true, length = 50)
    private String requestId;

    @Column(nullable = false, length = 50)
    private String eventType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    /**
     * Content Hash (분산 환경 안전)
     * <p>Hash Chain 대신 개별 레코드 무결성만 검증 (동시성 문제 제거)</p>
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
     * 팩토리 메서드 (Content Hash 자동 생성)
     */
    public static DonationOutbox create(String requestId, String eventType, String payload) {
        DonationOutbox outbox = new DonationOutbox();
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

    /**
     * Content Hash 계산 (CLAUDE.md Section 11 준수: InternalSystemException 사용)
     */
    private static String computeContentHash(String reqId, String type, String payload) {
        MessageDigest digest = getSha256Digest();
        byte[] hash = digest.digest((reqId + "|" + type + "|" + payload).getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hash);
    }

    /**
     * SHA-256 MessageDigest 획득
     *
     * <p><b>CLAUDE.md Section 11 준수:</b> 체크 예외를 InternalSystemException으로 변환</p>
     * <p>SHA-256은 Java 표준 필수 알고리즘 (RFC 8018)이므로
     * NoSuchAlgorithmException이 발생할 수 없음. 발생 시 JVM 결함.</p>
     *
     * <p><b>Note:</b> JPA 엔티티에서는 LogicExecutor 주입이 불가하므로
     * Section 11 규칙에 따라 직접 예외 변환 허용</p>
     */
    @SuppressWarnings("java:S1166")  // NoSuchAlgorithmException은 발생 불가 (Java 표준)
    private static MessageDigest getSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 JVM 필수 알고리즘이므로 여기 도달 시 JVM 결함
            throw new InternalSystemException("SHA-256 algorithm not available (JVM defect)", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 무결성 검증
     */
    public boolean verifyIntegrity() {
        String expected = computeContentHash(requestId, eventType, payload);
        return contentHash.equals(expected);
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
        this.nextRetryAt = LocalDateTime.now()
                .plusSeconds((long) Math.pow(2, retryCount) * 30);
        clearLock();
    }

    /**
     * DLQ 이동 여부 판단
     */
    public boolean shouldMoveToDlq() {
        return retryCount >= maxRetries;
    }

    /**
     * 즉시 DEAD_LETTER 상태로 강제 변경 (Purple 요구사항)
     *
     * <p>무결성 검증 실패 등 재시도가 무의미한 경우 사용</p>
     */
    public void forceDeadLetter() {
        this.status = OutboxStatus.DEAD_LETTER;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Stalled 상태에서 PENDING으로 복원 (#229)
     *
     * <p>JVM 크래시로 인한 Zombie 상태를 복구하기 위해 사용.
     * 무결성 검증 통과 후에만 호출되어야 함.</p>
     */
    public void resetToRetry() {
        this.status = OutboxStatus.PENDING;
        this.nextRetryAt = LocalDateTime.now();
        clearLock();
    }

    private void clearLock() {
        this.lockedBy = null;
        this.lockedAt = null;
        this.updatedAt = LocalDateTime.now();
    }

    private static String truncate(String str, int maxLen) {
        return str != null && str.length() > maxLen ? str.substring(0, maxLen) : str;
    }

    /**
     * PII 마스킹 (CLAUDE.md 19 준수)
     */
    @Override
    public String toString() {
        return "DonationOutbox[id=" + id +
                ", requestId=" + requestId +
                ", status=" + status +
                ", payload=MASKED]";
    }
}
