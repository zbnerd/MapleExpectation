package maple.expectation.infrastructure.external.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.model.equipment.CharacterEquipment;
import maple.expectation.domain.repository.CharacterEquipmentRepository;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.error.exception.EquipmentDataProcessingException;
import maple.expectation.error.exception.ExternalServiceException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse;
import maple.expectation.infrastructure.util.ExceptionUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Fallback Handler - API 호출 실패 시 fallback 로직을 담당하는 전담 클래스
 *
 * <h4>책임</h4>
 *
 * <ul>
 *   <li>CircuitBreakerIgnoreMarker 처리
 *   <li>4xx 클라이언트 에러 분류 (CharacterNotFoundException)
 *   <li>시나리오 A: 만료된 캐시 반환 (Degrade)
 *   <li>시나리오 B: Outbox 적재 + 알림 발송
 *   <li>JSON 역직렬화 (Cached Equipment → EquipmentResponse)
 * </ul>
 *
 * <h4>장애 대응 시나리오</h4>
 *
 * <p><b>시나리오 A (Degrade)</b>: DB에서 만료된 캐시라도 찾기<br>
 * <b>시나리오 B (Outbox)</b>: 캐시도 없으면 Outbox 적재 후 알림 발송
 */
@Slf4j
@RequiredArgsConstructor
public class FallbackHandler {

  private final CharacterEquipmentRepository equipmentRepository;
  private final ObjectMapper objectMapper;
  private final CheckedLogicExecutor checkedExecutor;
  private final OutboxFallbackManager outboxFallbackManager;
  private final AlertNotificationHelper alertNotificationHelper;

  /** 외부 서비스명 상수 (메트릭/로그 키 일관성) */
  private static final String SERVICE_NEXON = "넥슨 API";

  /**
   * CircuitBreakerIgnoreMarker 처리 및 ADR 계약 방어
   *
   * <p>Resilience4j/CompletableFuture 경로에서 CompletionException/ExecutionException으로 감싸진 예외를 unwrap한
   * 후 Marker 여부를 판단합니다.
   *
   * <p>Error는 즉시 전파하고, Marker가 RuntimeException이 아니면 IllegalStateException으로 fail-fast
   *
   * @param t 원본 예외
   */
  public void handleIgnoreMarker(Throwable t) {
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
   * 4xx 클라이언트 에러 확인 및 CharacterNotFoundException 변환
   *
   * @param root 원본 예외 (unwrap된 상태)
   * @param ocid 캐릭터 OCID
   * @return 4xx 에러이면 true, 아니면 false
   */
  public boolean isClientError(Throwable root, String ocid) {
    if (root instanceof WebClientResponseException wce && wce.getStatusCode().is4xxClientError()) {
      log.warn(
          "[Fallback] 4xx 클라이언트 에러 - 캐릭터 미존재 처리. ocid={}, status={}", ocid, wce.getStatusCode());
      return true;
    }
    return false;
  }

  /**
   * 장애 대응 시나리오: DB 조회 → Outbox 적재 → 알림 발송
   *
   * <h4>시나리오 분기</h4>
   *
   * <ul>
   *   <li><b>시나리오 A</b>: DB에서 만료된 캐시 발견 → 즉시 반환 (Degrade)
   *   <li><b>시나리오 B</b>: 캐시도 없음 → Outbox 적재 + 알림 발송 → 실패 반환
   * </ul>
   *
   * <h4>P0-3: 일관된 root cause 사용</h4>
   *
   * <ul>
   *   <li>관측(로그/알림): rootCause 사용
   *   <li>트러블슈팅: wrapper(CompletionException 등)도 의미 있으므로 원본 t 유지
   * </ul>
   *
   * @param ocid 캐릭터 OCID
   * @param eventType API 이벤트 타입 (Outbox용)
   * @param t 원본 예외
   * @return CompletableFuture<EquipmentResponse> - 캐시 데이터 또는 failedFuture
   */
  public CompletableFuture<EquipmentResponse> handleItemDataFallback(
      String ocid,
      maple.expectation.domain.v2.NexonApiOutbox.NexonApiEventType eventType,
      Throwable t) {

    // ★ P0-3: 일관된 root cause 사용 (CompletionException/ExecutionException unwrap)
    Throwable rootCause = ExceptionUtils.unwrapAsyncException(t);
    log.warn("[Fallback] 장애 대응 시나리오 가동. ocid={}", ocid, rootCause);

    // 알림용 cause 준비: Error는 이미 handleIgnoreMarker에서 throw됨
    Exception alertCause = (rootCause instanceof Exception ex) ? ex : new Exception(rootCause);

    // 1. DB에서 만료된 캐시라도 찾기 (Scenario A)
    // convertToResponse 내부에서 CheckedLogicExecutor로 JSON 역직렬화 관측성 확보
    EquipmentResponse cachedData =
        equipmentRepository
            .findById(maple.expectation.domain.model.character.CharacterId.of(ocid))
            .map(entity -> convertToResponse(entity))
            .orElse(null);

    if (cachedData != null) {
      log.warn("[Scenario A] 만료된 캐시 데이터 반환 (Degrade)");
      return CompletableFuture.completedFuture(cachedData);
    }

    // 2. 캐시도 없으면 Outbox 적재 후 최종 실패 및 알림 (Scenario B)
    log.error("[Scenario B] 캐시 부재. Outbox 적재 및 알림 발송 시도");

    // Outbox에 적재하여 나중에 재시도
    String requestId = outboxFallbackManager.generateRequestId(eventType.name(), ocid);
    outboxFallbackManager.saveToOutbox(requestId, eventType, ocid);

    // 알림은 best-effort: 실패해도 fallback 반환 계약을 깨지 않음
    alertNotificationHelper.sendAlertBestEffort(ocid, alertCause);

    // ★ P0-3 : 도메인 예외 cause는 원본 t 유지 (래퍼 컨텍스트 보존)
    return CompletableFuture.failedFuture(new ExternalServiceException(SERVICE_NEXON, t));
  }

  /**
   * JSON 역직렬화: CheckedLogicExecutor.executeUnchecked로 try-catch 제거
   *
   * <p>Jackson의 JsonProcessingException(checked)을 EquipmentDataProcessingException(unchecked)으로 변환
   *
   * @param entity CharacterEquipment 엔티티
   * @return EquipmentResponse DTO
   */
  private EquipmentResponse convertToResponse(CharacterEquipment entity) {
    return checkedExecutor.executeUnchecked(
        () -> objectMapper.readValue(entity.jsonContent(), EquipmentResponse.class),
        TaskContext.of("NexonApi", "DeserializeCache", entity.ocid()),
        e ->
            new EquipmentDataProcessingException(
                "JSON 역직렬화 실패 [ocid=" + entity.ocid() + "]: " + e.getMessage(), e));
  }

  /**
   * 4xx 에러용 실패 Future 생성
   *
   * @param ocid 캐릭터 OCID
   * @return CompletableFuture<CharacterBasicResponse> - CharacterNotFoundException으로 실패
   */
  public <T> CompletableFuture<T> clientErrorFuture(String ocid) {
    return CompletableFuture.failedFuture(new CharacterNotFoundException(ocid));
  }

  /**
   * 5xx/장애용 실패 Future 생성 (Outbox 적재 포함)
   *
   * @param ocid 캐릭터 OCID
   * @param eventType API 이벤트 타입
   * @param t 원본 예외
   * @return CompletableFuture<T> - ExternalServiceException으로 실패
   */
  public <T> CompletableFuture<T> serverErrorFuture(
      String ocid,
      maple.expectation.domain.v2.NexonApiOutbox.NexonApiEventType eventType,
      Throwable t) {
    // Outbox Fallback: 5xx/장애 시에만 Outbox 적재 (4xx는 비즈니스 예외)
    String requestId = outboxFallbackManager.generateRequestId(eventType.name(), ocid);
    outboxFallbackManager.saveToOutbox(requestId, eventType, ocid);

    return CompletableFuture.failedFuture(new ExternalServiceException(SERVICE_NEXON, t));
  }

  /**
   * 일반 실패 Future 생성 (Outbox 미적재)
   *
   * @param t 원본 예외
   * @return CompletableFuture<T> - ExternalServiceException으로 실패
   */
  public <T> CompletableFuture<T> errorFuture(Throwable t) {
    return CompletableFuture.failedFuture(new ExternalServiceException(SERVICE_NEXON, t));
  }

  /**
   * OutboxFallbackManager 노출 (ResilientNexonApiClient에서 설정 위임용)
   *
   * @return OutboxFallbackManager 인스턴스
   */
  public OutboxFallbackManager getOutboxManager() {
    return outboxFallbackManager;
  }
}
