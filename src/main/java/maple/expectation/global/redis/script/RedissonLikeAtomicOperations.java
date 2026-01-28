package maple.expectation.global.redis.script;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;

/**
 * Redisson 기반 원자적 좋아요 연산 구현체
 *
 * <p>Lua Script를 사용하여 Redis의 싱글 스레드 특성을 활용한 원자적 연산을 제공합니다.</p>
 *
 * <h2>특징</h2>
 * <ul>
 *   <li>SHA 캐싱: scriptLoad() + evalSha()로 네트워크 오버헤드 최소화</li>
 *   <li>NOSCRIPT 자동 복구: Redis 재시작 시 스크립트 자동 재로드</li>
 *   <li>Fail-Fast 검증: 모든 public 메서드 첫 줄에서 입력값 검증</li>
 *   <li>Graceful Degradation: Redis 장애 시 false 반환 (서비스 가용성 유지)</li>
 *   <li>관측성: 실행 시간, 실패 횟수 메트릭 수집</li>
 * </ul>
 *
 * <h2>Hash Tag (CRITICAL FIX - PR #175, #164)</h2>
 * <p>모든 키는 {buffer:likes} Hash Tag를 사용하여 Redis Cluster CROSSSLOT 에러를 방지합니다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonLikeAtomicOperations implements LikeAtomicOperations {

    private final RedissonClient redissonClient;
    private final LuaScriptProvider scriptProvider;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;

    @Override
    public boolean atomicTransfer(String userIgn, long count) {
        validateInput(userIgn, count);

        // Section 12 준수: try-finally → executeWithFinally
        Timer.Sample sample = Timer.start(meterRegistry);
        return executor.executeWithFinally(
                () -> scriptProvider.executeWithNoscriptHandling(
                        scriptProvider::getTransferSha,
                        LuaScripts.ATOMIC_TRANSFER,
                        scriptProvider::updateTransferSha,
                        sha -> executeTransferScript(sha, userIgn, count),
                        "Transfer"
                ),
                () -> stopTimer(sample, "transfer"),
                TaskContext.of("AtomicOps", "TransferTimed", userIgn)
        );
    }

    @Override
    public long atomicDeleteAndDecrement(String tempKey, String userIgn, long count) {
        validateTempKey(tempKey);
        validateInput(userIgn, count);

        // Section 12 준수: try-finally → executeWithFinally
        Timer.Sample sample = Timer.start(meterRegistry);
        return executor.executeWithFinally(
                () -> scriptProvider.executeWithNoscriptHandling(
                        scriptProvider::getDeleteAndDecrementSha,
                        LuaScripts.ATOMIC_DELETE_AND_DECREMENT,
                        scriptProvider::updateDeleteAndDecrementSha,
                        sha -> executeDeleteAndDecrementScript(sha, tempKey, userIgn, count),
                        "DeleteAndDecrement"
                ),
                () -> stopTimer(sample, "deleteAndDecrement"),
                TaskContext.of("AtomicOps", "DeleteAndDecrementTimed", userIgn)
        );
    }

    @Override
    public boolean atomicCompensation(String tempKey, String userIgn, long count) {
        validateTempKey(tempKey);
        validateInput(userIgn, count);

        // Section 12 준수: try-finally → executeWithFinally
        Timer.Sample sample = Timer.start(meterRegistry);
        return executor.executeWithFinally(
                () -> executeCompensationWithMetrics(tempKey, userIgn, count),
                () -> stopTimer(sample, "compensation"),
                TaskContext.of("AtomicOps", "CompensationTimed", userIgn)
        );
    }

    /**
     * Compensation 실행 및 메트릭 기록 (Section 15: 람다 추출)
     */
    private boolean executeCompensationWithMetrics(String tempKey, String userIgn, long count) {
        boolean result = scriptProvider.executeWithNoscriptHandling(
                scriptProvider::getCompensationSha,
                LuaScripts.ATOMIC_COMPENSATION,
                scriptProvider::updateCompensationSha,
                sha -> executeCompensationScript(sha, tempKey, userIgn, count),
                "Compensation"
        );

        if (result) {
            meterRegistry.counter("like.sync.compensation.count").increment();
            log.info("♻️ [Compensation] 복구 완료: {} ({}건)", userIgn, count);
        }

        return result;
    }

    private boolean executeTransferScript(String sha, String userIgn, long count) {
        return executor.executeOrDefault(
                () -> {
                    RScript script = redissonClient.getScript(StringCodec.INSTANCE);
                    Long result = script.evalSha(
                            RScript.Mode.READ_WRITE,
                            sha,
                            RScript.ReturnType.INTEGER,
                            Arrays.asList(LuaScripts.Keys.HASH, LuaScripts.Keys.TOTAL_COUNT),
                            userIgn, String.valueOf(count)
                    );
                    return result != null && result == 1L;
                },
                false,
                TaskContext.of("AtomicOps", "Transfer", userIgn)
        );
    }

    private long executeDeleteAndDecrementScript(String sha, String tempKey, String userIgn, long count) {
        return executor.executeOrDefault(
                () -> {
                    RScript script = redissonClient.getScript(StringCodec.INSTANCE);
                    Long result = script.evalSha(
                            RScript.Mode.READ_WRITE,
                            sha,
                            RScript.ReturnType.INTEGER,
                            Arrays.asList(tempKey, LuaScripts.Keys.TOTAL_COUNT),
                            userIgn, String.valueOf(count)
                    );
                    return result != null ? result : 0L;
                },
                0L,
                TaskContext.of("AtomicOps", "DeleteAndDecrement", userIgn)
        );
    }

    private boolean executeCompensationScript(String sha, String tempKey, String userIgn, long count) {
        return executor.executeOrDefault(
                () -> {
                    RScript script = redissonClient.getScript(StringCodec.INSTANCE);
                    Long result = script.evalSha(
                            RScript.Mode.READ_WRITE,
                            sha,
                            RScript.ReturnType.INTEGER,
                            Arrays.asList(LuaScripts.Keys.HASH, tempKey),
                            userIgn, String.valueOf(count)
                    );
                    return result != null && result == 1L;
                },
                false,
                TaskContext.of("AtomicOps", "Compensation", userIgn)
        );
    }

    /**
     * Timer 중지 헬퍼 (Section 15: 메서드 참조 분리)
     *
     * @param sample Timer 샘플
     * @param scriptName 스크립트 이름 (메트릭 태그)
     */
    private void stopTimer(Timer.Sample sample, String scriptName) {
        sample.stop(meterRegistry.timer("like.sync.lua.duration", "script", scriptName));
    }

    /**
     * 입력값 검증 (Fail-Fast)
     *
     * @param userIgn 사용자 식별자
     * @param count 증가/감소할 값
     * @throws IllegalArgumentException 검증 실패 시
     */
    private void validateInput(String userIgn, long count) {
        Objects.requireNonNull(userIgn, "userIgn must not be null");
        if (userIgn.isBlank()) {
            throw new IllegalArgumentException("userIgn must not be blank");
        }
        if (count <= 0 || count > MAX_INCREMENT_PER_OPERATION) {
            throw new IllegalArgumentException(
                    "count must be between 1 and " + MAX_INCREMENT_PER_OPERATION + ", but was: " + count
            );
        }
    }

    /**
     * 임시 키 검증
     *
     * @param tempKey 동기화 임시 키
     * @throws IllegalArgumentException 검증 실패 시
     */
    private void validateTempKey(String tempKey) {
        Objects.requireNonNull(tempKey, "tempKey must not be null");
        if (tempKey.isBlank()) {
            throw new IllegalArgumentException("tempKey must not be blank");
        }
    }
}
