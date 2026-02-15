package maple.expectation.external.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import maple.expectation.alert.StatelessAlertService;
import maple.expectation.domain.repository.CharacterEquipmentRepository;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.persistence.repository.NexonApiOutboxRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nexon API Client Configuration - ResilientNexonApiClient 관련 Bean 설정
 *
 * <h4>책임</h4>
 *
 * <ul>
 *   <li>OutboxFallbackManager Bean 등록
 *   <li>AlertNotificationHelper Bean 등록
 *   <li>FallbackHandler Bean 등록
 * </ul>
 *
 * <p>이 설정 클래스는 {@link ResilientNexonApiClient}가 필요로 하는 의존성을 조립합니다.
 */
@Configuration
@RequiredArgsConstructor
public class NexonApiClientConfig {

  private final NexonApiOutboxRepository outboxRepository;
  private final CheckedLogicExecutor checkedExecutor;
  private final Executor alertTaskExecutor;
  private final TransactionTemplate transactionTemplate;
  private final StatelessAlertService statelessAlertService;
  private final CharacterEquipmentRepository equipmentRepository;
  private final ObjectMapper objectMapper;

  /**
   * Outbox Fallback Manager Bean
   *
   * <p>실패한 API 호출을 Outbox에 적재하는 전담 매니저
   *
   * @return OutboxFallbackManager 인스턴스
   */
  @Bean
  public OutboxFallbackManager outboxFallbackManager() {
    return new OutboxFallbackManager(
        outboxRepository, checkedExecutor, transactionTemplate, alertTaskExecutor);
  }

  /**
   * Alert Notification Helper Bean
   *
   * <p>Best-effort 알림 발송을 담당하는 헬퍼 클래스
   *
   * @return AlertNotificationHelper 인스턴스
   */
  @Bean
  public AlertNotificationHelper alertNotificationHelper() {
    return new AlertNotificationHelper(statelessAlertService, checkedExecutor, alertTaskExecutor);
  }

  /**
   * Fallback Handler Bean
   *
   * <p>API 호출 실패 시 fallback 로직을 담당하는 핸들러
   *
   * @return FallbackHandler 인스턴스
   */
  @Bean
  public FallbackHandler fallbackHandler(
      OutboxFallbackManager outboxFallbackManager,
      AlertNotificationHelper alertNotificationHelper) {
    return new FallbackHandler(
        equipmentRepository,
        objectMapper,
        checkedExecutor,
        outboxFallbackManager,
        alertNotificationHelper);
  }
}
