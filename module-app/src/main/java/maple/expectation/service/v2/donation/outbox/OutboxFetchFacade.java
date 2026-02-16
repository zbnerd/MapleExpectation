package maple.expectation.service.v2.donation.outbox;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.config.OutboxProperties;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.domain.v2.DonationOutbox.OutboxStatus;
import maple.expectation.infrastructure.persistence.repository.DonationOutboxRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 조회 Facade (내부 호출 AOP 문제 해결용)
 *
 * <p>OutboxProcessor의 fetchAndLock()를 분리하여 Spring AOP 프록시가 정상 작동하도록 함.
 *
 * <h3>분리 사유</h3>
 *
 * <ul>
 *   <li>동일 클래스 내부 메서드 호출 시 @Transactional 무시 문제 해결
 *   <li>Facade 패턴으로 트랜잭션 경계 명확화
 *   <li>단일 책임 원칙: 조회 로직과 처리 로직 분리
 * </ul>
 *
 * @see OutboxProcessor
 * @see DonationOutboxRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxFetchFacade {

  private final DonationOutboxRepository outboxRepository;
  private final OutboxProperties properties;

  /**
   * Phase 1: SKIP LOCKED 조회 + markProcessing (단일 트랜잭션)
   *
   * <p>트랜잭션 종료와 함께 SKIP LOCKED 해제되지만, 상태가 PROCESSING으로 변경되어 다른 인스턴스가 재조회하지 않음
   *
   * @return 잠긴 Outbox 항목 목록
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public List<DonationOutbox> fetchAndLock() {
    List<DonationOutbox> pending =
        outboxRepository.findPendingWithLock(
            List.of(OutboxStatus.PENDING, OutboxStatus.FAILED),
            LocalDateTime.now(),
            PageRequest.of(0, properties.getBatchSize()));

    for (DonationOutbox entry : pending) {
      entry.markProcessing(properties.getInstanceId());
    }

    return outboxRepository.saveAll(pending);
  }
}
