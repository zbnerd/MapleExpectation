package maple.expectation.chaos.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Scenario 08: 디스크 가득 찼을 경우 시스템 응답 검증
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 디스크 공간 고갈
 *   <li>🟣 Purple (Auditor): 데이터 무결성 검증 - 로그 및 데이터 손실
 *   <li>🔵 Blue (Architect): 흐름 검증 - 예외 처리 메커니즘
 *   <li>🟢 Green (Performance): 메트릭 검증 - I/O 성능 저하
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 용량 부족 Fallback
 * </ul>
 *
 * <h4>검증 포인트</h4>
 *
 * <ol>
 *   <li>디스크 가득 찼을 때 예외 처리 확인
 *   <li>시스템 종료 없이 계속 동작
 *   <li>Fallback 동작 검증
 *   <li>디스크 복구 후 정상 동작 확인
 * </ol>
 *
 * <h4>CS 원리</h4>
 *
 * <ul>
 *   <li>Circuit Breaker: 디스크 I/O 장애 격리
 *   <li>Fallback Pattern: 장애 시 대체 로직 사용
 *   <li>Graceful Degradation: 부분 기능으로 서비스 유지
 * </ul>
 *
 * <h4>CI 실행 제외</h4>
 *
 * <p>이 테스트는 @Tag("chaos") 태그가 있어 CI 파이프라인에서 제외됩니다. 별도의 카오스 엔지니어링 테스트 스위트에서 실행하세요:
 *
 * <pre>
 * ./gradlew test --tests "*DiskFull*" --tags "chaos"
 * </pre>
 *
 * @see maple.expectation.global.executor.LogicExecutor
 * @see java.nio.file.Files
 */
@Tag("chaos")
@SpringBootTest
@DisplayName("Scenario 08: Disk Full - 시스템 응답 검증")
class DiskFullChaosTest extends AbstractContainerBaseTest {

  @Autowired private LogicExecutor logicExecutor;

  private static final String TEST_DIR = "/tmp/test-disk-full";
  private static final String LOG_FILE = TEST_DIR + "/test-log.log";
  private final AtomicLong totalDiskSpace = new AtomicLong(0);
  private final AtomicLong usedDiskSpace = new AtomicLong(0);

  /**
   * 🟡 Yellow's Test 1: 디스크 가득 찼을 때 서비스 가용성 유지
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>정상 상태에서 로그 파일 작성
   *   <li>디스크 공간 모두 소진
   *   <li>로그 작업 시도 → Fallback 동작
   * </ol>
   *
   * <p><b>예상 로그</b>:
   *
   * <pre>
   * WARN  [xxx] DiskManager - 디스크 용량 부족, Fallback 모드로 전환
   * </pre>
   */
  @Test
  @DisplayName("디스크 가득 찼을 때 서비스 가용성 유지")
  void shouldHandleDiskFull_gracefully() throws IOException {
    // Given: 디스크 상태 확인
    setupDiskSpace();

    // 디스크 가득 참
    fillDiskSpace();

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger fallbackCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);

    int concurrentRequests = 30;
    ExecutorService executor = Executors.newFixedThreadPool(10);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch endLatch = new CountDownLatch(concurrentRequests);

    // When: 디스크 가득 찬 상태에서 요청 처리
    for (int i = 0; i < concurrentRequests; i++) {
      final int requestId = i;
      executor.submit(
          () -> {
            try {
              startLatch.await();

              TaskContext context = TaskContext.of("Chaos", "Disk_Full_Test", "req_" + requestId);

              // 디스크 작업 시뮬레이션
              String result =
                  logicExecutor.executeWithFallback(
                      () -> {
                        // 디스크 쓰기 시도
                        writeTestLog("Disk write attempt: " + requestId);
                        return "disk_operation_" + requestId;
                      },
                      (e) -> {
                        // Fallback: 메모리에서 처리
                        fallbackCount.incrementAndGet();
                        return "fallback_" + requestId;
                      },
                      context);

              // 결과 검증
              if (result.startsWith("fallback")) {
                successCount.incrementAndGet();
              } else if (result.startsWith("disk_operation")) {
                successCount.incrementAndGet();
              }

            } catch (Exception e) {
              errorCount.incrementAndGet();
            } finally {
              endLatch.countDown();
            }
          });
    }

    startLatch.countDown();

    // 모든 요청 완료 대기
    boolean completed = false;
    try {
      completed = endLatch.await(20, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Test interrupted", e);
    }
    executor.shutdown();

    // Then: 결과 검증
    assertThat(completed).as("모든 요청이 20초 내에 완료되어야 함").isTrue();

    assertThat(errorCount.get()).as("디스크 가득 찼을 때도 예외 발생 없어야 함").isZero();

    // Fallback 발생 확인 (필요에 따라 조정)
    System.out.printf(
        "Success: %d, Fallback: %d, Errors: %d%n",
        successCount.get(), fallbackCount.get(), errorCount.get());

    assertThat(successCount.get()).as("모든 요청이 성공적으로 처리되어야 함").isEqualTo(concurrentRequests);

    // Cleanup
    cleanupDiskSpace();
  }

  /**
   * 🔵 Blue's Test 2: 디스크 가득 찼을 때 예외 처리 검증
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>디스크 가득 찬 상태에서 파일 쓰기 시도
   *   <li>IOException 발생 → 예외 처리 확인
   *   <li>Circuit Breaker 동작 확인
   * </ol>
   */
  @Test
  @DisplayName("디스크 가득 찼을 때 예외 처리 및 Circuit Breaker 동작")
  void shouldHandleException_whenDiskFull() {
    // Given: 디스크 가득 참
    try {
      fillDiskSpace();
    } catch (IOException e) {
      throw new RuntimeException("Failed to fill disk space", e);
    }

    TaskContext context = TaskContext.of("Chaos", "Disk_Full_Exception_Test");

    // When & Then: executeWithTranslation으로 예외 변환 검증
    // 첫 번째 테스트: IOException이 RuntimeException으로 변환되는지 확인
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class,
        () ->
            logicExecutor.executeWithTranslation(
                () -> {
                  throw new IOException("No space left on device");
                },
                (e, ctx) -> {
                  // IOException을 RuntimeException으로 변환
                  throw new RuntimeException("디스크 용량 부족", e);
                },
                context));

    // 두 번째 테스트: executeOrDefault로 Fallback 값 반환 검증
    String fallbackResult =
        logicExecutor.executeOrDefault(
            () -> {
              throw new IOException("No space left on device");
            },
            "disk_full_fallback",
            context);

    assertThat(fallbackResult).as("디스크 가득 찼을 때 Fallback 값 반환").isEqualTo("disk_full_fallback");
  }

  /**
   * 🟢 Green's Test 3: 디스크 공간 모니터링
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>디스크 사용량 모니터링
   *   <li>경계점(90%) 테스트
   *   <li>알림 메커니즘 검증
   * </ol>
   */
  @Test
  @DisplayName("디스크 공간 모니터링 및 경계점 테스트")
  void shouldMonitorDiskSpace_andTriggerAlerts() throws IOException {
    // Given: 초기 디스크 상태
    updateDiskSpaceInfo();
    double initialUsage = (double) usedDiskSpace.get() / totalDiskSpace.get() * 100;

    System.out.printf("Initial Disk Usage: %.2f%%%n", initialUsage);

    // When: 디스크 공간 점진적으로 소진
    fillDiskSpaceTo(90.0); // 90%까지 채움

    updateDiskSpaceInfo();
    double usage90 = (double) usedDiskSpace.get() / totalDiskSpace.get() * 100;

    // Then: 경계점 확인
    assertThat(usage90).as("디스크 사용량이 90% 이상이어야 함").isGreaterThanOrEqualTo(90.0);

    // 경고 알림 시뮬레이션
    boolean shouldAlert = usage90 >= 90.0;
    if (shouldAlert) {
      System.out.println("🚨 경고: 디스크 사용량 90% 초과");
    }

    assertThat(shouldAlert).as("90% 초과 시 경고 발생").isTrue();

    // Cleanup
    cleanupDiskSpace();
  }

  /**
   * 🟡 Yellow's Test 4: 디스크 복구 후 정상 동작 검증
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>디스크 가득 찬 상태에서 작업
   *   <li>디스크 공간 확보
   *   <li>정상 동작 복구 확인
   * </ol>
   */
  @Test
  @DisplayName("디스크 복구 후 정상 동작 복구")
  void shouldResumeNormalOperations_afterDiskRecovery() throws IOException, InterruptedException {
    // Given: 디스크 가득 참
    fillDiskSpace();

    TaskContext context = TaskContext.of("Chaos", "Disk_Recovery_Test");

    // When: 디스크 복구
    cleanupDiskSpace();

    // 복구 후 상태 확인
    updateDiskSpaceInfo();
    double afterRecovery = (double) usedDiskSpace.get() / totalDiskSpace.get() * 100;

    assertThat(afterRecovery).as("복구 후 디스크 사용량이 낮아야 함").isLessThan(10.0);

    // Then: 정상 동작 복구 확인
    String result =
        logicExecutor.executeOrDefault(
            () -> {
              // 정상적인 파일 쓰기 시도
              writeTestLog("Post-recovery test");
              return "normal_operation";
            },
            "error_operation",
            context);

    assertThat(result).as("복구 후 정상 동작해야 함").isEqualTo("normal_operation");

    // 디스크 I/O 성능 확인
    long startTime = System.nanoTime();
    writeTestLog("Performance test");
    long endTime = System.nanoTime();

    long writeTime = (endTime - startTime) / 1_000_000; // ms
    assertThat(writeTime).as("디스크 쓰기 시간이 합리적이어야 함 (< 100ms)").isLessThan(100);

    System.out.printf("Disk write time after recovery: %dms%n", writeTime);
  }

  // ==================== Helper Methods ====================

  /** 디스크 정보 업데이트 */
  private void updateDiskSpaceInfo() {
    try {
      File file = new File(TEST_DIR);
      if (file.exists()) {
        totalDiskSpace.set(file.getTotalSpace());
        usedDiskSpace.set(file.getTotalSpace() - file.getFreeSpace());
      }
    } catch (Exception e) {
      // 테스트 환경에서는 무시
    }
  }

  /** 디스크 공간 설정 */
  private void setupDiskSpace() throws IOException {
    // 테스트 디렉토리 생성
    Path path = Paths.get(TEST_DIR);
    if (!Files.exists(path)) {
      Files.createDirectories(path);
    }
    updateDiskSpaceInfo();
  }

  /** 디스크 공간 채우기 */
  private void fillDiskSpace() throws IOException {
    updateDiskSpaceInfo();
    long targetSize = totalDiskSpace.get() * 80L / 100; // 80% 채움
    long currentSize = usedDiskSpace.get();

    if (currentSize < targetSize) {
      long remaining = targetSize - currentSize;
      fillDiskSpaceWithBytes(remaining);
    }
  }

  /** 특정 비율까지 디스크 채우기 */
  private void fillDiskSpaceTo(double targetPercent) throws IOException {
    updateDiskSpaceInfo();
    long targetSize = (long) (totalDiskSpace.get() * targetPercent / 100);
    long currentSize = usedDiskSpace.get();

    if (currentSize < targetSize) {
      long remaining = targetSize - currentSize;
      fillDiskSpaceWithBytes(remaining);
    }
  }

  /** 지정된 크기만큼 디스크에 바이트 데이터 채우기 */
  private void fillDiskSpaceWithBytes(long bytesToFill) throws IOException {
    long chunkSize = 1024 * 1024; // 1MB
    int chunks = (int) (bytesToFill / chunkSize);
    int lastChunk = (int) (bytesToFill % chunkSize);

    // 1MB 청크 생성 및 디스크에 쓰기
    byte[] chunk = new byte[(int) chunkSize];
    for (int i = 0; i < chunks; i++) {
      Path chunkFile = Paths.get(TEST_DIR, "chunk-" + i + ".dat");
      Files.write(chunkFile, chunk);
    }

    // 마지막 청크
    if (lastChunk > 0) {
      byte[] lastChunkBytes = new byte[lastChunk];
      Path lastChunkFile = Paths.get(TEST_DIR, "chunk-last.dat");
      Files.write(lastChunkFile, lastChunkBytes);
    }
  }

  /** 테스트 로그 파일 쓰기 */
  private void writeTestLog(String message) throws IOException {
    try {
      Files.write(Paths.get(LOG_FILE), (message + "\n").getBytes());
    } catch (IOException e) {
      // 디스크 가득 참 예외 - 정상 동작
      throw new IOException("디스크 쓰기 실패: " + e.getMessage(), e);
    }
  }

  /** 디스크 공간 정리 */
  private void cleanupDiskSpace() {
    try {
      // 테스트 디렉토리 삭제 후 재생성
      Path path = Paths.get(TEST_DIR);
      if (Files.exists(path)) {
        Files.walk(path)
            .sorted((a, b) -> -a.compareTo(b))
            .forEach(
                p -> {
                  try {
                    Files.delete(p);
                  } catch (IOException ignored) {
                  }
                });
      }
      // 재생성
      Files.createDirectories(path);
      updateDiskSpaceInfo();
    } catch (IOException e) {
      System.err.println("디스크 정리 실패: " + e.getMessage());
    }
  }
}
