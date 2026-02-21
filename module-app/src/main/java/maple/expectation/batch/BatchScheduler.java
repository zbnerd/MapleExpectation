package maple.expectation.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.InternalSystemException;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.function.CheckedRunnable;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Spring Batch Scheduler for Equipment Refresh Job
 *
 * <h3>기능</h3>
 *
 * <ul>
 *   <li>매일 새벽 2시에 전체 유저 장비 데이터 갱신 Job 실행
 *   <li>JobParameters에 timestamp 추가 (Job 재실행 가능하도록)
 *   <li>Cron 표현식은 application.yml에서 설정 가능
 * </ul>
 *
 * <h3>Cron 표현식</h3>
 *
 * <pre>{@code
 * 0 0 2 * * *  (초 분 시 일 월 요일)
 * }</pre>
 *
 * <h3>CLAUDE.md 준수사항</h3>
 *
 * <ul>
 *   <li>Section 12: LogicExecutor 사용 (예외 처리)
 *   <li>Section 15: 람다 3줄 초과 시 Method 추출
 *   <li>Stateless: 상태 없음
 *   <li>메서드 참조 우선: JobLauncher::run 형태
 * </ul>
 *
 * @see <a href="https://docs.spring.io/spring-batch/docs/current/reference/html/index.html">Spring
 *     Batch Documentation</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchScheduler {

  private final JobLauncher jobLauncher;
  private final Job equipmentRefreshJob;
  private final LogicExecutor executor;
  private final CheckedLogicExecutor checkedExecutor;

  // Checked exception to RuntimeException mapper
  private static final java.util.function.Function<Exception, RuntimeException>
      BATCH_EXCEPTION_MAPPER = e -> new InternalSystemException("BatchScheduler:LaunchJob", e);

  @Value("${batch.equipment-refresh.cron:0 0 2 * * *}")
  private String cronExpression;

  /**
   * 매일 새벽 2시에 전체 유저 장비 데이터 갱신
   *
   * <p>Cron: 0 0 2 * * * (초 분 시 일 월 요일)
   *
   * <p>JobParameters에 timestamp를 추가하여 Spring Batch가 매번 새로운 JobInstance로 인식하도록 합니다.
   */
  @Scheduled(cron = "${batch.equipment-refresh.cron:0 0 2 * * *}")
  public void runEquipmentRefreshJob() {
    TaskContext context = TaskContext.of("BatchScheduler", "EquipmentRefresh");

    executor.executeVoid(
        () -> {
          log.info("[BatchScheduler] Starting equipment refresh job with cron: {}", cronExpression);
          launchJob();
        },
        context);
  }

  /** Job 실행 (Method Extraction - Section 15: Lambda Hell 방지) */
  private void launchJob() {
    checkedExecutor.executeUncheckedVoid(
        new CheckedRunnable() {
          @Override
          public void run() {
            try {
              JobParameters params =
                  new JobParametersBuilder()
                      .addLong("timestamp", System.currentTimeMillis())
                      .toJobParameters();

              log.debug("[BatchScheduler] Launching job with params: {}", params);
              jobLauncher.run(equipmentRefreshJob, params);
              log.info("[BatchScheduler] Equipment refresh job launched successfully");
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
        },
        TaskContext.of("BatchScheduler", "LaunchJob"),
        BATCH_EXCEPTION_MAPPER);
  }
}
