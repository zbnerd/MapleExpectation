package maple.expectation.infrastructure.redis.script;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.RedisScriptExecutionException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

/**
 * Lua Script SHA 캐싱 및 NOSCRIPT 에러 핸들링 제공자
 *
 * <p>Redis Lua Script 실행 시 성능 최적화를 위해 SHA 해시를 캐싱하고, Redis 서버 재시작 등으로 인한 NOSCRIPT 에러 발생 시 자동 재로드합니다.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>AtomicReference를 사용하여 volatile 레이스 컨디션을 방지합니다.
 *
 * <h2>NOSCRIPT Error Handling</h2>
 *
 * <pre>
 * 1. evalSha(sha) 호출
 * 2. NOSCRIPT 에러 발생 (서버에 스크립트 없음)
 * 3. scriptLoad()로 스크립트 재로드
 * 4. evalSha(newSha) 재시도
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LuaScriptProvider {

  private final RedissonClient redissonClient;
  private final LogicExecutor executor;

  private final AtomicReference<String> transferShaRef = new AtomicReference<>();
  private final AtomicReference<String> deleteAndDecrementShaRef = new AtomicReference<>();
  private final AtomicReference<String> compensationShaRef = new AtomicReference<>();

  private static final String NOSCRIPT_ERROR_PREFIX = "NOSCRIPT";

  /**
   * 애플리케이션 시작 시 모든 Lua Script를 Redis에 로드하고 SHA 캐싱 (웜업)
   *
   * <p>Graceful Degradation: Redis 연결 실패해도 애플리케이션 시작에 영향 없음. 첫 호출 시 Lazy Loading으로 스크립트 로드 재시도.
   */
  @PostConstruct
  public void loadScripts() {
    boolean loaded =
        executor.executeOrDefault(
            () -> {
              RScript script = redissonClient.getScript(StringCodec.INSTANCE);

              String transferSha = script.scriptLoad(LuaScripts.ATOMIC_TRANSFER);
              transferShaRef.set(transferSha);

              String deleteAndDecrementSha =
                  script.scriptLoad(LuaScripts.ATOMIC_DELETE_AND_DECREMENT);
              deleteAndDecrementShaRef.set(deleteAndDecrementSha);

              String compensationSha = script.scriptLoad(LuaScripts.ATOMIC_COMPENSATION);
              compensationShaRef.set(compensationSha);

              log.info(
                  "✅ [LuaScriptProvider] SHA 캐싱 완료 - Transfer: {}, DeleteDecr: {}, Compensation: {}",
                  transferSha,
                  deleteAndDecrementSha,
                  compensationSha);
              return true;
            },
            false,
            TaskContext.of("LuaScript", "LoadAll"));

    if (!loaded) {
      log.warn("⚠️ [LuaScriptProvider] 시작 시 스크립트 로드 실패 - 첫 호출 시 Lazy Loading 시도");
    }
  }

  /** AtomicTransfer 스크립트 SHA 반환 */
  public String getTransferSha() {
    return transferShaRef.updateAndGet(
        current ->
            current != null ? current : reloadScript(LuaScripts.ATOMIC_TRANSFER, "Transfer"));
  }

  /** AtomicDeleteAndDecrement 스크립트 SHA 반환 */
  public String getDeleteAndDecrementSha() {
    return deleteAndDecrementShaRef.updateAndGet(
        current ->
            current != null
                ? current
                : reloadScript(LuaScripts.ATOMIC_DELETE_AND_DECREMENT, "DeleteAndDecrement"));
  }

  /** AtomicCompensation 스크립트 SHA 반환 */
  public String getCompensationSha() {
    return compensationShaRef.updateAndGet(
        current ->
            current != null
                ? current
                : reloadScript(LuaScripts.ATOMIC_COMPENSATION, "Compensation"));
  }

  /**
   * NOSCRIPT 에러 발생 시 자동 재로드 후 재실행
   *
   * @param <T> 반환 타입
   * @param shaGetter SHA 획득 함수
   * @param scriptSource 원본 스크립트 (재로드용)
   * @param shaUpdater SHA 업데이트 함수 (AtomicReference 업데이트)
   * @param scriptExecutor 스크립트 실행 함수
   * @param scriptName 스크립트 이름 (로깅용)
   * @return 스크립트 실행 결과
   */
  public <T> T executeWithNoscriptHandling(
      java.util.function.Supplier<String> shaGetter,
      String scriptSource,
      java.util.function.Consumer<String> shaUpdater,
      Function<String, T> scriptExecutor,
      String scriptName) {

    return executor.executeWithFallback(
        () -> scriptExecutor.apply(shaGetter.get()),
        e -> handleNoscriptAndRetry(e, scriptSource, shaUpdater, scriptExecutor, scriptName),
        TaskContext.of("LuaScript", "Execute", scriptName));
  }

  private <T> T handleNoscriptAndRetry(
      Throwable e,
      String scriptSource,
      java.util.function.Consumer<String> shaUpdater,
      Function<String, T> scriptExecutor,
      String scriptName) {

    if (!isNoscriptError(e)) {
      throw new RedisScriptExecutionException(scriptName, e);
    }

    log.warn("⚠️ [NOSCRIPT] 스크립트 재로드 필요: {}", scriptName);
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
    log.info("🔄 [LuaScriptProvider] 스크립트 재로드 완료 - {}: {}", scriptName, sha);
    return sha;
  }

  /** Transfer SHA 업데이트 (NOSCRIPT 재로드 후 사용) */
  public void updateTransferSha(String sha) {
    transferShaRef.set(sha);
  }

  /** DeleteAndDecrement SHA 업데이트 (NOSCRIPT 재로드 후 사용) */
  public void updateDeleteAndDecrementSha(String sha) {
    deleteAndDecrementShaRef.set(sha);
  }

  /** Compensation SHA 업데이트 (NOSCRIPT 재로드 후 사용) */
  public void updateCompensationSha(String sha) {
    compensationShaRef.set(sha);
  }
}
