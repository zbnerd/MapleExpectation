package maple.expectation.global.queue.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.queue.BufferLuaScripts;
import maple.expectation.global.queue.MessageQueueStrategy;
import maple.expectation.global.queue.QueueMessage;
import maple.expectation.global.queue.QueueType;
import maple.expectation.global.queue.RedisKey;
import maple.expectation.global.queue.script.BufferLuaScriptProvider;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 기반 메시지 큐 전략 (V5 Stateless Architecture)
 *
 * <h3>V5 Stateless Architecture (#271)</h3>
 * <p>Redis Cluster에서 원자적 큐 연산을 제공하는 Reliable Queue 패턴 구현</p>
 *
 * <h3>GPT-5 Iteration 4 반영 (CRITICAL)</h3>
 * <ul>
 *   <li>(A) ACK는 msgId 기반 - payload 직렬화 불일치 방지</li>
 *   <li>(B) Payload Store 분리 - Re-drive 시 복원 가능</li>
 *   <li>(C) Retry ZSET 메커니즘 - Delayed Retry 지원</li>
 *   <li>(1) ACK/Redrive 레이스 Lua 원자화</li>
 * </ul>
 *
 * <h3>Reliable Queue 패턴</h3>
 * <pre>
 * Main Queue: {expectation}:buffer (List of msgId)
 * Inflight:   {expectation}:buffer:inflight (List of msgId)
 * Inflight TS: {expectation}:buffer:inflight:ts (ZSET: score=timestamp)
 * Payload:    {expectation}:buffer:payload (HASH: msgId → JSON)
 * Retry:      {expectation}:buffer:retry (ZSET: score=nextAttemptAt)
 * DLQ:        {expectation}:buffer:dlq (List)
 * </pre>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Blue (Architect): Reliable Queue 패턴으로 At-Least-Once 보장</li>
 *   <li>Green (Performance): Lua Script로 RTT 최소화 (6 commands → 1 RTT)</li>
 *   <li>Purple (Auditor): 멱등성 보장으로 중복 처리 방지</li>
 *   <li>Yellow (QA): Testcontainers 통합 테스트 가능</li>
 *   <li>Red (SRE): DLQ + Circuit Breaker로 장애 격리</li>
 * </ul>
 *
 * @param <T> 메시지 페이로드 타입
 * @see MessageQueueStrategy 전략 인터페이스
 * @see BufferLuaScripts Lua 스크립트 상수
 */
@Slf4j
public class RedisBufferStrategy<T> implements MessageQueueStrategy<T> {

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long BASE_RETRY_DELAY_MS = 1000L;  // 1초

    private final RedissonClient redissonClient;
    private final BufferLuaScriptProvider scriptProvider;
    private final ObjectMapper objectMapper;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;
    private final Class<T> payloadType;

    /** 설정 */
    private final int maxRetries;

    /** Redis 키 (Hash Tag 포함) */
    private final String mainQueueKey;
    private final String inflightKey;
    private final String inflightTsKey;
    private final String payloadKey;
    private final String retryKey;
    private final String dlqKey;

    /** 카운터 캐시 (성능 최적화) */
    private final AtomicLong cachedPendingCount = new AtomicLong(0);
    private final AtomicLong cachedInflightCount = new AtomicLong(0);
    private final AtomicLong cachedRetryCount = new AtomicLong(0);
    private final AtomicLong cachedDlqCount = new AtomicLong(0);

    /** Shutdown 플래그 */
    private volatile boolean shuttingDown = false;

    /**
     * 생성자 (Expectation 버퍼용)
     *
     * @param redissonClient Redis 클라이언트
     * @param scriptProvider Lua 스크립트 제공자
     * @param objectMapper   JSON 직렬화기
     * @param executor       로직 실행기
     * @param meterRegistry  메트릭 레지스트리
     * @param payloadType    페이로드 클래스 타입
     */
    public RedisBufferStrategy(
            RedissonClient redissonClient,
            BufferLuaScriptProvider scriptProvider,
            ObjectMapper objectMapper,
            LogicExecutor executor,
            MeterRegistry meterRegistry,
            Class<T> payloadType) {
        this(redissonClient, scriptProvider, objectMapper, executor, meterRegistry, payloadType, DEFAULT_MAX_RETRIES);
    }

    /**
     * 생성자 (maxRetries 커스텀)
     */
    public RedisBufferStrategy(
            RedissonClient redissonClient,
            BufferLuaScriptProvider scriptProvider,
            ObjectMapper objectMapper,
            LogicExecutor executor,
            MeterRegistry meterRegistry,
            Class<T> payloadType,
            int maxRetries) {

        this.redissonClient = redissonClient;
        this.scriptProvider = scriptProvider;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.payloadType = payloadType;
        this.maxRetries = maxRetries;

        // Redis 키 초기화 (Hash Tag 패턴)
        this.mainQueueKey = RedisKey.EXPECTATION_BUFFER.getKey();
        this.inflightKey = RedisKey.EXPECTATION_BUFFER_INFLIGHT.getKey();
        this.inflightTsKey = RedisKey.EXPECTATION_BUFFER_INFLIGHT_TS.getKey();
        this.payloadKey = RedisKey.EXPECTATION_BUFFER_PAYLOAD.getKey();
        this.retryKey = RedisKey.EXPECTATION_BUFFER_RETRY.getKey();
        this.dlqKey = RedisKey.EXPECTATION_BUFFER_DLQ.getKey();

        registerMetrics();
        log.info("[RedisBufferStrategy] 초기화 완료 - maxRetries={}", maxRetries);
    }

    /**
     * 메트릭 등록
     */
    private void registerMetrics() {
        String strategyTag = getType().name();

        Gauge.builder("queue.pending", cachedPendingCount, AtomicLong::get)
                .tag("strategy", strategyTag)
                .description("대기 중인 메시지 수")
                .register(meterRegistry);

        Gauge.builder("queue.inflight", cachedInflightCount, AtomicLong::get)
                .tag("strategy", strategyTag)
                .description("처리 중인 메시지 수")
                .register(meterRegistry);

        Gauge.builder("queue.retry", cachedRetryCount, AtomicLong::get)
                .tag("strategy", strategyTag)
                .description("재시도 대기 중인 메시지 수")
                .register(meterRegistry);

        Gauge.builder("queue.dlq", cachedDlqCount, AtomicLong::get)
                .tag("strategy", strategyTag)
                .description("DLQ 메시지 수")
                .register(meterRegistry);
    }

    @Override
    public String publish(T message) {
        // Shutdown 중이면 거부
        if (shuttingDown) {
            meterRegistry.counter("queue.publish.rejected", "strategy", getType().name(), "reason", "shutdown").increment();
            log.debug("[RedisBufferStrategy] Rejected during shutdown");
            return null;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        String msgId = UUID.randomUUID().toString();

        String result = scriptProvider.executeWithNoscriptHandling(
                scriptProvider::getPublishSha,
                BufferLuaScripts.PUBLISH,
                scriptProvider::updatePublishSha,
                sha -> executePublishScript(sha, msgId, message),
                "Publish"
        );

        sample.stop(meterRegistry.timer("queue.publish.duration", "strategy", getType().name()));

        if (result != null) {
            cachedPendingCount.incrementAndGet();
            meterRegistry.counter("queue.publish.success", "strategy", getType().name()).increment();
            log.debug("[RedisBufferStrategy] Published message: msgId={}", msgId);
        }

        return result;
    }

    private String executePublishScript(String sha, String msgId, T message) {
        return executor.executeOrDefault(
                () -> {
                    String payloadJson = objectMapper.writeValueAsString(
                            new PayloadWrapper<>(message, 0, Instant.now().toEpochMilli())
                    );

                    RScript script = redissonClient.getScript(StringCodec.INSTANCE);
                    script.evalSha(
                            RScript.Mode.READ_WRITE,
                            sha,
                            RScript.ReturnType.INTEGER,
                            Arrays.asList(mainQueueKey, payloadKey),
                            msgId, payloadJson
                    );
                    return msgId;
                },
                null,
                TaskContext.of("RedisBuffer", "Publish", msgId)
        );
    }

    @Override
    public List<QueueMessage<T>> consume(int batchSize) {
        Timer.Sample sample = Timer.start(meterRegistry);

        List<QueueMessage<T>> result = scriptProvider.executeWithNoscriptHandling(
                scriptProvider::getConsumeSha,
                BufferLuaScripts.CONSUME,
                scriptProvider::updateConsumeSha,
                sha -> executeConsumeScript(sha, batchSize),
                "Consume"
        );

        sample.stop(meterRegistry.timer("queue.consume.duration", "strategy", getType().name()));

        if (!result.isEmpty()) {
            cachedPendingCount.addAndGet(-result.size());
            cachedInflightCount.addAndGet(result.size());
            meterRegistry.counter("queue.consume.success", "strategy", getType().name())
                    .increment(result.size());
            log.debug("[RedisBufferStrategy] Consumed {} messages", result.size());
        }

        return result;
    }

    private List<QueueMessage<T>> executeConsumeScript(String sha, int batchSize) {
        return executor.executeOrDefault(
                () -> {
                    RScript script = redissonClient.getScript(StringCodec.INSTANCE);
                    long timestamp = System.currentTimeMillis();

                    @SuppressWarnings("unchecked")
                    List<List<String>> rawResult = (List<List<String>>) script.evalSha(
                            RScript.Mode.READ_WRITE,
                            sha,
                            RScript.ReturnType.MULTI,
                            Arrays.asList(mainQueueKey, inflightKey, inflightTsKey, payloadKey),
                            String.valueOf(batchSize), String.valueOf(timestamp)
                    );

                    return convertToQueueMessages(rawResult);
                },
                List.of(),
                TaskContext.of("RedisBuffer", "Consume", String.valueOf(batchSize))
        );
    }

    private List<QueueMessage<T>> convertToQueueMessages(List<List<String>> rawResult) {
        List<QueueMessage<T>> messages = new ArrayList<>();

        for (List<String> entry : rawResult) {
            if (entry.size() >= 2) {
                String msgId = entry.get(0);
                String payloadJson = entry.get(1);

                QueueMessage<T> queueMessage = deserializePayload(msgId, payloadJson);
                if (queueMessage != null) {
                    messages.add(queueMessage);
                }
            }
        }

        return messages;
    }

    private QueueMessage<T> deserializePayload(String msgId, String payloadJson) {
        return executor.executeOrDefault(
                () -> {
                    PayloadWrapper<T> wrapper = objectMapper.readValue(
                            payloadJson,
                            objectMapper.getTypeFactory().constructParametricType(PayloadWrapper.class, payloadType)
                    );
                    return new QueueMessage<>(
                            msgId,
                            wrapper.payload(),
                            wrapper.retryCount(),
                            Instant.ofEpochMilli(wrapper.createdAtMs())
                    );
                },
                null,
                TaskContext.of("RedisBuffer", "Deserialize", msgId)
        );
    }

    @Override
    public void ack(String msgId) {
        Timer.Sample sample = Timer.start(meterRegistry);

        Long removed = scriptProvider.executeWithNoscriptHandling(
                scriptProvider::getAckSha,
                BufferLuaScripts.ACK,
                scriptProvider::updateAckSha,
                sha -> executeAckScript(sha, msgId),
                "Ack"
        );

        sample.stop(meterRegistry.timer("queue.ack.duration", "strategy", getType().name()));

        if (removed != null && removed > 0) {
            cachedInflightCount.decrementAndGet();
            meterRegistry.counter("queue.ack.success", "strategy", getType().name()).increment();
            log.debug("[RedisBufferStrategy] ACK message: msgId={}", msgId);
        } else {
            meterRegistry.counter("queue.ack.not_found", "strategy", getType().name()).increment();
            log.debug("[RedisBufferStrategy] ACK message not found (already acked): msgId={}", msgId);
        }
    }

    private Long executeAckScript(String sha, String msgId) {
        return executor.executeOrDefault(
                () -> {
                    RScript script = redissonClient.getScript(StringCodec.INSTANCE);
                    return script.evalSha(
                            RScript.Mode.READ_WRITE,
                            sha,
                            RScript.ReturnType.INTEGER,
                            Arrays.asList(inflightKey, inflightTsKey, payloadKey),
                            msgId
                    );
                },
                0L,
                TaskContext.of("RedisBuffer", "Ack", msgId)
        );
    }

    @Override
    public void nack(String msgId, int retryCount) {
        Timer.Sample sample = Timer.start(meterRegistry);

        if (retryCount >= maxRetries) {
            // DLQ로 이동
            scriptProvider.executeWithNoscriptHandling(
                    scriptProvider::getNackToDlqSha,
                    BufferLuaScripts.NACK_TO_DLQ,
                    scriptProvider::updateNackToDlqSha,
                    sha -> executeNackToDlqScript(sha, msgId),
                    "NackToDlq"
            );

            cachedInflightCount.decrementAndGet();
            cachedDlqCount.incrementAndGet();
            meterRegistry.counter("queue.nack.dlq", "strategy", getType().name()).increment();
            log.warn("[RedisBufferStrategy] Message moved to DLQ after {} retries: msgId={}", maxRetries, msgId);
        } else {
            // Retry Queue로 이동 (Exponential Backoff)
            long nextAttemptAt = System.currentTimeMillis() + calculateBackoffDelay(retryCount);

            scriptProvider.executeWithNoscriptHandling(
                    scriptProvider::getNackToRetrySha,
                    BufferLuaScripts.NACK_TO_RETRY,
                    scriptProvider::updateNackToRetrySha,
                    sha -> executeNackToRetryScript(sha, msgId, nextAttemptAt, retryCount),
                    "NackToRetry"
            );

            cachedInflightCount.decrementAndGet();
            cachedRetryCount.incrementAndGet();
            meterRegistry.counter("queue.nack.retry", "strategy", getType().name()).increment();
            log.debug("[RedisBufferStrategy] Message scheduled for retry: msgId={}, retryCount={}", msgId, retryCount + 1);
        }

        sample.stop(meterRegistry.timer("queue.nack.duration", "strategy", getType().name()));
    }

    private Long executeNackToDlqScript(String sha, String msgId) {
        return executor.executeOrDefault(
                () -> {
                    RScript script = redissonClient.getScript(StringCodec.INSTANCE);
                    return script.evalSha(
                            RScript.Mode.READ_WRITE,
                            sha,
                            RScript.ReturnType.INTEGER,
                            Arrays.asList(inflightKey, inflightTsKey, dlqKey),
                            msgId
                    );
                },
                0L,
                TaskContext.of("RedisBuffer", "NackToDlq", msgId)
        );
    }

    private Long executeNackToRetryScript(String sha, String msgId, long nextAttemptAt, int retryCount) {
        return executor.executeOrDefault(
                () -> {
                    // 업데이트된 payload (retryCount 증가)
                    String currentPayloadJson = getPayload(msgId);
                    if (currentPayloadJson == null) {
                        log.warn("[RedisBufferStrategy] Payload not found for NACK: msgId={}", msgId);
                        return 0L;
                    }

                    PayloadWrapper<T> currentWrapper = objectMapper.readValue(
                            currentPayloadJson,
                            objectMapper.getTypeFactory().constructParametricType(PayloadWrapper.class, payloadType)
                    );

                    PayloadWrapper<T> updatedWrapper = new PayloadWrapper<>(
                            currentWrapper.payload(),
                            retryCount + 1,
                            currentWrapper.createdAtMs()
                    );

                    String updatedPayloadJson = objectMapper.writeValueAsString(updatedWrapper);

                    RScript script = redissonClient.getScript(StringCodec.INSTANCE);
                    return script.evalSha(
                            RScript.Mode.READ_WRITE,
                            sha,
                            RScript.ReturnType.INTEGER,
                            Arrays.asList(inflightKey, inflightTsKey, retryKey, payloadKey),
                            msgId, String.valueOf(nextAttemptAt), String.valueOf(retryCount + 1), updatedPayloadJson
                    );
                },
                0L,
                TaskContext.of("RedisBuffer", "NackToRetry", msgId)
        );
    }

    private String getPayload(String msgId) {
        return executor.executeOrDefault(
                () -> {
                    return redissonClient.<String, String>getMap(payloadKey, StringCodec.INSTANCE).get(msgId);
                },
                null,
                TaskContext.of("RedisBuffer", "GetPayload", msgId)
        );
    }

    /**
     * Exponential Backoff 지연 계산
     *
     * @param retryCount 현재 재시도 횟수
     * @return 지연 시간 (ms)
     */
    private long calculateBackoffDelay(int retryCount) {
        return BASE_RETRY_DELAY_MS * (1L << retryCount);  // 1s, 2s, 4s, ...
    }

    @Override
    public long getPendingCount() {
        refreshQueueCounts();
        return cachedPendingCount.get();
    }

    @Override
    public long getInflightCount() {
        refreshQueueCounts();
        return cachedInflightCount.get();
    }

    @Override
    public long getRetryCount() {
        refreshQueueCounts();
        return cachedRetryCount.get();
    }

    @Override
    public long getDlqCount() {
        refreshQueueCounts();
        return cachedDlqCount.get();
    }

    /**
     * 큐 카운트 갱신 (Lua Script 호출)
     */
    private void refreshQueueCounts() {
        scriptProvider.executeWithNoscriptHandling(
                scriptProvider::getGetQueueCountsSha,
                BufferLuaScripts.GET_QUEUE_COUNTS,
                scriptProvider::updateGetQueueCountsSha,
                this::executeGetQueueCountsScript,
                "GetQueueCounts"
        );
    }

    private Boolean executeGetQueueCountsScript(String sha) {
        return executor.executeOrDefault(
                () -> {
                    RScript script = redissonClient.getScript(StringCodec.INSTANCE);
                    @SuppressWarnings("unchecked")
                    List<Long> counts = (List<Long>) script.evalSha(
                            RScript.Mode.READ_ONLY,
                            sha,
                            RScript.ReturnType.MULTI,
                            Arrays.asList(mainQueueKey, inflightKey, retryKey, dlqKey)
                    );

                    if (counts != null && counts.size() >= 4) {
                        cachedPendingCount.set(counts.get(0));
                        cachedInflightCount.set(counts.get(1));
                        cachedRetryCount.set(counts.get(2));
                        cachedDlqCount.set(counts.get(3));
                    }
                    return true;
                },
                false,
                TaskContext.of("RedisBuffer", "GetQueueCounts")
        );
    }

    @Override
    public QueueType getType() {
        return QueueType.REDIS_LIST;
    }

    @Override
    public boolean isHealthy() {
        if (shuttingDown) {
            return false;
        }

        return executor.executeOrDefault(
                () -> {
                    redissonClient.getBucket("health-check").isExists();
                    return true;
                },
                false,
                TaskContext.of("RedisBuffer", "HealthCheck")
        );
    }

    @Override
    public void prepareShutdown() {
        this.shuttingDown = true;
        log.info("[RedisBufferStrategy] Shutdown prepared - new publish will be rejected");
    }

    @Override
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    // ==================== Additional Methods (Re-drive, Retry Queue Processing) ====================

    /**
     * 만료된 INFLIGHT 메시지 조회
     *
     * @param timeoutMs 타임아웃 (ms)
     * @param limit     최대 조회 개수
     * @return 만료된 msgId 목록
     */
    public List<String> getExpiredInflightMessages(long timeoutMs, int limit) {
        long maxTimestamp = System.currentTimeMillis() - timeoutMs;

        return scriptProvider.executeWithNoscriptHandling(
                scriptProvider::getGetExpiredInflightSha,
                BufferLuaScripts.GET_EXPIRED_INFLIGHT,
                scriptProvider::updateGetExpiredInflightSha,
                sha -> executeGetExpiredInflightScript(sha, maxTimestamp, limit),
                "GetExpiredInflight"
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> executeGetExpiredInflightScript(String sha, long maxTimestamp, int limit) {
        return executor.executeOrDefault(
                () -> {
                    RScript script = redissonClient.getScript(StringCodec.INSTANCE);
                    return (List<String>) script.evalSha(
                            RScript.Mode.READ_ONLY,
                            sha,
                            RScript.ReturnType.MULTI,
                            List.of(inflightTsKey),
                            String.valueOf(maxTimestamp), String.valueOf(limit)
                    );
                },
                List.of(),
                TaskContext.of("RedisBuffer", "GetExpiredInflight")
        );
    }

    /**
     * INFLIGHT → Main Queue 복귀 (Re-drive)
     *
     * @param msgId 복귀할 메시지 ID
     * @return true: 복귀 성공, false: 이미 ACK됨
     */
    public boolean redrive(String msgId) {
        Long result = scriptProvider.executeWithNoscriptHandling(
                scriptProvider::getRedriveSha,
                BufferLuaScripts.REDRIVE,
                scriptProvider::updateRedriveSha,
                sha -> executeRedriveScript(sha, msgId),
                "Redrive"
        );

        if (result != null && result > 0) {
            cachedInflightCount.decrementAndGet();
            cachedPendingCount.incrementAndGet();
            meterRegistry.counter("queue.redrive.success", "strategy", getType().name()).increment();
            log.info("[RedisBufferStrategy] Message redriven: msgId={}", msgId);
            return true;
        } else {
            meterRegistry.counter("queue.redrive.skip", "strategy", getType().name()).increment();
            log.debug("[RedisBufferStrategy] Redrive skipped (already acked): msgId={}", msgId);
            return false;
        }
    }

    private Long executeRedriveScript(String sha, String msgId) {
        return executor.executeOrDefault(
                () -> {
                    RScript script = redissonClient.getScript(StringCodec.INSTANCE);
                    return script.evalSha(
                            RScript.Mode.READ_WRITE,
                            sha,
                            RScript.ReturnType.INTEGER,
                            Arrays.asList(inflightKey, inflightTsKey, mainQueueKey),
                            msgId
                    );
                },
                0L,
                TaskContext.of("RedisBuffer", "Redrive", msgId)
        );
    }

    /**
     * Retry Queue 처리 (만료된 Retry → Main Queue)
     *
     * @param limit 최대 처리 개수
     * @return 처리된 msgId 목록
     */
    public List<String> processRetryQueue(int limit) {
        long now = System.currentTimeMillis();

        @SuppressWarnings("unchecked")
        List<String> processed = scriptProvider.executeWithNoscriptHandling(
                scriptProvider::getProcessRetryQueueSha,
                BufferLuaScripts.PROCESS_RETRY_QUEUE,
                scriptProvider::updateProcessRetryQueueSha,
                sha -> executeProcessRetryQueueScript(sha, now, limit),
                "ProcessRetryQueue"
        );

        if (!processed.isEmpty()) {
            cachedRetryCount.addAndGet(-processed.size());
            cachedPendingCount.addAndGet(processed.size());
            meterRegistry.counter("queue.retry.processed", "strategy", getType().name())
                    .increment(processed.size());
            log.info("[RedisBufferStrategy] Processed {} retry messages", processed.size());
        }

        return processed;
    }

    @SuppressWarnings("unchecked")
    private List<String> executeProcessRetryQueueScript(String sha, long now, int limit) {
        return executor.executeOrDefault(
                () -> {
                    RScript script = redissonClient.getScript(StringCodec.INSTANCE);
                    return (List<String>) script.evalSha(
                            RScript.Mode.READ_WRITE,
                            sha,
                            RScript.ReturnType.MULTI,
                            Arrays.asList(retryKey, mainQueueKey),
                            String.valueOf(now), String.valueOf(limit)
                    );
                },
                List.of(),
                TaskContext.of("RedisBuffer", "ProcessRetryQueue")
        );
    }

    /**
     * DLQ 메시지 조회 (재처리용)
     *
     * @param maxCount 최대 조회 개수
     * @return DLQ 메시지 목록
     */
    public List<QueueMessage<T>> pollDlq(int maxCount) {
        List<QueueMessage<T>> messages = new ArrayList<>();

        return executor.executeOrDefault(
                () -> {
                    for (int i = 0; i < maxCount; i++) {
                        String msgId = redissonClient.<String>getDeque(dlqKey, StringCodec.INSTANCE).pollFirst();
                        if (msgId == null) {
                            break;
                        }

                        String payloadJson = getPayload(msgId);
                        QueueMessage<T> queueMessage = deserializePayload(msgId, payloadJson);
                        if (queueMessage != null) {
                            messages.add(queueMessage);
                            cachedDlqCount.decrementAndGet();
                        }
                    }
                    return messages;
                },
                messages,
                TaskContext.of("RedisBuffer", "PollDlq")
        );
    }

    /**
     * 최대 재시도 횟수 조회
     *
     * @return maxRetries
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    // ==================== Inner Classes ====================

    /**
     * Payload 래퍼 (retryCount, createdAt 포함)
     */
    private record PayloadWrapper<T>(
            T payload,
            int retryCount,
            long createdAtMs
    ) {}
}
