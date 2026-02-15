package maple.expectation.external.impl;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.NexonApiOutbox;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.repository.NexonApiOutboxRepository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox Fallback Manager - 실패한 API 호출을 Outbox에 적재하는 전담 클래스
 *
 * <h4>책임</h4>
 *
 * <ul>
 *   <li>멱등성 Request ID 생성
 *   <li>Outbox 적재 (비동기)
 *   <li>중복 체크 (idempotent insert)
 *   <li>PII 마스킹
 *   <li>활성화/비활성화 제어
 * </ul>
 *
 * <h4>N19 Outbox Fallback 패턴</h4>
 *
 * <p>장애 시 Outbox에 적재하여 나중에 재시도 (장기 장애 6시간 대응 가능)
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxFallbackManager {

  private final NexonApiOutboxRepository outboxRepository;
  private final CheckedLogicExecutor checkedExecutor;
  private final TransactionTemplate transactionTemplate;
  private final java.util.concurrent.Executor alertTaskExecutor;

  /** Outbox Fallback 활성화 여부 (YAML 설정 가능) */
  private volatile boolean enabled = true;

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
  public void saveToOutbox(
      String requestId, NexonApiOutbox.NexonApiEventType eventType, String payload) {
    if (!enabled) {
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
  public String generateRequestId(String eventType, String payload) {
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
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    log.info("[Outbox] Fallback 설정 변경: enabled={}", enabled);
  }

  /**
   * Outbox Fallback 활성화 여부 조회
   *
   * @return 활성화 여부
   */
  public boolean isEnabled() {
    return enabled;
  }
}
