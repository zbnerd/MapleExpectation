package maple.expectation.global.queue.script;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.exception.RedisScriptExecutionException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.queue.BufferLuaScripts;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Buffer Lua Script SHA 캐싱 및 NOSCRIPT 에러 핸들링 제공자
 *
 * <h3>V5 Stateless Architecture (#271)</h3>
 * <p>Redis Buffer 연산을 위한 Lua Script SHA 해시를 캐싱하고,
 * NOSCRIPT 에러 발생 시 자동 재로드합니다.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>AtomicReference를 사용하여 volatile 레이스 컨디션을 방지합니다.</p>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Green (Performance): SHA 캐싱으로 네트워크 오버헤드 최소화</li>
 *   <li>Red (SRE): NOSCRIPT 자동 복구로 Redis 재시작 대응</li>
 *   <li>Purple (Auditor): AtomicReference로 동시성 안전성 보장</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BufferLuaScriptProvider {

    private final RedissonClient redissonClient;
    private final LogicExecutor executor;

    private static final String NOSCRIPT_ERROR_PREFIX = "NOSCRIPT";

    // SHA 캐시 (원자적 업데이트)
    private final AtomicReference<String> publishShaRef = new AtomicReference<>();
    private final AtomicReference<String> consumeShaRef = new AtomicReference<>();
    private final AtomicReference<String> ackShaRef = new AtomicReference<>();
    private final AtomicReference<String> nackToRetryShaRef = new AtomicReference<>();
    private final AtomicReference<String> nackToDlqShaRef = new AtomicReference<>();
    private final AtomicReference<String> redriveShaRef = new AtomicReference<>();
    private final AtomicReference<String> processRetryQueueShaRef = new AtomicReference<>();
    private final AtomicReference<String> getExpiredInflightShaRef = new AtomicReference<>();
    private final AtomicReference<String> getQueueCountsShaRef = new AtomicReference<>();

    /**
     * 애플리케이션 시작 시 모든 Buffer Lua Script를 Redis에 로드 (웜업)
     *
     * <p>Graceful Degradation: Redis 연결 실패해도 애플리케이션 시작에 영향 없음.
     * 첫 호출 시 Lazy Loading으로 스크립트 로드 재시도.</p>
     */
    @PostConstruct
    public void loadScripts() {
        boolean loaded = executor.executeOrDefault(() -> {
            RScript script = redissonClient.getScript(StringCodec.INSTANCE);

            publishShaRef.set(script.scriptLoad(BufferLuaScripts.PUBLISH));
            consumeShaRef.set(script.scriptLoad(BufferLuaScripts.CONSUME));
            ackShaRef.set(script.scriptLoad(BufferLuaScripts.ACK));
            nackToRetryShaRef.set(script.scriptLoad(BufferLuaScripts.NACK_TO_RETRY));
            nackToDlqShaRef.set(script.scriptLoad(BufferLuaScripts.NACK_TO_DLQ));
            redriveShaRef.set(script.scriptLoad(BufferLuaScripts.REDRIVE));
            processRetryQueueShaRef.set(script.scriptLoad(BufferLuaScripts.PROCESS_RETRY_QUEUE));
            getExpiredInflightShaRef.set(script.scriptLoad(BufferLuaScripts.GET_EXPIRED_INFLIGHT));
            getQueueCountsShaRef.set(script.scriptLoad(BufferLuaScripts.GET_QUEUE_COUNTS));

            log.info("[BufferLuaScriptProvider] SHA 캐싱 완료 - 9개 스크립트 로드");
            return true;
        }, false, TaskContext.of("BufferLuaScript", "LoadAll"));

        if (!loaded) {
            log.warn("[BufferLuaScriptProvider] 시작 시 스크립트 로드 실패 - 첫 호출 시 Lazy Loading 시도");
        }
    }

    // ==================== SHA Getters ====================

    public String getPublishSha() {
        return publishShaRef.updateAndGet(current ->
                current != null ? current : reloadScript(BufferLuaScripts.PUBLISH, "Publish"));
    }

    public String getConsumeSha() {
        return consumeShaRef.updateAndGet(current ->
                current != null ? current : reloadScript(BufferLuaScripts.CONSUME, "Consume"));
    }

    public String getAckSha() {
        return ackShaRef.updateAndGet(current ->
                current != null ? current : reloadScript(BufferLuaScripts.ACK, "Ack"));
    }

    public String getNackToRetrySha() {
        return nackToRetryShaRef.updateAndGet(current ->
                current != null ? current : reloadScript(BufferLuaScripts.NACK_TO_RETRY, "NackToRetry"));
    }

    public String getNackToDlqSha() {
        return nackToDlqShaRef.updateAndGet(current ->
                current != null ? current : reloadScript(BufferLuaScripts.NACK_TO_DLQ, "NackToDlq"));
    }

    public String getRedriveSha() {
        return redriveShaRef.updateAndGet(current ->
                current != null ? current : reloadScript(BufferLuaScripts.REDRIVE, "Redrive"));
    }

    public String getProcessRetryQueueSha() {
        return processRetryQueueShaRef.updateAndGet(current ->
                current != null ? current : reloadScript(BufferLuaScripts.PROCESS_RETRY_QUEUE, "ProcessRetryQueue"));
    }

    public String getGetExpiredInflightSha() {
        return getExpiredInflightShaRef.updateAndGet(current ->
                current != null ? current : reloadScript(BufferLuaScripts.GET_EXPIRED_INFLIGHT, "GetExpiredInflight"));
    }

    public String getGetQueueCountsSha() {
        return getQueueCountsShaRef.updateAndGet(current ->
                current != null ? current : reloadScript(BufferLuaScripts.GET_QUEUE_COUNTS, "GetQueueCounts"));
    }

    // ==================== SHA Updaters ====================

    public void updatePublishSha(String sha) {
        publishShaRef.set(sha);
    }

    public void updateConsumeSha(String sha) {
        consumeShaRef.set(sha);
    }

    public void updateAckSha(String sha) {
        ackShaRef.set(sha);
    }

    public void updateNackToRetrySha(String sha) {
        nackToRetryShaRef.set(sha);
    }

    public void updateNackToDlqSha(String sha) {
        nackToDlqShaRef.set(sha);
    }

    public void updateRedriveSha(String sha) {
        redriveShaRef.set(sha);
    }

    public void updateProcessRetryQueueSha(String sha) {
        processRetryQueueShaRef.set(sha);
    }

    public void updateGetExpiredInflightSha(String sha) {
        getExpiredInflightShaRef.set(sha);
    }

    public void updateGetQueueCountsSha(String sha) {
        getQueueCountsShaRef.set(sha);
    }

    // ==================== NOSCRIPT Handling ====================

    /**
     * NOSCRIPT 에러 발생 시 자동 재로드 후 재실행
     *
     * @param <T>            반환 타입
     * @param shaGetter      SHA 획득 함수
     * @param scriptSource   원본 스크립트 (재로드용)
     * @param shaUpdater     SHA 업데이트 함수
     * @param scriptExecutor 스크립트 실행 함수
     * @param scriptName     스크립트 이름 (로깅용)
     * @return 스크립트 실행 결과
     */
    public <T> T executeWithNoscriptHandling(
            Supplier<String> shaGetter,
            String scriptSource,
            Consumer<String> shaUpdater,
            Function<String, T> scriptExecutor,
            String scriptName) {

        return executor.executeWithFallback(
                () -> scriptExecutor.apply(shaGetter.get()),
                e -> handleNoscriptAndRetry(e, scriptSource, shaUpdater, scriptExecutor, scriptName),
                TaskContext.of("BufferLuaScript", "Execute", scriptName)
        );
    }

    private <T> T handleNoscriptAndRetry(
            Throwable e,
            String scriptSource,
            Consumer<String> shaUpdater,
            Function<String, T> scriptExecutor,
            String scriptName) {

        if (!isNoscriptError(e)) {
            throw new RedisScriptExecutionException(scriptName, e);
        }

        log.warn("[NOSCRIPT] Buffer 스크립트 재로드 필요: {}", scriptName);
        String newSha = reloadScript(scriptSource, scriptName);
        shaUpdater.accept(newSha);

        return scriptExecutor.apply(newSha);
    }

    private boolean isNoscriptError(Throwable e) {
        String message = e.getMessage();
        if (message != null && message.contains(NOSCRIPT_ERROR_PREFIX)) {
            return true;
        }
        Throwable cause = e.getCause();
        return cause != null && isNoscriptError(cause);
    }

    private String reloadScript(String scriptSource, String scriptName) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        String sha = script.scriptLoad(scriptSource);
        log.info("[BufferLuaScriptProvider] 스크립트 재로드 완료 - {}: {}", scriptName, sha);
        return sha;
    }
}
