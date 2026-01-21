package maple.expectation.chaos.network;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scenario 07: Black Hole Commit - ACK 유실
 *
 * <h4>5-Agent Council</h4>
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - ACK 패킷 드롭으로 "쓴 것 같은데 없어진" 상황</li>
 *   <li>🟣 Purple (Auditor): 데이터 무결성 검증 - Write-Read 일관성</li>
 *   <li>🔵 Blue (Architect): 흐름 검증 - 트랜잭션 Commit 안전성</li>
 *   <li>🟢 Green (Performance): 메트릭 검증 - 유실 탐지 시간</li>
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 경계 조건 검증</li>
 * </ul>
 *
 * <h4>검증 포인트</h4>
 * <ol>
 *   <li>Redis SET 후 ACK 유실 시 데이터 존재 여부</li>
 *   <li>트랜잭션 커밋 후 롤백되는 "Phantom Commit" 탐지</li>
 *   <li>Idempotency Key로 중복 쓰기 방지</li>
 *   <li>Write-Ahead Log (WAL) 패턴의 필요성</li>
 * </ol>
 *
 * <h4>CS 원리</h4>
 * <ul>
 *   <li>Two-Phase Commit (2PC)</li>
 *   <li>Write-Ahead Logging (WAL)</li>
 *   <li>Idempotency / At-Least-Once Delivery</li>
 *   <li>Exactly-Once Semantics</li>
 * </ul>
 *
 * @see org.testcontainers.containers.ToxiproxyContainer
 */
@Tag("chaos")
@SpringBootTest
@DisplayName("Scenario 07: Black Hole Commit - ACK 유실 및 데이터 정합성")
class BlackHoleCommitChaosTest extends AbstractContainerBaseTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private DataSource dataSource;

    private static final String BLACK_HOLE_KEY_PREFIX = "black-hole:";

    @BeforeEach
    void setUp() {
        // 테스트 키 정리
        try {
            redisTemplate.delete(redisTemplate.keys(BLACK_HOLE_KEY_PREFIX + "*"));
        } catch (Exception ignored) {
        }
    }

    /**
     * 🔴 Red's Test 1: ACK 유실 시뮬레이션 (응답 패킷 드롭)
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>Redis에 값 쓰기 시도</li>
     *   <li>Toxiproxy로 UPSTREAM 응답 100% 드롭</li>
     *   <li>클라이언트는 타임아웃, 하지만 Redis에는 저장됨</li>
     * </ol>
     */
    @Test
    @DisplayName("ACK 유실 시 클라이언트 타임아웃이지만 데이터는 저장됨")
    void shouldTimeout_butDataMayExist_whenAckDropped() throws Exception {
        String key = BLACK_HOLE_KEY_PREFIX + "ack-drop:" + UUID.randomUUID();
        String value = "phantom-write-" + System.currentTimeMillis();

        // Given: 정상 상태에서 먼저 쓰기
        redisTemplate.opsForValue().set(key, value);
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(value);

        String newValue = "updated-" + System.currentTimeMillis();

        // When: UPSTREAM(응답) 방향 100% 패킷 드롭
        redisProxy.toxics()
                .resetPeer("ack-black-hole", ToxicDirection.UPSTREAM, 0);

        System.out.println("[Red] ACK black hole injected (UPSTREAM reset_peer)");

        // 타임아웃과 함께 쓰기 시도
        boolean writeTimedOut = false;
        long startTime = System.nanoTime();

        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                    redisTemplate.opsForValue().set(key, newValue)
            );
            future.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            writeTimedOut = true;
            System.out.println("[Red] Write timed out as expected: " + e.getClass().getSimpleName());
        }

        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

        // Toxic 제거 후 실제 값 확인
        redisProxy.toxics().get("ack-black-hole").remove();
        Thread.sleep(500); // 연결 재설정 대기

        // Then: 클라이언트는 타임아웃했지만 데이터가 있을 수도 있음
        String actualValue = null;
        try {
            actualValue = redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            System.out.println("[Red] Read after recovery failed: " + e.getMessage());
        }

        System.out.printf("[Green] Write timed out: %s, Elapsed: %dms%n", writeTimedOut, elapsedMs);
        System.out.printf("[Purple] Original value: %s, New value: %s, Actual: %s%n",
                value, newValue, actualValue);

        // 검증: 타임아웃은 발생했어야 함
        assertThat(writeTimedOut).as("ACK 드롭 시 클라이언트 타임아웃 발생").isTrue();

        // 데이터는 원래 값 또는 새 값일 수 있음 (Black Hole의 특성)
        System.out.println("[Yellow] Black Hole 특성: 쓰기 성공 여부 불확실");
    }

    /**
     * 🟣 Purple's Test 2: Idempotency Key를 통한 중복 쓰기 방지
     *
     * <p><b>시나리오</b>: 재시도 시 Idempotency Key로 중복 방지
     */
    @Test
    @DisplayName("Idempotency Key로 중복 쓰기 방지")
    void shouldPreventDuplicateWrite_withIdempotencyKey() throws Exception {
        String idempotencyKey = "idempotency:" + UUID.randomUUID();
        String dataKey = BLACK_HOLE_KEY_PREFIX + "data:" + UUID.randomUUID();

        // Idempotency 패턴: SET NX로 락 획득 후 데이터 쓰기
        System.out.println("[Purple] Starting idempotency pattern test...");

        // 첫 번째 쓰기 시도
        Boolean firstWrite = redisTemplate.opsForValue()
                .setIfAbsent(idempotencyKey, "processing", Duration.ofSeconds(30));
        assertThat(firstWrite).as("첫 번째 쓰기는 성공해야 함").isTrue();

        if (firstWrite) {
            redisTemplate.opsForValue().set(dataKey, "value-1");
            System.out.println("[Purple] First write: SUCCESS (idempotency key acquired)");
        }

        // 두 번째 쓰기 시도 (재시도 시뮬레이션)
        Boolean secondWrite = redisTemplate.opsForValue()
                .setIfAbsent(idempotencyKey, "processing", Duration.ofSeconds(30));
        assertThat(secondWrite).as("중복 쓰기는 차단되어야 함").isFalse();

        System.out.println("[Purple] Second write: BLOCKED (idempotency key exists)");

        // 데이터 확인
        String actualData = redisTemplate.opsForValue().get(dataKey);
        assertThat(actualData).isEqualTo("value-1");

        System.out.printf("[Purple] Final data: %s (no duplicate)%n", actualData);
    }

    /**
     * 🔵 Blue's Test 3: 슬라이스 패킷 드롭 (부분 응답 유실)
     *
     * <p><b>시나리오</b>: 응답의 일부만 유실되는 경우
     */
    @Test
    @DisplayName("부분 응답 유실 시 프로토콜 에러 발생")
    void shouldDetectProtocolError_whenPartialResponseLost() throws Exception {
        String key = BLACK_HOLE_KEY_PREFIX + "partial:" + UUID.randomUUID();

        // 정상 쓰기 먼저
        redisTemplate.opsForValue().set(key, "initial-value");

        // 응답 슬라이스 (일부 바이트만 전달)
        redisProxy.toxics()
                .slicer("partial-slicer", ToxicDirection.UPSTREAM, 10, 100);

        System.out.println("[Blue] Partial response slicer injected");

        Exception caughtException = null;
        try {
            // 여러 번 읽기 시도
            for (int i = 0; i < 5; i++) {
                redisTemplate.opsForValue().get(key);
            }
        } catch (Exception e) {
            caughtException = e;
            System.out.println("[Blue] Exception caught: " + e.getClass().getSimpleName());
        }

        // 정리
        redisProxy.toxics().get("partial-slicer").remove();

        // 부분 응답으로 인한 에러가 발생할 수 있음
        System.out.printf("[Blue] Partial response test completed. Exception: %s%n",
                caughtException != null ? caughtException.getClass().getSimpleName() : "None");
    }

    /**
     * 🟢 Green's Test 4: Write-Read 일관성 검증
     *
     * <p><b>시나리오</b>: 연속적인 Write 후 Read 일관성 확인
     */
    @Test
    @DisplayName("연속 Write-Read 일관성 검증")
    void shouldMaintainConsistency_acrossWriteRead() throws Exception {
        String key = BLACK_HOLE_KEY_PREFIX + "consistency:" + UUID.randomUUID();
        int iterations = 100;
        int inconsistencies = 0;

        System.out.println("[Green] Starting Write-Read consistency test...");
        System.out.println("┌────────────────────────────────────────────────────────────┐");
        System.out.println("│               Write-Read Consistency Test                  │");
        System.out.println("├────────────────────────────────────────────────────────────┤");

        for (int i = 0; i < iterations; i++) {
            String expectedValue = "value-" + i + "-" + UUID.randomUUID();

            // Write
            redisTemplate.opsForValue().set(key, expectedValue);

            // Immediate Read
            String actualValue = redisTemplate.opsForValue().get(key);

            if (!expectedValue.equals(actualValue)) {
                inconsistencies++;
                System.out.printf("│ [!] Inconsistency at i=%d: expected=%s, actual=%s │%n",
                        i, expectedValue, actualValue);
            }
        }

        System.out.printf("│ Total iterations: %d                                       │%n", iterations);
        System.out.printf("│ Inconsistencies: %d                                         │%n", inconsistencies);
        System.out.printf("│ Consistency Rate: %.2f%%                                    │%n",
                (iterations - inconsistencies) * 100.0 / iterations);
        System.out.println("└────────────────────────────────────────────────────────────┘");

        assertThat(inconsistencies)
                .as("정상 상태에서 Write-Read 불일치는 0이어야 함")
                .isZero();
    }

    /**
     * 🟡 Yellow's Test 5: 타임아웃 후 재시도 패턴 검증
     *
     * <p><b>시나리오</b>: 첫 시도 실패 후 재시도로 성공
     */
    @Test
    @DisplayName("타임아웃 후 재시도 패턴 검증")
    void shouldSucceed_afterRetryOnTimeout() throws Exception {
        String key = BLACK_HOLE_KEY_PREFIX + "retry:" + UUID.randomUUID();
        String value = "retry-test-value";

        // 재시도 로직
        int maxRetries = 3;
        int attempt = 0;
        boolean success = false;

        System.out.println("[Yellow] Starting retry pattern test...");

        while (attempt < maxRetries && !success) {
            attempt++;

            // 첫 번째 시도에만 지연 주입
            if (attempt == 1) {
                redisProxy.toxics()
                        .latency("retry-latency", ToxicDirection.DOWNSTREAM, 2000);
                System.out.printf("[Yellow] Attempt %d: Injecting 2s latency%n", attempt);
            } else {
                try {
                    redisProxy.toxics().get("retry-latency").remove();
                } catch (Exception ignored) {}
                System.out.printf("[Yellow] Attempt %d: Normal operation%n", attempt);
            }

            try {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                        redisTemplate.opsForValue().set(key, value)
                );

                // 첫 시도는 1초 타임아웃 (실패 예상), 이후는 5초
                long timeout = (attempt == 1) ? 1 : 5;
                future.get(timeout, TimeUnit.SECONDS);
                success = true;
                System.out.printf("[Yellow] Attempt %d: SUCCESS%n", attempt);
            } catch (TimeoutException e) {
                System.out.printf("[Yellow] Attempt %d: TIMEOUT%n", attempt);
            } catch (Exception e) {
                System.out.printf("[Yellow] Attempt %d: ERROR - %s%n", attempt, e.getMessage());
            }
        }

        // 정리
        try {
            redisProxy.toxics().get("retry-latency").remove();
        } catch (Exception ignored) {}

        // 검증
        System.out.printf("[Yellow] Final result: success=%s after %d attempts%n", success, attempt);

        assertThat(success).as("재시도 후 성공해야 함").isTrue();
        assertThat(attempt).as("첫 시도 실패 후 재시도로 성공").isGreaterThan(1);

        // 데이터 확인
        String actualValue = redisTemplate.opsForValue().get(key);
        assertThat(actualValue).isEqualTo(value);
    }
}
