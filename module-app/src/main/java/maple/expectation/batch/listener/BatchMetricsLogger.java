package maple.expectation.batch.listener;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

/**
 * Spring Batch JobExecutionListener for logging job lifecycle metrics.
 *
 * <h3>Functionality</h3>
 *
 * <ul>
 *   <li>Logs job start with initial metrics
 *   <li>Logs job completion with duration and status
 *   <li>Records execution time using Micrometer Timer
 * </ul>
 *
 * <h3>CLAUDE.md Compliance</h3>
 *
 * <ul>
 *   <li>Section 12: LogicExecutor pattern for exception handling
 *   <li>Section 15: Lambda limit - extracted private methods
 *   <li>Stateless: No mutable instance state
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchMetricsLogger implements JobExecutionListener {

  private final LogicExecutor executor;
  private final MeterRegistry meterRegistry;

  private static final String TIMER_NAME = "batch.equipment.refresh.duration";
  private static final String TAG_JOB_NAME = "job_name";
  private static final String TAG_STATUS = "status";

  @Override
  public void beforeJob(JobExecution jobExecution) {
    TaskContext context = TaskContext.of("Batch", "MetricsLogger", "beforeJob");

    executor.executeVoid(
        () -> {
          String jobName = jobExecution.getJobInstance().getJobName();
          Instant startTime = jobExecution.getStartTime().toInstant(ZoneOffset.UTC);

          logJobStart(jobName, startTime);
          recordJobStartMetrics(jobExecution);
        },
        context);
  }

  @Override
  public void afterJob(JobExecution jobExecution) {
    TaskContext context = TaskContext.of("Batch", "MetricsLogger", "afterJob");

    executor.executeVoid(
        () -> {
          String jobName = jobExecution.getJobInstance().getJobName();
          String status = jobExecution.getStatus().name();
          Instant startTime = jobExecution.getStartTime().toInstant(ZoneOffset.UTC);
          Instant endTime = jobExecution.getEndTime().toInstant(ZoneOffset.UTC);

          Duration duration = calculateDuration(startTime, endTime);
          recordJobTimer(jobName, status, duration);
          logJobCompletion(jobName, status, duration, jobExecution);
        },
        context);
  }

  /** Calculate job execution duration. */
  private Duration calculateDuration(Instant startTime, Instant endTime) {
    return Duration.between(startTime, endTime);
  }

  /** Record job execution time using Micrometer Timer. */
  private void recordJobTimer(String jobName, String status, Duration duration) {
    Timer.builder(TIMER_NAME)
        .tag(TAG_JOB_NAME, jobName)
        .tag(TAG_STATUS, status)
        .description("Batch job execution duration")
        .register(meterRegistry)
        .record(duration);
  }

  /** Log job start message. */
  private void logJobStart(String jobName, Instant startTime) {
    log.info("[BatchMetricsLogger] Job started: {} at {}", jobName, startTime);
  }

  /** Record job start metrics. */
  private void recordJobStartMetrics(JobExecution jobExecution) {
    String jobName = jobExecution.getJobInstance().getJobName();

    meterRegistry.counter("batch.equipment.jobs.started", "job_name", jobName).increment();

    log.debug("[BatchMetricsLogger] Job start metrics recorded for: {}", jobName);
  }

  /** Log job completion message with status and duration. */
  private void logJobCompletion(
      String jobName, String status, Duration duration, JobExecution jobExecution) {
    log.info(
        "[BatchMetricsLogger] Job completed: {} with status {} in {}ms",
        jobName,
        status,
        duration.toMillis());

    if (jobExecution.getStatus().isUnsuccessful()) {
      log.error(
          "[BatchMetricsLogger] Job failed with exceptions: {}",
          jobExecution.getFailureExceptions());
    }
  }
}
