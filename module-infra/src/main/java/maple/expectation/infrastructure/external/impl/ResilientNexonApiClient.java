package maple.expectation.infrastructure.external.impl;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.NexonApiOutbox;
import maple.expectation.error.exception.ExternalServiceException;
import maple.expectation.infrastructure.aop.annotation.ObservedTransaction;
import maple.expectation.infrastructure.external.NexonApiClient;
import maple.expectation.infrastructure.external.dto.v2.CharacterBasicResponse;
import maple.expectation.infrastructure.external.dto.v2.CharacterOcidResponse;
import maple.expectation.infrastructure.external.dto.v2.CubeHistoryResponse;
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse;
import maple.expectation.infrastructure.resilience.RetryBudgetManager;
import maple.expectation.util.ExceptionUtils;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Resilient Nexon API Client - 외부 API 호출에 회복 탄력성 패턴을 적용하는 퍼사드
 *
 * <h4>책임 (Refactoring 후)</h4>
 *
 * <ul>
 *   <li><b>API Delegation</b>: 4개 API 메서드 (OCID, Character Basic, Item Data, Cube History)
 *   <li><b>Retry Budget Check</b>: 장기 장애 시 재시도 폭주 방지
 *   <li><b>Fallback Coordination</b>: FallbackHandler에게 처리 위임
 * </ul>
 *
 * <h4>분리된 책임 (별도 클래스)</h4>
 *
 * <ul>
 *   <li>{@link OutboxFallbackManager}: Outbox 적재, 멱등성 ID 생성, PII 마스킹
 *   <li>{@link AlertNotificationHelper}: Best-effort 알림 발송
 *   <li>{@link FallbackHandler}: Fallback 로직 (Scenario A/B, 4xx 분류, 캐시 degradation)
 * </ul>
 *
 * <h4>Resilience4j 패턴</h4>
 *
 * <ul>
 *   <li>CircuitBreaker: 장애 격리
 *   <li>Retry: 일시적 장애 복구
 *   <li>TimeLimiter: 비동기 타임아웃 관리
 *   <li>Bulkhead: 동시성 제한
 * </ul>
 */
@Slf4j
@Profile("!chaos")
@Primary
@Component("resilientNexonApiClient")
@RequiredArgsConstructor
public class ResilientNexonApiClient implements NexonApiClient {

  private final NexonApiClient delegate;
  private final FallbackHandler fallbackHandler;
  private final RetryBudgetManager retryBudgetManager;

  private static final String NEXON_API = "nexonApi";

  /**
   * 캐릭터 이름으로 OCID 조회 (비동기)
   *
   * <p>Issue #195: CompletableFuture 반환으로 Reactor 체인 내 .block() 제거
   *
   * <p>TimeLimiter 추가로 비동기 작업 타임아웃 관리
   *
   * <p>Retry Budget: 장기 장애 시 재시도 폭주 방지
   */
  @Override
  @ObservedTransaction("external.api.nexon.ocid")
  @Bulkhead(name = NEXON_API)
  @TimeLimiter(name = NEXON_API)
  @CircuitBreaker(name = NEXON_API)
  @Retry(name = NEXON_API, fallbackMethod = "getOcidFallback")
  public CompletableFuture<CharacterOcidResponse> getOcidByCharacterName(String name) {
    // Retry Budget 확인 (재시도 전에 예산 체크)
    if (!retryBudgetManager.tryAcquire(NEXON_API)) {
      log.warn("[RetryBudget] OCID 조회 예산 소진으로 즉시 실패. name={}", name);
      return CompletableFuture.failedFuture(
          new ExternalServiceException("Retry budget exceeded for OCID lookup", null));
    }
    return delegate.getOcidByCharacterName(name);
  }

  /**
   * OCID로 캐릭터 기본 정보 조회 (비동기)
   *
   * <p>Resilience4j 적용: CircuitBreaker + Retry + TimeLimiter
   *
   * <p>Retry Budget: 장기 장애 시 재시도 폭주 방지
   */
  @Override
  @ObservedTransaction("external.api.nexon.basic")
  @Bulkhead(name = NEXON_API)
  @TimeLimiter(name = NEXON_API)
  @CircuitBreaker(name = NEXON_API)
  @Retry(name = NEXON_API, fallbackMethod = "getCharacterBasicFallback")
  public CompletableFuture<CharacterBasicResponse> getCharacterBasic(String ocid) {
    // Retry Budget 확인 (재시도 전에 예산 체크)
    if (!retryBudgetManager.tryAcquire(NEXON_API)) {
      log.warn("[RetryBudget] Character Basic 조회 예산 소진으로 즉시 실패. ocid={}", ocid);
      return CompletableFuture.failedFuture(
          new ExternalServiceException("Retry budget exceeded for Character Basic lookup", null));
    }
    return delegate.getCharacterBasic(ocid);
  }

  /**
   * OCID로 장비 데이터 조회 (비동기)
   *
   * <p>Resilience4j 적용: CircuitBreaker + Retry + TimeLimiter
   *
   * <p>Retry Budget: 장기 장애 시 재시도 폭주 방지
   */
  @Override
  @ObservedTransaction("external.api.nexon.itemdata")
  @Bulkhead(name = NEXON_API)
  @TimeLimiter(name = NEXON_API)
  @CircuitBreaker(name = NEXON_API)
  @Retry(name = NEXON_API, fallbackMethod = "getItemDataFallback")
  public CompletableFuture<EquipmentResponse> getItemDataByOcid(String ocid) {
    // Retry Budget 확인 (재시도 전에 예산 체크)
    if (!retryBudgetManager.tryAcquire(NEXON_API)) {
      log.warn("[RetryBudget] Item Data 조회 예산 소진으로 즉시 실패. ocid={}", ocid);
      return CompletableFuture.failedFuture(
          new ExternalServiceException("Retry budget exceeded for Item Data lookup", null));
    }
    return delegate.getItemDataByOcid(ocid);
  }

  /**
   * OCID로 큐브 사용 내역 조회 (비동기)
   *
   * <p>Resilience4j 적용: CircuitBreaker + Retry + TimeLimiter
   *
   * <p>Retry Budget: 장기 장애 시 재시도 폭주 방지
   */
  @Override
  @ObservedTransaction("external.api.nexon.cube")
  @Bulkhead(name = NEXON_API)
  @TimeLimiter(name = NEXON_API)
  @CircuitBreaker(name = NEXON_API)
  @Retry(name = NEXON_API, fallbackMethod = "getCubeHistoryFallback")
  public CompletableFuture<CubeHistoryResponse> getCubeHistory(String ocid) {
    // Retry Budget 확인 (재시도 전에 예산 체크)
    if (!retryBudgetManager.tryAcquire(NEXON_API)) {
      log.warn("[RetryBudget] Cube History 조회 예산 소진으로 즉시 실패. ocid={}", ocid);
      return CompletableFuture.failedFuture(
          new ExternalServiceException("Retry budget exceeded for Cube History lookup", null));
    }
    return delegate.getCubeHistory(ocid);
  }

  /**
   * OCID 조회 fallback (비동기)
   *
   * <p>Issue #195: CompletableFuture.failedFuture() 반환으로 비동기 계약 준수
   *
   * <p>N19: Outbox Fallback 패턴 적용 - 장애 시 Outbox에 적재하여 나중에 재시도
   */
  public CompletableFuture<CharacterOcidResponse> getOcidFallback(String name, Throwable t) {
    fallbackHandler.handleIgnoreMarker(t);
    log.error("[Resilience] OCID 최종 조회 실패. name={}", name, t);

    // Outbox Fallback: 멱등성 ID 생성 및 Outbox 적재
    String requestId = fallbackHandler.getOutboxManager().generateRequestId("GET_OCID", name);
    fallbackHandler
        .getOutboxManager()
        .saveToOutbox(requestId, NexonApiOutbox.NexonApiEventType.GET_OCID, name);

    return fallbackHandler.errorFuture(t);
  }

  /**
   * 캐릭터 기본 정보 조회 fallback (비동기)
   *
   * <p>Nexon API 4xx 응답(유효하지 않은 OCID 등)은 캐릭터 미존재로 처리
   *
   * <p>N19: Outbox Fallback 패턴 적용
   */
  public CompletableFuture<CharacterBasicResponse> getCharacterBasicFallback(
      String ocid, Throwable t) {
    fallbackHandler.handleIgnoreMarker(t);

    Throwable root = ExceptionUtils.unwrapAsyncException(t);
    if (root instanceof WebClientResponseException wce && wce.getStatusCode().is4xxClientError()) {
      log.warn(
          "[Resilience] Character basic 조회 4xx - 캐릭터 미존재 처리. ocid={}, status={}",
          ocid,
          wce.getStatusCode());
      return fallbackHandler.clientErrorFuture(ocid);
    }

    log.error("[Resilience] Character basic 최종 조회 실패. ocid={}", ocid, t);
    return fallbackHandler.serverErrorFuture(
        ocid, NexonApiOutbox.NexonApiEventType.GET_CHARACTER_BASIC, t);
  }

  /**
   * 장애 대응 시나리오 가동: DB 조회 → Outbox 적재 → 알림 발송
   *
   * <p><b>위임:</b> {@link FallbackHandler#handleItemDataFallback}에 처리 위임
   *
   * <h4>시나리오 분기</h4>
   *
   * <ul>
   *   <li><b>시나리오 A</b>: DB에서 만료된 캐시 발견 → 즉시 반환 (Degrade)
   *   <li><b>시나리오 B</b>: 캐시도 없음 → Outbox 적재 + 알림 발송 → 실패 반환
   * </ul>
   */
  public CompletableFuture<EquipmentResponse> getItemDataFallback(String ocid, Throwable t) {
    return fallbackHandler.handleItemDataFallback(
        ocid, NexonApiOutbox.NexonApiEventType.GET_ITEM_DATA, t);
  }

  /**
   * 큐브 사용 내역 조회 fallback (비동기)
   *
   * <p>Nexon API 4xx 응답(유효하지 않은 OCID 등)은 캐릭터 미존재로 처리
   *
   * <p>N19: Outbox Fallback 패턴 적용
   */
  public CompletableFuture<CubeHistoryResponse> getCubeHistoryFallback(String ocid, Throwable t) {
    fallbackHandler.handleIgnoreMarker(t);

    Throwable root = ExceptionUtils.unwrapAsyncException(t);
    if (root instanceof WebClientResponseException wce && wce.getStatusCode().is4xxClientError()) {
      log.warn(
          "[Resilience] Cube History 조회 4xx - 캐릭터 미존재 처리. ocid={}, status={}",
          ocid,
          wce.getStatusCode());
      return fallbackHandler.clientErrorFuture(ocid);
    }

    log.error("[Resilience] Cube History 최종 조회 실패. ocid={}", ocid, t);
    return fallbackHandler.serverErrorFuture(ocid, NexonApiOutbox.NexonApiEventType.GET_CUBES, t);
  }

  /**
   * Outbox Fallback 활성화/비활성화 설정 (위임)
   *
   * <p>YAML 설정 또는 런타임에 동적으로 제어 가능
   *
   * @param enabled 활성화 여부
   */
  public void setOutboxFallbackEnabled(boolean enabled) {
    fallbackHandler.getOutboxManager().setEnabled(enabled);
  }

  /**
   * Outbox Fallback 활성화 여부 조회 (위임)
   *
   * @return 활성화 여부
   */
  public boolean isOutboxFallbackEnabled() {
    return fallbackHandler.getOutboxManager().isEnabled();
  }
}
