package maple.expectation.service.v2.donation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.exception.AdminMemberNotFoundException;
import maple.expectation.global.error.exception.InsufficientPointException;
import maple.expectation.repository.v2.MemberRepository;
import org.springframework.stereotype.Component;

/**
 * 내부 포인트 시스템 기반 결제 전략
 *
 * <p>Member 테이블의 point 필드를 사용하여 결제를 처리합니다.
 * 발신자의 포인트를 차감하고 수신자(Admin)의 포인트를 증가시킵니다.</p>
 *
 * <h3>포트원 연동 시 교체 방법:</h3>
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

    @Override
    public void processPayment(String senderUuid, String receiverFingerprint, Long amount) {
        log.debug("[Payment] Processing internal point transfer: sender={}, amount={}",
                maskUuid(senderUuid), amount);

        // 1. 발신자 포인트 차감
        if (memberRepository.decreasePoint(senderUuid, amount) == 0) {
            throw new InsufficientPointException("이체 실패: 잔액 부족 또는 유효하지 않은 발신자");
        }

        // 2. 수신자(Admin) 포인트 증가
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
