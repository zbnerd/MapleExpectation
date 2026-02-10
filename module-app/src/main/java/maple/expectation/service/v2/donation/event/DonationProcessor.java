package maple.expectation.service.v2.donation.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.service.v2.donation.PaymentStrategy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도네이션 결제 처리기 (Orchestrator)
 *
 * <p>Strategy Pattern을 통해 결제 방식을 위임합니다. 현재는 내부 포인트 시스템만 지원하며, 추후 포트원 결제 등 새로운 결제 방식 추가 시
 * PaymentStrategy 구현체만 추가하면 됩니다.
 *
 * <h3>SOLID 원칙:</h3>
 *
 * <ul>
 *   <li>SRP: 이 클래스는 오케스트레이션만 담당
 *   <li>OCP: 새 결제 방식 추가 시 이 클래스 수정 불필요
 *   <li>DIP: 구체 클래스가 아닌 PaymentStrategy 인터페이스에 의존
 * </ul>
 *
 * @see PaymentStrategy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DonationProcessor {

  private final PaymentStrategy paymentStrategy;

  /**
   * Admin에게 결제 처리
   *
   * <p>반드시 기존 트랜잭션 내에서 실행되어야 합니다 (MANDATORY).
   *
   * @param senderUuid 발신자 UUID
   * @param receiverFingerprint 수신자 Admin fingerprint
   * @param amount 결제 금액
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void executeTransferToAdmin(String senderUuid, String receiverFingerprint, Long amount) {
    log.debug("[DonationProcessor] Delegating to strategy: {}", paymentStrategy.getStrategyName());
    paymentStrategy.processPayment(senderUuid, receiverFingerprint, amount);
  }
}
