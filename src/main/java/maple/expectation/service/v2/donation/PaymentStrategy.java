package maple.expectation.service.v2.donation;

/**
 * 결제 전략 인터페이스 (Strategy Pattern)
 *
 * <p>OCP(개방-폐쇄 원칙) 준수: 새로운 결제 방식 추가 시
 * 기존 코드 수정 없이 새 Strategy 구현체만 추가하면 됩니다.</p>
 *
 * <h3>구현체 목록:</h3>
 * <ul>
 *   <li>{@link InternalPointPaymentStrategy} - 내부 포인트 시스템 (현재)</li>
 *   <li>PortOnePaymentStrategy - 포트원 결제 (미래 예정)</li>
 * </ul>
 *
 * @see InternalPointPaymentStrategy
 */
public interface PaymentStrategy {

    /**
     * 결제 처리
     *
     * <p>호출 전 트랜잭션이 시작되어 있어야 합니다.
     * 결제 실패 시 RuntimeException을 던져 트랜잭션 롤백을 유도합니다.</p>
     *
     * @param senderUuid          발신자 UUID
     * @param receiverFingerprint 수신자 fingerprint (Admin)
     * @param amount              결제 금액
     * @throws RuntimeException 결제 실패 시
     */
    void processPayment(String senderUuid, String receiverFingerprint, Long amount);

    /**
     * 결제 방식 이름 (로깅/메트릭용)
     *
     * @return 결제 방식 이름 (예: "INTERNAL_POINT", "PORTONE")
     */
    default String getStrategyName() {
        return this.getClass().getSimpleName();
    }
}
