package maple.expectation.service.v2;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.jvm.functions.Function1;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.domain.repository.RedisBufferRepository;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.function.ThrowingRunnable;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import maple.expectation.infrastructure.queue.like.LikeSyncExecutor;
import maple.expectation.service.v2.cache.LikeBufferStrategy;
import maple.expectation.service.v2.like.dto.FetchResult;
import maple.expectation.service.v2.like.metrics.LikeSyncMetricsRecorder;
import maple.expectation.service.v2.like.strategy.AtomicFetchStrategy;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ✅ LogicExecutor 계약 기반 테스트 (본연의 비즈니스 로직 검증)
 *
 * <p>리팩토링 후: AtomicFetchStrategy 기반 원자적 동기화 검증
 */
@ExtendWith(MockitoExtension.class)
class LikeSyncServiceTest {

  private LikeSyncService likeSyncService;

  @Mock private LikeBufferStrategy likeBufferStrategy;
  @Mock private LikeSyncExecutor syncExecutor;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private RedisBufferRepository redisBufferRepository;
  @Mock private ShutdownDataPersistenceService shutdownDataPersistenceService;
  @Mock private HashOperations<String, Object, Object> hashOperations;
  @Mock private LogicExecutor executor;
  @Mock private AtomicFetchStrategy atomicFetchStrategy;
  @Mock private MeterRegistry meterRegistry;
  @Mock private Counter mockCounter;
  @Mock private Timer mockTimer;
  @Mock private DistributionSummary mockSummary;
  @Mock private LikeSyncMetricsRecorder metricsRecorder;
  @Mock private ApplicationEventPublisher eventPublisher;

  private static final String SOURCE_KEY = "{buffer:likes}";

  @BeforeEach
  void setUp() {
    // ✅ LogicExecutor 계약 stub (4가지 패턴)

    // [패턴 1] executeWithFinally: task 실행 후 finalizer 반드시 실행
    lenient()
        .doAnswer(
            inv -> {
              ThrowingSupplier<?> task = inv.getArgument(0);
              Runnable finalizer = inv.getArgument(1);
              AtomicBoolean finalizerRan = new AtomicBoolean(false);

              try {
                return task.get();
              } finally {
                if (finalizerRan.compareAndSet(false, true)) {
                  finalizer.run();
                }
              }
            })
        .when(executor)
        .executeWithFinally(any(), any(), any());

    // [패턴 2] executeVoid: task 실행 (반환값 무시)
    lenient()
        .doAnswer(
            inv -> {
              ThrowingRunnable task = inv.getArgument(0);
              task.run();
              return null;
            })
        .when(executor)
        .executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));

    // [패턴 3] executeOrDefault: task 실행, 예외 시 기본값 반환 (Error는 즉시 rethrow)
    lenient()
        .doAnswer(
            inv -> {
              ThrowingSupplier<?> task = inv.getArgument(0);
              Object defaultValue = inv.getArgument(1);
              try {
                return task.get();
              } catch (Error err) {
                throw err; // Error는 복구 금지
              } catch (Throwable e) {
                return defaultValue;
              }
            })
        .when(executor)
        .executeOrDefault(any(), any(), any());

    // [패턴 4a] executeOrCatch: Kotlin Function1 version (Java lambdas use this)
    lenient()
        .when(
            executor.executeOrCatch(
                any(ThrowingSupplier.class), any(Function1.class), any(TaskContext.class)))
        .thenAnswer(
            inv -> {
              ThrowingSupplier<?> task = inv.getArgument(0);
              Function1<Throwable, ?> recovery = inv.getArgument(1);
              try {
                return task.get();
              } catch (Error err) {
                throw err; // Error는 복구 금지
              } catch (Throwable t) {
                return recovery.invoke(t);
              }
            });

    // [패턴 4b] executeOrCatch: ExceptionTranslator version
    lenient()
        .when(
            executor.executeOrCatch(
                any(ThrowingSupplier.class),
                any(ExceptionTranslator.class),
                any(TaskContext.class)))
        .thenAnswer(
            inv -> {
              ThrowingSupplier<?> task = inv.getArgument(0);
              ExceptionTranslator handler = inv.getArgument(1);
              try {
                return task.get();
              } catch (Error err) {
                throw err; // Error는 복구 금지
              } catch (Throwable t) {
                return handler.translate(t, inv.getArgument(2));
              }
            });

    // MeterRegistry mock 설정
    lenient().when(meterRegistry.counter(anyString())).thenReturn(mockCounter);
    lenient().when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(mockCounter);
    lenient().when(meterRegistry.timer(anyString(), any(String[].class))).thenReturn(mockTimer);
    lenient().when(meterRegistry.summary(anyString())).thenReturn(mockSummary);

    // V5 Stateless: LikeBufferStrategy mock (In-Memory 모드로 테스트)
    lenient()
        .when(likeBufferStrategy.getType())
        .thenReturn(LikeBufferStrategy.StrategyType.IN_MEMORY);

    likeSyncService =
        new LikeSyncService(
            likeBufferStrategy,
            syncExecutor,
            redisTemplate,
            redisBufferRepository,
            shutdownDataPersistenceService,
            executor,
            atomicFetchStrategy,
            meterRegistry,
            metricsRecorder,
            eventPublisher);

    // Issue #48: chunkSize 설정 (테스트용 - @Value 필드 주입 대체)
    ReflectionTestUtils.setField(likeSyncService, "chunkSize", 500);

    lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
  }

  @Test
  @DisplayName("성공 시나리오: AtomicFetch 후 데이터를 DB에 반영하고 전역 카운터를 차감한다")
  void syncRedisToDatabase_SuccessScenario() {
    // [Given]
    String userIgn = "Gamer";
    Map<String, Long> fetchedData = Map.of(userIgn, 5L);
    FetchResult fetchResult = new FetchResult("{buffer:likes}:sync:test-uuid", fetchedData);

    // AtomicFetchStrategy mock: fetchAndMove 호출 시 FetchResult 반환
    given(atomicFetchStrategy.fetchAndMove(eq(SOURCE_KEY), anyString())).willReturn(fetchResult);

    // [When]
    likeSyncService.syncRedisToDatabase();

    // [Then]
    // ✅ [핵심] AtomicFetchStrategy.fetchAndMove() 호출 검증
    verify(atomicFetchStrategy, times(1)).fetchAndMove(eq(SOURCE_KEY), anyString());

    // ✅ [핵심] DB 동기화 실행 (Issue #48: Batch Update)
    verify(syncExecutor, times(1)).executeIncrementBatch(anyList());

    // ✅ [핵심] 성공 시 전역 카운터 차감
    verify(redisBufferRepository, times(1)).decrementGlobalCount(5L);

    // ✅ [핵심] 성공 시 임시 키 삭제 (commit)
    verify(atomicFetchStrategy, times(1)).deleteTempKey(anyString());
  }

  @Test
  @DisplayName("실패 시나리오: DB 반영 실패 시 전역 카운터를 차감하지 않고 원본 키로 복구한다")
  void syncRedisToDatabase_FailureScenario() {
    // [Given]
    String userIgn = "Gamer";
    Map<String, Long> fetchedData = Map.of(userIgn, 10L);
    FetchResult fetchResult = new FetchResult("{buffer:likes}:sync:test-uuid", fetchedData);

    // AtomicFetchStrategy mock
    given(atomicFetchStrategy.fetchAndMove(eq(SOURCE_KEY), anyString())).willReturn(fetchResult);

    // DB 동기화 실패 (Issue #48: Batch Update에서 예외)
    willThrow(new RuntimeException("DB Fail")).given(syncExecutor).executeIncrementBatch(anyList());

    // [When]
    likeSyncService.syncRedisToDatabase();

    // [Then]
    // ✅ [핵심] 실패 시 전역 카운터 차감하지 않음
    verify(redisBufferRepository, never()).decrementGlobalCount(anyLong());

    // ✅ [핵심] 실패 항목은 원본 버퍼로 즉시 복구 (restoreSingleEntry)
    verify(hashOperations, times(1)).increment(eq(SOURCE_KEY), eq(userIgn), eq(10L));

    // ✅ [핵심] 실패해도 commit은 호출됨 (finally에서 compensation 체크 후 결정)
    // compensation.isPending() == false (save() 후 실패해도 restoreSingleEntry로 개별 복구됨)
    verify(atomicFetchStrategy, times(1)).deleteTempKey(anyString());
  }

  @Test
  @DisplayName("빈 데이터 시나리오: 동기화할 데이터가 없으면 아무 작업도 하지 않는다")
  void syncRedisToDatabase_EmptyData() {
    // [Given]
    given(atomicFetchStrategy.fetchAndMove(eq(SOURCE_KEY), anyString()))
        .willReturn(FetchResult.empty());

    // [When]
    likeSyncService.syncRedisToDatabase();

    // [Then]
    // ✅ [핵심] 빈 데이터 시 DB 동기화 스킵 (Issue #48: Batch Update)
    verify(syncExecutor, never()).executeIncrementBatch(anyList());

    // ✅ [핵심] 빈 데이터 시 전역 카운터 차감 스킵
    verify(redisBufferRepository, never()).decrementGlobalCount(anyLong());

    // ✅ [핵심] 빈 데이터 시 임시 키 삭제 스킵
    verify(atomicFetchStrategy, never()).deleteTempKey(anyString());
  }
}
