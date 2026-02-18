package maple.expectation.infrastructure.external.impl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.ExternalServiceException;
import maple.expectation.infrastructure.alert.StatelessAlertService;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.util.ExceptionUtils;

/**
 * Alert Notification Helper - 알림 발송을 담당하는 전담 클래스
 *
 * <h4>책임</h4>
 *
 * <ul>
 *   <li>Best-effort 알림 발송 (비동기)
 *   <li>알림 실패 처리 (RejectedExecutionException 등)
 *   <li>예외 변환 (Checked → Unchecked)
 * </ul>
 *
 * <h4>설계 의도</h4>
 *
 * <p>알림은 부가 기능이므로, 실패해도 메인 흐름에 영향을 주지 않도록 best-effort로 처리
 */
@Slf4j
@RequiredArgsConstructor
public class AlertNotificationHelper {

  private final StatelessAlertService statelessAlertService;
  private final CheckedLogicExecutor checkedExecutor;
  private final Executor alertTaskExecutor;

  /** 외부 서비스명 상수 (메트릭/로그 키 일관성) */
  private static final String SERVICE_DISCORD = "Discord";

  /**
   * 알림 발송 (best-effort)
   *
   * <p>알림 실패가 fallback의 반환 계약을 깨지 않도록 비동기 분리 + 예외 흡수
   *
   * <p>전용 alertTaskExecutor 사용: commonPool 오염/경합 방지
   *
   * <h4>예외 처리 정책</h4>
   *
   * <ul>
   *   <li><b>RejectedExecutionException</b>: 정책적 드롭 → DEBUG (정상 시나리오)
   *   <li><b>기타 예외</b>: 실제 알림 실패 → WARN
   * </ul>
   *
   * @param ocid 대상 캐릭터 OCID
   * @param alertCause 알림 원인 예외
   */
  public void sendAlertBestEffort(String ocid, Exception alertCause) {
    CompletableFuture.runAsync(
            () ->
                checkedExecutor.executeUncheckedVoid(
                    () ->
                        statelessAlertService.sendCritical(
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
}
