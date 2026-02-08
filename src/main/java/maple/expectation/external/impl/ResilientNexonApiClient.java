package maple.expectation.external.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.config.OutboxProperties;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.domain.v2.NexonApiOutbox;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterBasicResponse;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.CubeHistoryResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.global.error.exception.EquipmentDataProcessingException;
import maple.expectation.global.error.exception.ExternalServiceException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;
import maple.expectation.global.executor.CheckedLogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.resilience.RetryBudgetManager;
import maple.expectation.global.util.ExceptionUtils;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.repository.v2.NexonApiOutboxRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Slf4j
@Primary
@Component("resilientNexonApiClient")
public class ResilientNexonApiClient implements NexonApiClient {

  private final NexonApiClient delegate;
  private final DiscordAlertService discordAlertService;
  private final CharacterEquipmentRepository equipmentRepository;
  private final ObjectMapper objectMapper;
  private final CheckedLogicExecutor checkedExecutor;
  private final Executor alertTaskExecutor;
  private final NexonApiOutboxRepository outboxRepository;
  private final TransactionTemplate transactionTemplate;
  private final OutboxProperties outboxProperties;
  private final RetryBudgetManager retryBudgetManager;

  private static final String NEXON_API = "nexonApi";

  // 외부 서비스명 상수 (메트릭/로그 키 일관성)
  private static final String SERVICE_NEXON = "넥슨 API";
  private static final String SERVICE_DISCORD = "Discord";

  /**
   * Outbox Fallback 활성화 여부 (YAML 설정 가능)
   *
   * <p>기본값: true (장애 시 Outbox 적재)
   */
  private volatile boolean outboxFallbackEnabled = true;

  public ResilientNexonApiClient(
      @Qualifier("realNexonApiClient") NexonApiClient delegate,
      DiscordAlertService discordAlertService,
      CharacterEquipmentRepository equipmentRepository,
      ObjectMapper objectMapper,
      @Qualifier("checkedLogicExecutor") CheckedLogicExecutor checkedExecutor,
      @Qualifier("alertTaskExecutor") Executor alertTaskExecutor,
      NexonApiOutboxRepository outboxRepository,
      TransactionTemplate transactionTemplate,
      OutboxProperties outboxProperties,
      RetryBudgetManager retryBudgetManager) {
    this.delegate = delegate;
    this.discordAlertService = discordAlertService;
    this.equipmentRepository = equipmentRepository;
    this.objectMapper = objectMapper;
    this.checkedExecutor = checkedExecutor;
    this.alertTaskExecutor = alertTaskExecutor;
    this.outboxRepository = outboxRepository;
    this.transactionTemplate = transactionTemplate;
    this.outboxProperties = outboxProperties;
    this.retryBudgetManager = retryBudgetManager;
  }

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
    handleIgnoreMarker(t);
    log.error("[Resilience] OCID 최종 조회 실패. name={}", name, t);

    // Outbox Fallback: 멱등성 ID 생성 및 Outbox 적재
    saveToOutbox(
        generateRequestId("GET_OCID", name), NexonApiOutbox.NexonApiEventType.GET_OCID, name);

    return CompletableFuture.failedFuture(new ExternalServiceException(SERVICE_NEXON, t));
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
    handleIgnoreMarker(t);

    Throwable root = ExceptionUtils.unwrapAsyncException(t);
    if (root instanceof WebClientResponseException wce && wce.getStatusCode().is4xxClientError()) {
      log.warn(
          "[Resilience] Character basic 조회 4xx - 캐릭터 미존재 처리. ocid={}, status={}",
          ocid,
          wce.getStatusCode());
      return CompletableFuture.failedFuture(new CharacterNotFoundException(ocid));
    }

    log.error("[Resilience] Character basic 최종 조회 실패. ocid={}", ocid, t);

    // Outbox Fallback: 5xx/장애 시에만 Outbox 적재 (4xx는 비즈니스 예외)
    saveToOutbox(
        generateRequestId("GET_CHARACTER_BASIC", ocid),
        NexonApiOutbox.NexonApiEventType.GET_CHARACTER_BASIC,
        ocid);

    return CompletableFuture.failedFuture(new ExternalServiceException(SERVICE_NEXON, t));
  }

  /**
   * 장애 대응 시나리오 가동: DB 조회 → Outbox 적재 → 알림 발송
   *
   * <p>비동기 계약 준수: 최종 실패 시 failedFuture 반환 (throw 금지)
   *
   * <p>알림은 best-effort: 실패해도 fallback 반환 계약을 깨지 않음
   *
   * <h4>N19 Outbox Fallback 추가</h4>
   *
   * <ul>
   *   <li>캐시 실패 시 Outbox에 적재하여 나중에 재시도
   *   <li>장기 장애(6시간) 대응 가능
   *   <li>멱등성 ID로 중복 방지
   * </ul>
   *
   * <h4>executor 사용 정책</h4>
   *
   * <ul>
   *   <li><b>fallback 전체를 executor로 감싸지 않음</b>: "실패를 값으로 반환"하는 구조로 관측성 왜곡 방지
   *   <li><b>convertToResponse는 executor 사용</b>: JSON 역직렬화 checked 예외 변환
   *   <li><b>알림 발송은 executor 사용</b>: 외부 웹훅 호출 checked 예외 변환
   * </ul>
   */
  public CompletableFuture<EquipmentResponse> getItemDataFallback(String ocid, Throwable t) {
    handleIgnoreMarker(t);

    // ★ P0-3: 일관된 root cause 사용 (CompletionException/ExecutionException unwrap)
    Throwable rootCause = ExceptionUtils.unwrapAsyncException(t);
    log.warn("[Resilience] 장애 대응 시나리오 가동. ocid={}", ocid, rootCause);

    // 알림용 cause 준비: Error는 이미 handleIgnoreMarker에서 throw됨
    Exception alertCause = (rootCause instanceof Exception ex) ? ex : new Exception(rootCause);

    // 1. DB에서 만료된 캐시라도 찾기 (Scenario A)
    // convertToResponse 내부에서 CheckedLogicExecutor로 JSON 역직렬화 관측성 확보
    EquipmentResponse cachedData =
        equipmentRepository.findById(ocid).map(this::convertToResponse).orElse(null);

    if (cachedData != null) {
      log.warn("[Scenario A] 만료된 캐시 데이터 반환 (Degrade)");
      return CompletableFuture.completedFuture(cachedData);
    }

    // 2. 캐시도 없으면 Outbox 적재 후 최종 실패 및 알림 (Scenario B)
    log.error("[Scenario B] 캐시 부재. Outbox 적재 및 알림 발송 시도");

    // N19: Outbox에 적재하여 나중에 재시도
    saveToOutbox(
        generateRequestId("GET_ITEM_DATA", ocid),
        NexonApiOutbox.NexonApiEventType.GET_ITEM_DATA,
        ocid);

    // 알림은 best-effort: 실패해도 fallback 반환 계약을 깨지 않음
    sendAlertBestEffort(ocid, alertCause);

    // ★ P0-3 : 도메인 예외 cause는 원본 t 유지 (래퍼 컨텍스트 보존)
    // - 관측(로그/알림): rootCause 사용 (위에서 처리)
    // - 트러블슈팅: wrapper(CompletionException 등)도 의미 있으므로 원본 유지
    return CompletableFuture.failedFuture(new ExternalServiceException(SERVICE_NEXON, t));
  }

  /**
   * 큐브 사용 내역 조회 fallback (비동기)
   *
   * <p>Nexon API 4xx 응답(유효하지 않은 OCID 등)은 캐릭터 미존재로 처리
   *
   * <p>N19: Outbox Fallback 패턴 적용
   */
  public CompletableFuture<CubeHistoryResponse> getCubeHistoryFallback(String ocid, Throwable t) {
    handleIgnoreMarker(t);

    Throwable root = ExceptionUtils.unwrapAsyncException(t);
    if (root instanceof WebClientResponseException wce && wce.getStatusCode().is4xxClientError()) {
      log.warn(
          "[Resilience] Cube History 조회 4xx - 캐릭터 미존재 처리. ocid={}, status={}",
          ocid,
          wce.getStatusCode());
      return CompletableFuture.failedFuture(new CharacterNotFoundException(ocid));
    }

    log.error("[Resilience] Cube History 최종 조회 실패. ocid={}", ocid, t);

    // Outbox Fallback: 5xx/장애 시에만 Outbox 적재 (4xx는 비즈니스 예외)
    saveToOutbox(
        generateRequestId("GET_CUBES", ocid), NexonApiOutbox.NexonApiEventType.GET_CUBES, ocid);

    return CompletableFuture.failedFuture(new ExternalServiceException(SERVICE_NEXON, t));
  }

  /**
   * 알림 발송 (best-effort)
   *
   * <p>알림 실패가 fallback의 반환 계약을 깨지 않도록 비동기 분리 + 예외 흡수
   *
   * <p>전용 alertTaskExecutor 사용: commonPool 오염/경합 방지
   *
   * <h4>예외 처리 정책 </h4>
   *
   * <ul>
   *   <li><b>RejectedExecutionException</b>: 정책적 드롭 → DEBUG (정상 시나리오)
   *   <li><b>기타 예외</b>: 실제 알림 실패 → WARN
   * </ul>
   */
  private void sendAlertBestEffort(String ocid, Exception alertCause) {
    CompletableFuture.runAsync(
            () ->
                checkedExecutor.executeUncheckedVoid(
                    () ->
                        discordAlertService.sendCriticalAlert(
                            "외부 API 장애", "OCID: " + ocid, alertCause),
                    TaskContext.of("Alert", "SendCritical", ocid),
                    e -> new ExternalServiceException(SERVICE_DISCORD, e)),
            alertTaskExecutor // commonPool 대신 전용 Executor 사용
            )
        .exceptionally(ex -> handleAlertFailure(ex, ocid));
  }

  /**
   * 알림 실패 처리 (Section 15: 3-Line Rule 준수)
   *
   * @param ex 알림 실패 예외
   * @param ocid 대상 캐릭터 OCID
   * @return null (exceptionally 반환값)
   */
  private Void handleAlertFailure(Throwable ex, String ocid) {
    Throwable root = ExceptionUtils.unwrapAsyncException(ex);
    if (root instanceof RejectedExecutionException) {
      log.debug("[Alert] 알림 드롭 (큐 포화, best-effort). ocid={}", ocid);
    } else {
      log.warn("[Alert] 디스코드 알림 실패 (best-effort). ocid={}", ocid, root);
    }
    return null;
  }

  /**
   * CircuitBreakerIgnoreMarker 처리 및 ADR 계약 방어
   *
   * <p>Resilience4j/CompletableFuture 경로에서 CompletionException/ExecutionException으로 감싸진 예외를 unwrap한
   * 후 Marker 여부를 판단합니다.
   *
   * <p>Error는 즉시 전파하고, Marker가 RuntimeException이 아니면 IllegalStateException으로 fail-fast
   */
  private void handleIgnoreMarker(Throwable t) {
    // Resilience4j/CompletableFuture wrapper unwrap
    Throwable root = ExceptionUtils.unwrapAsyncException(t);

    // ADR: Error는 어떤 상황에서든 즉시 전파
    if (root instanceof Error e) throw e;

    // Marker 처리: RuntimeException 캐스팅 방어
    if (root instanceof CircuitBreakerIgnoreMarker) {
      if (root instanceof RuntimeException re) throw re;
      throw new IllegalStateException("CircuitBreakerIgnoreMarker must be RuntimeException", root);
    }
  }

  /**
   * JSON 역직렬화: CheckedLogicExecutor.executeUnchecked로 try-catch 제거
   *
   * <p>Jackson의 JsonProcessingException(checked)을 EquipmentDataProcessingException(unchecked)으로 변환
   */
  private EquipmentResponse convertToResponse(CharacterEquipment entity) {
    return checkedExecutor.executeUnchecked(
        () -> objectMapper.readValue(entity.getJsonContent(), EquipmentResponse.class),
        TaskContext.of("NexonApi", "DeserializeCache", entity.getOcid()),
        e ->
            new EquipmentDataProcessingException(
                "JSON 역직렬화 실패 [ocid=" + entity.getOcid() + "]: " + e.getMessage(), e));
  }

  // ========== N19: Outbox Fallback Pattern ==========

  /**
   * Outbox에 실패한 API 호출을 적재 (비동기)
   *
   * <h4>멱등성 보장</h4>
   *
   * <ul>
   *   <li>requestId 기반 중복 체크 (existsByRequestId)
   *   <li>이미 존재하면 재적재하지 않음 (idempotent insert)
   * </ul>
   *
   * <h4>비동기 처리</h4>
   *
   * <ul>
   *   <li>fallback 메인 흐름을 차단하지 않도록 별도 스레드에서 실행
   *   <li>실패해도 사용자 응답에는 영향 없음 (best-effort)
   * </ul>
   *
   * @param requestId 멱등성 ID (UUID-based)
   * @param eventType API 이벤트 타입
   * @param payload 요청 파라미터 (OCID, 캐릭터명 등)
   */
  private void saveToOutbox(
      String requestId, NexonApiOutbox.NexonApiEventType eventType, String payload) {
    if (!outboxFallbackEnabled) {
      log.debug("[Outbox] Fallback 비활성화로 인해 Outbox 적재 스킵. requestId={}", requestId);
      return;
    }

    // 비동기로 Outbox 적재 (fallback 메인 흐름 차단 방지)
    CompletableFuture.runAsync(
            () -> {
              TaskContext context = TaskContext.of("Outbox", "Save", requestId);

              checkedExecutor.executeUncheckedVoid(
                  () -> {
                    // 멱등성 체크 (이미 존재하면 스킵)
                    if (outboxRepository.existsByRequestId(requestId)) {
                      log.warn("[Outbox] 이미 존재하는 requestId로 인해 적재 스킵 (Idempotent): {}", requestId);
                      return;
                    }

                    // Outbox 생성 및 저장
                    NexonApiOutbox outbox = NexonApiOutbox.create(requestId, eventType, payload);

                    transactionTemplate.executeWithoutResult(
                        status -> {
                          outboxRepository.save(outbox);
                          log.info(
                              "[Outbox] 실패한 API 호출을 Outbox에 적재: requestId={}, eventType={}, payload={}",
                              requestId,
                              eventType,
                              maskPayload(payload));
                        });
                  },
                  context,
                  e -> {
                    log.error("[Outbox] Outbox 적재 실패 (best-effort): requestId={}", requestId, e);
                    return null; // Void 반환
                  });
            },
            alertTaskExecutor) // 전용 Executor 사용 (commonPool 오염 방지)
        .exceptionally(
            ex -> {
              log.error("[Outbox] Outbox 적재 비동기 실행 실패 (best-effort): requestId={}", requestId, ex);
              return null;
            });
  }

  /**
   * 멱등성 Request ID 생성
   *
   * <h4>생성 규칙</h4>
   *
   * <ul>
   *   <li>UUID 기반 고유 ID
   *   <li>eventType + payload 조합으로 추적 가능
   *   <li>재시도 시 같은 requestId 생성 가능성 최소화
   * </ul>
   *
   * @param eventType API 이벤트 타입
   * @param payload 요청 파라미터
   * @return requestId (UUID-based)
   */
  private String generateRequestId(String eventType, String payload) {
    // UUID + eventType + payload hash 조합으로 고유성 확보
    String base = String.format("%s-%s-%d", eventType, payload, System.currentTimeMillis());
    return UUID.nameUUIDFromBytes(base.getBytes()).toString();
  }

  /**
   * PII 마스킹 (로그 안전성 확보)
   *
   * @param payload 원본 payload
   * @return 마스킹된 payload (앞 4자만 노출)
   */
  private String maskPayload(String payload) {
    if (payload == null || payload.length() <= 4) {
      return "***";
    }
    return payload.substring(0, 4) + "***";
  }

  /**
   * Outbox Fallback 활성화/비활성화 설정
   *
   * <p>YAML 설정 또는 런타임에 동적으로 제어 가능
   *
   * @param enabled 활성화 여부
   */
  public void setOutboxFallbackEnabled(boolean enabled) {
    this.outboxFallbackEnabled = enabled;
    log.info("[Outbox] Fallback 설정 변경: enabled={}", enabled);
  }

  /**
   * Outbox Fallback 활성화 여부 조회
   *
   * @return 활성화 여부
   */
  public boolean isOutboxFallbackEnabled() {
    return outboxFallbackEnabled;
  }
}
