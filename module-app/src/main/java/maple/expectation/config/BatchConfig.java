package maple.expectation.config;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch Configuration for Equipment Refresh Job (Issue #356)
 *
 * <h3>Components</h3>
 *
 * <ul>
 *   <li>equipmentRefreshJob: Main batch job
 *   <li>ocidRefreshStep: Step for OCID refresh (chunk size: 100)
 * </ul>
 *
 * <h4>CLAUDE.md 준수사항</h4>
 *
 * <ul>
 *   <li>Section 12: LogicExecutor 사용 (try-catch 금지)
 *   <li>Section 15: 람다 3줄 초과 시 Method Reference 추출
 *   <li>Stateless: Job/Step 상태는 Spring Batch 메타데이터에 저장
 * </ul>
 *
 * @see maple.expectation.batch.reader.OcidReader
 * @see maple.expectation.batch.writer.LowPriorityQueueWriter
 */
@Configuration
public class BatchConfig {

  private static final int CHUNK_SIZE = 100;

  /**
   * Equipment Refresh Job Bean
   *
   * <p>OCID를 조회하여 LOW Priority Queue에 추가하는 배치 Job
   *
   * @param ocidRefreshStep OCID 갱신 스텝
   * @param jobRepository Spring Batch Job Repository
   * @return Job 인스턴스
   */
  @Bean
  public Job equipmentRefreshJob(Step ocidRefreshStep, JobRepository jobRepository) {
    return new JobBuilder("equipmentRefreshJob", jobRepository).start(ocidRefreshStep).build();
  }

  /**
   * OCID Refresh Step Bean
   *
   * <p>Chunk size: 100 (OCID 조회 후 Queue에 일괄 추가)
   *
   * <p>Note: ResourcelessTransactionManager 사용 (DB 트랜잭션 없이 Queue에만 추가)
   *
   * @param ocidReader ItemReader for OCID 조회
   * @param queueWriter ItemWriter for LOW Priority Queue 추가
   * @param jobRepository Spring Batch Job Repository
   * @param transactionManager Platform Transaction Manager
   * @return Step 인스턴스
   */
  @Bean
  public Step ocidRefreshStep(
      ItemReader<String> ocidReader,
      ItemWriter<String> queueWriter,
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager) {
    return new StepBuilder("ocidRefreshStep", jobRepository)
        .<String, String>chunk(CHUNK_SIZE, transactionManager)
        .reader(ocidReader)
        .writer(queueWriter)
        .build();
  }
}
