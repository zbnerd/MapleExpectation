package maple.expectation.infrastructure.concurrency;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.SystemException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

/**
 * Distributed Single-flight Executor (Issue #283 P0-4 Fix)
 *
 * <h4>핵심 기능</h4>
 *
 * <ul>
 *   <li>동일 키에 대한 동시 요청 N개 중 실제 계산은 1회만 수행 (Leader)
 *   <li>나머지 요청은 Leader의 결과를 공유 (Follower)
 *   <li>Redis 기반 분산 상태로 인스턴스 간 Single-Flight 보장
 *   <li>Follower 타임아웃 시 fallback 함수 실행
 * </ul>
 *
 * <h4>Redis 키 구조</h4>
 *
 * <ul>
 *   <li>In-flight 키: {single-flight}:{keyHash} (TTL: leaderLockSeconds)
 *   <li>결과 캐시 키: {single-flight}:result:{keyHash} (TTL: resultTtl)
 * </ul>
 *
 * <h4>분산 상태 관리</h4>
 *
 * <ol>
 *   <li>Leader: Redis SET NX로 in-flight 키 확보 (선점)
 *   <li>계산 완료: 결과를 Redis Cache에 저장, in-flight 키 삭제
 *   <li>Follower: Redis에서 in-flight 키 확인 → 있으면 결과 대기 (polling)
 * </ol>
 *
 * @param <T> 계산 결과 타입
 * @see SingleFlightExecutor 인-메모리 구현 (테스트용)
 */
@Slf4j
public class DistributedSingleFlightExecutor<T> {

  /** Redis 키 접두사 (Hash Tag for Cluster) */
  private static final String KEY_PREFIX = "{single-flight}:";

  private static final String RESULT_PREFIX = "{single-flight}:result:";

  /** Follower 대기 타임아웃 (초) */
  private final int followerTimeoutSeconds;

  /** 비동기 작업 실행용 Executor */
  private final Executor executor;

  /** Follower 타임아웃 시 fallback 함수 (key → result) */
  private final Function<String, T> timeoutFallback;

  /** Redisson Client (분산 상태) */
  private final RedissonClient redissonClient;

  /** LogicExecutor */
  private final LogicExecutor logicExecutor;

  /** Leader 잠금 유지 시간 (초) - 계산 시간 고려하여 충분히 설정 */
  private final int leaderLockSeconds;

  /** 결과 캐시 TTL (초) - Follower가 결과를 조회할 수 있는 시간 */
  private final int resultTtlSeconds;

  /** 로컬 결과 캐시 (Redis 조회 최적화) */
  private final Cache<String, CompletableFuture<T>> localResultCache;

  /**
   * DistributedSingleFlightExecutor 생성자
   *
   * @param followerTimeoutSeconds Follower 대기 타임아웃 (초)
   * @param executor 비동기 작업 실행용 Executor
   * @param timeoutFallback Follower 타임아웃 시 fallback 함수 (null 시 예외 전파)
   * @param redissonClient Redisson 클라이언트
   * @param logicExecutor LogicExecutor
   * @param leaderLockSeconds Leader 잠금 유지 시간 (기본 30초)
   * @param resultTtlSeconds 결과 캐시 TTL (기본 60초)
   */
  public DistributedSingleFlightExecutor(
      int followerTimeoutSeconds,
      Executor executor,
      Function<String, T> timeoutFallback,
      RedissonClient redissonClient,
      LogicExecutor logicExecutor,
      int leaderLockSeconds,
      int resultTtlSeconds) {
    this.followerTimeoutSeconds = followerTimeoutSeconds;
    this.executor = executor;
    this.timeoutFallback = timeoutFallback;
    this.redissonClient = redissonClient;
    this.logicExecutor = logicExecutor;
    this.leaderLockSeconds = leaderLockSeconds;
    this.resultTtlSeconds = resultTtlSeconds;

    // 로컬 캐시 초기화 (TTL 기반 만료)
    this.localResultCache =
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(resultTtlSeconds))
            .maximumSize(1000)
            .build();
  }

  /** 기본 생성자 (Leader 잠금 30초, 결과 TTL 60초) */
  public DistributedSingleFlightExecutor(
      int followerTimeoutSeconds,
      Executor executor,
      Function<String, T> timeoutFallback,
      RedissonClient redissonClient,
      LogicExecutor logicExecutor) {
    this(followerTimeoutSeconds, executor, timeoutFallback, redissonClient, logicExecutor, 30, 60);
  }

  /**
   * Single-flight 비동기 실행
   *
   * <h4>흐름</h4>
   *
   * <ol>
   *   <li>키에 대한 in-flight 엔트리 Redis에서 확인 (SET NX)
   *   <li>없으면 Leader로 등록 후 계산 시작
   *   <li>있으면 Follower로 Leader 결과 대기 (Redis 결과 캐시 폴링)
   * </ol>
   *
   * @param key 계산 식별 키 (캐시 키 등)
   * @param asyncSupplier 비동기 계산 로직
   * @return 계산 결과 Future
   */
  public CompletableFuture<T> executeAsync(
      String key, Supplier<CompletableFuture<T>> asyncSupplier) {

    String hashKey = hashKey(key);
    String inFlightKey = KEY_PREFIX + hashKey;
    String resultKey = RESULT_PREFIX + hashKey;

    // 로컬 캐시 확인 (Redis 조회 최적화)
    CompletableFuture<T> cached = localResultCache.getIfPresent(resultKey);
    if (cached != null && cached.isDone()) {
      log.debug("[DistributedSingleFlight] Local cache hit for key: {}", maskKey(key));
      return copyFuture(cached);
    }

    // Leader 선점 시도
    boolean acquired = tryAcquireLeadership(inFlightKey);

    if (acquired) {
      // Leader: 계산 수행
      return executeAsLeader(key, hashKey, inFlightKey, resultKey, asyncSupplier);
    }

    // Follower: 결과 대기
    return executeAsFollower(key, hashKey, resultKey);
  }

  /**
   * Leader 선점 시도 (Redis SET NX)
   *
   * @return true: 선점 성공 (Leader), false: 선점 실패 (Follower)
   */
  private boolean tryAcquireLeadership(String inFlightKey) {
    return logicExecutor.executeOrDefault(
        () -> {
          RBucket<Boolean> bucket = redissonClient.getBucket(inFlightKey);
          // SET NX (존재하지 않을 때만 설정)
          boolean acquired = bucket.trySet(true, leaderLockSeconds, TimeUnit.SECONDS);
          if (acquired) {
            log.debug("[DistributedSingleFlight] Leadership acquired: {}", maskKey(inFlightKey));
          }
          return acquired;
        },
        false,
        TaskContext.of("DistributedSingleFlight", "TryAcquireLeadership", inFlightKey));
  }

  /** Leader 비동기 실행 (계산 + Redis 결과 저장 + cleanup) */
  private CompletableFuture<T> executeAsLeader(
      String key,
      String hashKey,
      String inFlightKey,
      String resultKey,
      Supplier<CompletableFuture<T>> asyncSupplier) {

    CompletableFuture<T> promise = new CompletableFuture<>();

    // 로컬 캐시에 등록 (Follower가 로컬에서 확인 가능)
    localResultCache.put(resultKey, promise);

    // LogicExecutor 사용 (CLAUDE.md Section 12 준수)
    logicExecutor.executeVoid(
        () -> {
          CompletableFuture.supplyAsync(asyncSupplier::get, executor)
              .thenCompose(future -> future) // flatten
              .whenComplete(
                  (result, error) -> {
                    // 결과를 Redis에 저장
                    saveResultToRedis(resultKey, result, error);

                    // in-flight 키 제거
                    cleanupLeaderEntry(inFlightKey);

                    // Promise 완료
                    if (error != null) {
                      Throwable cause = unwrapCause(error);
                      log.error(
                          "[DistributedSingleFlight] Leader failed for key: {}",
                          maskKey(key),
                          cause);
                      promise.completeExceptionally(cause);
                    } else {
                      promise.complete(result);
                    }
                  });
        },
        TaskContext.of("DistributedSingleFlight", "ExecuteAsLeader", maskKey(key)));

    return promise;
  }

  /** 결과를 Redis에 저장 (Leader) */
  private void saveResultToRedis(String resultKey, T result, Throwable error) {
    logicExecutor.executeVoid(
        () -> {
          try {
            if (error == null) {
              // 성공 결과 저장
              redissonClient
                  .<T>getBucket(resultKey)
                  .set(result, resultTtlSeconds, TimeUnit.SECONDS);
            } else {
              // 실패 결과도 저장 (Follower가 동일하게 실패하도록)
              redissonClient
                  .<String>getBucket(resultKey + ":error")
                  .set(error.getClass().getName(), resultTtlSeconds, TimeUnit.SECONDS);
            }
            log.debug("[DistributedSingleFlight] Result saved to Redis: {}", maskKey(resultKey));
          } catch (Exception e) {
            log.warn(
                "[DistributedSingleFlight] Failed to save result to Redis: {}",
                maskKey(resultKey),
                e);
          }
        },
        TaskContext.of("DistributedSingleFlight", "SaveResult", maskKey(resultKey)));
  }

  /** Leader 종료 시 정리 (in-flight 키 제거) */
  private void cleanupLeaderEntry(String inFlightKey) {
    logicExecutor.executeVoid(
        () -> {
          try {
            redissonClient.getBucket(inFlightKey).delete();
            log.debug("[DistributedSingleFlight] In-flight key removed: {}", maskKey(inFlightKey));
          } catch (Exception e) {
            log.warn(
                "[DistributedSingleFlight] Failed to remove in-flight key: {}",
                maskKey(inFlightKey),
                e);
          }
        },
        TaskContext.of("DistributedSingleFlight", "CleanupLeader", maskKey(inFlightKey)));
  }

  /**
   * Follower 비동기 대기 (Redis 결과 폴링)
   *
   * <p>Redis 결과 캐시를 폴링하며 Leader 결과를 기다립니다.
   */
  private CompletableFuture<T> executeAsFollower(String key, String hashKey, String resultKey) {

    CompletableFuture<T> result = new CompletableFuture<>();
    String maskedKey = maskKey(key);

    // 비동기 폴링 시작
    logicExecutor.executeVoid(
        () -> pollForResult(resultKey, result, maskedKey, System.currentTimeMillis()),
        TaskContext.of("DistributedSingleFlight", "ExecuteAsFollower", maskedKey));

    return result
        .orTimeout(followerTimeoutSeconds, TimeUnit.SECONDS)
        .exceptionally(
            e -> {
              Throwable cause = unwrapCause(e);

              if (cause instanceof TimeoutException) {
                log.warn("[DistributedSingleFlight] Follower timeout for key: {}", maskKey(key));

                if (timeoutFallback != null) {
                  return logicExecutor.executeOrDefault(
                      () -> timeoutFallback.apply(key),
                      null,
                      TaskContext.of("DistributedSingleFlight", "Fallback", maskKey(key)));
                }
              }

              // Re-throw the exception
              if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
              }
              throw new SystemException(
                  CommonErrorCode.INTERNAL_SERVER_ERROR, "SingleFlight execution failed", cause);
            });
  }

  /** Redis 결과 폴링 (Follower) */
  private void pollForResult(
      String resultKey, CompletableFuture<T> result, String maskedKey, long deadline) {

    long timeoutMs = followerTimeoutSeconds * 1000L;
    long remaining = deadline + timeoutMs - System.currentTimeMillis();

    if (remaining <= 0) {
      log.warn("[DistributedSingleFlight] Poll timeout for key: {}", maskedKey);
      return;
    }

    // Redis에서 결과 확인
    logicExecutor.executeVoid(
        () -> {
          // 먼저 에러 확인
          RBucket<String> errorBucket = redissonClient.getBucket(resultKey + ":error");
          String errorClass = errorBucket.get();
          if (errorClass != null) {
            result.completeExceptionally(
                new SystemException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR, "Leader failed: %s", errorClass));
            return;
          }

          // 성공 결과 확인
          RBucket<T> resultBucket = redissonClient.<T>getBucket(resultKey);
          T value = resultBucket.get();

          if (value != null) {
            // 결과 발견
            result.complete(value);
            log.debug("[DistributedSingleFlight] Result retrieved from Redis: {}", maskedKey);
            return;
          }

          // 결과 아직 준비 안됨 -> 재시도
          if (remaining > 100) {
            // 100ms 후 재시도
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              result.completeExceptionally(e);
              return;
            }
            pollForResult(resultKey, result, maskedKey, deadline);
          } else {
            log.debug("[DistributedSingleFlight] Polling exhausted for key: {}", maskedKey);
          }
        },
        TaskContext.of("DistributedSingleFlight", "PollResult", maskedKey));
  }

  /**
   * 키 해시 (Redis 키 길이 제한 준수)
   *
   * @param key 원본 키
   * @return SHA-256 해시 (hex)
   */
  private String hashKey(String key) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256는 항상 존재
      throw new SystemException(CommonErrorCode.INTERNAL_SERVER_ERROR, "SHA-256 not available", e);
    }
  }

  /** CompletionException unwrap */
  private Throwable unwrapCause(Throwable e) {
    if (e instanceof java.util.concurrent.CompletionException
        || e instanceof java.util.concurrent.ExecutionException) {
      return e.getCause() != null ? e.getCause() : e;
    }
    return e;
  }

  /** 키 마스킹 (로깅용) */
  private String maskKey(String key) {
    if (key == null) return "null";
    if (key.length() <= 8) return "***";
    return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
  }

  /** Future 복사 (완료된 Future의 안전한 참조 반환) */
  private CompletableFuture<T> copyFuture(CompletableFuture<T> future) {
    CompletableFuture<T> copy = new CompletableFuture<>();
    future.whenComplete(
        (result, error) -> {
          if (error != null) {
            copy.completeExceptionally(error);
          } else {
            copy.complete(result);
          }
        });
    return copy;
  }

  /** 현재 로컬 캐시 크기 (모니터링용) */
  public long getLocalCacheSize() {
    return localResultCache.estimatedSize();
  }

  /** 로컬 캐시 비우기 */
  public void clearLocalCache() {
    localResultCache.invalidateAll();
  }
}
