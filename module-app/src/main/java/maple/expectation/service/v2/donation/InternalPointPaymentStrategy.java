package maple.expectation.service.v2.donation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.Member;
import maple.expectation.error.exception.AdminMemberNotFoundException;
import maple.expectation.error.exception.SenderMemberNotFoundException;
import maple.expectation.infrastructure.persistence.repository.MemberRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내부 포인트 시스템 기반 결제 전략 (Hybrid: Rich Domain + Atomic Query)
 *
 * <p>Issue #120: Rich Domain Model 전환
 *
 * <h3>하이브리드 전략</h3>
 *
 * <ul>
 *   <li><b>Sender (발신자)</b>: Rich Domain (member.deductPoints()) - 각 사용자는 자신의 데이터만 수정하므로 충돌 가능성 낮음
 *       - @Version 낙관적 락으로 보호
 *   <li><b>Receiver (Admin)</b>: 원자적 쿼리 (memberRepository.increasePointByUuid()) - Hot Key 문제: 모든
 *       후원이 단일 Admin으로 집중됨 - 100명 동시 요청 시 원자적 UPDATE로 Lost Update 방지
 * </ul>
 *
 * <h3>포트원 연동 시 교체 방법:</h3>
 *
 * <pre>{@code
 * // application.yml
 * payment:
 *   provider: portone  # internal (기본값) -> portone
 *
 * // PortOnePaymentStrategy.java
 * @Component
 * @ConditionalOnProperty(name = "payment.provider", havingValue = "portone")
 * public class PortOnePaymentStrategy implements PaymentStrategy { ... }
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalPointPaymentStrategy implements PaymentStrategy {

  private final MemberRepository memberRepository;

  /**
   * 포인트 이체 처리 (Hybrid: Rich Domain + Atomic Query)
   *
   * <p>Issue #120: Sender에 Member.deductPoints() 적용 (플랜 핵심 요구사항)
   *
   * @param senderUuid 발신자 UUID
   * @param receiverFingerprint 수신자(Admin) fingerprint (Member.uuid로 사용)
   * @param amount 이체 금액
   */
  @Override
  @Transactional
  public void processPayment(String senderUuid, String receiverFingerprint, Long amount) {
    log.debug(
        "[Payment] Processing internal point transfer: sender={}, amount={}",
        maskUuid(senderUuid),
        amount);

    // 1. 발신자: Rich Domain (Issue #120 핵심)
    //    - 각 사용자는 자신의 데이터만 수정 → 충돌 가능성 낮음
    //    - @Version 낙관적 락으로 보호
    Member sender =
        memberRepository
            .findByUuid(senderUuid)
            .orElseThrow(() -> new SenderMemberNotFoundException(maskUuid(senderUuid)));
    sender.deductPoints(amount); // InsufficientPointException 발생 가능

    // 2. 수신자(Admin): 원자적 쿼리 (Hot Key 보호)
    //    - 모든 후원이 단일 Admin으로 집중 → 동시성 경합 심함
    //    - 원자적 UPDATE로 Lost Update 방지
    if (memberRepository.increasePointByUuid(receiverFingerprint, amount) == 0) {
      throw new AdminMemberNotFoundException();
    }

    log.info("[Payment] Internal point transfer completed: amount={}", amount);
  }

  @Override
  public String getStrategyName() {
    return "INTERNAL_POINT";
  }

  private String maskUuid(String uuid) {
    if (uuid == null || uuid.length() < 8) {
      return "****";
    }
    return uuid.substring(0, 4) + "****";
  }
}
