package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.DonationHistory;
import maple.expectation.exception.CriticalTransactionFailureException; // [NEW] 커스텀 예외
import maple.expectation.exception.DeveloperNotFoundException;
import maple.expectation.exception.InsufficientPointException;
import maple.expectation.repository.v2.DonationHistoryRepository;
import maple.expectation.repository.v2.MemberRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DonationService {

    private final MemberRepository memberRepository;
    private final DonationHistoryRepository donationHistoryRepository;
    private final DiscordAlertService discordAlertService; // [NEW] 주입

    @Transactional
    public void sendCoffee(String guestUuid, Long developerId, Long amount, String requestId) {
        try {
            // ==========================================
            //  비즈니스 로직 (try 안에 깔끔하게 모음)
            // ==========================================

            // 1️⃣ [INFO] 멱등성 방어
            if (donationHistoryRepository.existsByRequestId(requestId)) {
                log.info("[Idempotency] 이미 처리된 요청입니다. RequestId={}, Guest={}", requestId, guestUuid);
                return;
            }

            // 2️⃣ [WARN] 잔액 차감
            int updatedCount = memberRepository.decreasePoint(guestUuid, amount);
            if (updatedCount == 0) {
                log.warn("[Donation Failed] 잔액 부족 또는 게스트 없음. Guest={}, Amount={}", guestUuid, amount);
                throw new InsufficientPointException("이체 실패: 잔액 부족 또는 유효하지 않은 게스트");
            }

            // 3️⃣ [WARN] 개발자 계정 확인 및 포인트 증가
            int developerUpdated = memberRepository.increasePoint(developerId, amount);
            if (developerUpdated == 0) {
                log.warn("[Donation Failed] 존재하지 않는 개발자 ID. DevId={}", developerId);
                throw new DeveloperNotFoundException("이체 실패: 존재하지 않는 개발자 ID(" + developerId + ")");
            }

            // 4️⃣ [INFO] 이력 저장
            DonationHistory history = DonationHistory.builder()
                    .senderUuid(guestUuid)
                    .receiverId(developerId)
                    .amount(amount)
                    .requestId(requestId)
                    .build();

            donationHistoryRepository.save(history);

            log.info("[Donation Success] 이체 성공. Guest={} -> Dev={}, Amount={}", guestUuid, developerId, amount);

        } catch (InsufficientPointException | DeveloperNotFoundException e) {
            // [Clean Catch 1] 비즈니스 로직상 발생한 "예상된 실패"는 알림 없이 상위로 던짐
            throw e;

        } catch (Exception e) {
            // [Clean Catch 2] 예상치 못한 시스템 장애 (DB 연결 끊김, NPE, 타임아웃 등)
            log.error("💥 Critical Failure in Donation Transaction. RequestId={}", requestId, e);

            // 1. 디스코드 알림 발송 (비동기)
            discordAlertService.sendCriticalAlert(
                    "DONATION TRANSACTION FAILED",
                    "도네이션 트랜잭션 중 치명적인 오류가 발생했습니다.\nRequestId: " + requestId,
                    e
            );

            // 2. Global Exception Handler용 커스텀 예외로 감싸서 던짐
            throw new CriticalTransactionFailureException("도네이션 시스템 오류 발생", e);
        }
    }
}