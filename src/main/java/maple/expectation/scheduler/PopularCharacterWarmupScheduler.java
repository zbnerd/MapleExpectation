package maple.expectation.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.service.v4.EquipmentExpectationServiceV4;
import maple.expectation.service.v4.warmup.PopularCharacterTracker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 인기 캐릭터 자동 웜업 스케줄러 (#275 Auto Warmup)
 *
 * <h3>기능</h3>
 *
 * <p>전날 인기 캐릭터 TOP N을 조회하여 캐시를 미리 웜업합니다. 서버 시작 시 또는 매일 새벽에 실행되어 Cold Start 문제를 해결합니다.
 *
 * <h3>실행 시점</h3>
 *
 * <ul>
 *   <li>매일 새벽 5시 (트래픽 낮은 시간)
 *   <li>서버 시작 후 30초 (초기 웜업)
 * </ul>
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Green (Performance): 웜업으로 첫 요청 지연 방지, RPS 향상
 *   <li>Blue (Architect): 분산 락으로 단일 인스턴스만 웜업 실행
 *   <li>Red (SRE): 요청 간 지연으로 Thundering Herd 방지
 *   <li>Yellow (QA): 성공/실패 메트릭으로 모니터링
 * </ul>
 *
 * <h3>설정</h3>
 *
 * <pre>
 * scheduler:
 *   warmup:
 *     enabled: true           # 웜업 활성화 여부
 *     top-count: 50           # 웜업할 상위 캐릭터 수
 *     delay-between-ms: 100   # 요청 간 지연 (ms)
 * </pre>
 *
 * @see PopularCharacterTracker 인기 캐릭터 트래커
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "scheduler.warmup.enabled",
    havingValue = "true",
    matchIfMissing = false // 명시적으로 활성화 필요
    )
public class PopularCharacterWarmupScheduler {

  private final PopularCharacterTracker popularCharacterTracker;
  private final EquipmentExpectationServiceV4 expectationService;
  private final LockStrategy lockStrategy;
  private final LogicExecutor executor;
  private final MeterRegistry meterRegistry;

  @Value("${scheduler.warmup.top-count:50}")
  private int topCount;

  @Value("${scheduler.warmup.delay-between-ms:100}")
  private long delayBetweenMs;

  /**
   * 매일 새벽 5시 웜업 실행
   *
   * <p>트래픽이 낮은 시간에 캐시를 미리 채워둡니다.
   */
  @Scheduled(cron = "0 0 5 * * *")
  public void dailyWarmup() {
    executeWarmup("DailyWarmup");
  }

  /**
   * 서버 시작 후 30초 뒤 초기 웜업
   *
   * <p>서버 재시작 후 Cold Cache 상태를 빠르게 해소합니다.
   */
  @Scheduled(initialDelay = 30000, fixedDelay = Long.MAX_VALUE)
  public void initialWarmup() {
    executeWarmup("InitialWarmup");
  }

  /**
   * 웜업 실행 (분산 락 사용)
   *
   * <p>여러 인스턴스 중 하나만 웜업을 실행하도록 분산 락을 사용합니다.
   *
   * @param warmupType 웜업 유형 (로깅용)
   */
  private void executeWarmup(String warmupType) {
    TaskContext context = TaskContext.of("Scheduler", "Warmup." + warmupType);

    executor.executeOrCatch(
        () -> {
          // 분산 락 획득 시도 (waitTime=0: 즉시 반환, leaseTime=300초)
          lockStrategy.executeWithLock(
              "popular-warmup-lock",
              0,
              300,
              () -> {
                doWarmup(warmupType);
                return null;
              });
          return null;
        },
        e -> {
          if (e instanceof maple.expectation.global.error.exception.DistributedLockException) {
            log.debug("[Warmup] {} skipped: another instance is warming up", warmupType);
          } else {
            log.error("[Warmup] {} failed: {}", warmupType, e.getMessage());
            meterRegistry
                .counter("warmup.execution", "type", warmupType, "status", "error")
                .increment();
          }
          return null;
        },
        context);
  }

  /**
   * 실제 웜업 로직
   *
   * @param warmupType 웜업 유형
   */
  private void doWarmup(String warmupType) {
    Timer.Sample sample = Timer.start(meterRegistry);
    log.info(
        "[Warmup] {} started at {} - warming up top {} characters",
        warmupType,
        LocalDateTime.now(),
        topCount);

    // 전날 인기 캐릭터 조회
    List<String> topCharacters = popularCharacterTracker.getYesterdayTopCharacters(topCount);

    if (topCharacters.isEmpty()) {
      log.info("[Warmup] {} completed - no characters to warm up (first day?)", warmupType);
      meterRegistry.counter("warmup.execution", "type", warmupType, "status", "empty").increment();
      return;
    }

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failCount = new AtomicInteger(0);

    for (String userIgn : topCharacters) {
      warmupCharacter(userIgn, successCount, failCount);

      // Thundering Herd 방지를 위한 요청 간 지연
      if (delayBetweenMs > 0) {
        sleep(delayBetweenMs);
      }
    }

    sample.stop(meterRegistry.timer("warmup.duration", "type", warmupType));
    meterRegistry.counter("warmup.execution", "type", warmupType, "status", "success").increment();
    meterRegistry.gauge("warmup.last.success_count", successCount);
    meterRegistry.gauge("warmup.last.fail_count", failCount);

    log.info(
        "[Warmup] {} completed - success: {}, fail: {}, total: {}",
        warmupType,
        successCount.get(),
        failCount.get(),
        topCharacters.size());
  }

  /**
   * 단일 캐릭터 웜업
   *
   * @param userIgn 캐릭터 닉네임
   * @param successCount 성공 카운터
   * @param failCount 실패 카운터
   */
  private void warmupCharacter(
      String userIgn, AtomicInteger successCount, AtomicInteger failCount) {
    executor.executeOrCatch(
        () -> {
          // V4 API 호출하여 캐시 채우기 (force=false로 기존 캐시 사용)
          expectationService.calculateExpectation(userIgn, false);
          successCount.incrementAndGet();
          log.debug("[Warmup] Warmed up: {}", maskIgn(userIgn));
          return null;
        },
        e -> {
          failCount.incrementAndGet();
          log.warn("[Warmup] Failed to warm up {}: {}", maskIgn(userIgn), e.getMessage());
          return null;
        },
        TaskContext.of("Warmup", "Character", userIgn));
  }

  /**
   * 지연 (Thread.sleep 래핑)
   *
   * <p><b>Section 14 Exception:</b> Thread.sleep is acceptable here for sequential warmup delays with
   * proper LogicExecutor wrapping. This is a synchronous delay in a sequential processing loop, not an
   * asynchronous scheduled task.
   *
   * @param millis 대기 시간 (밀리초)
   */
  private void sleep(long millis) {
    executor.executeOrDefault(
        () -> {
          Thread.sleep(millis);
          return null;
        },
        null,
        TaskContext.of("Warmup", "Delay"));
  }

  /** IGN 마스킹 (로깅용) */
  private String maskIgn(String ign) {
    if (ign == null || ign.length() < 2) return "***";
    return ign.charAt(0) + "***" + ign.substring(ign.length() - 1);
  }
}
