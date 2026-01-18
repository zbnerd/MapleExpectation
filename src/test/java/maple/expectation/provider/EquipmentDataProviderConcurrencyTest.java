package maple.expectation.provider;

import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.service.v2.worker.EquipmentDbWorker;
import maple.expectation.support.IntegrationTestSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AOP 기반 캐시 동시성 테스트
 *
 * <h3>검증 목표</h3>
 * <p>동시에 10명이 같은 유저를 조회할 때, 외부 API 호출과 DB 저장이 각각 1회만 발생하는지 검증</p>
 *
 * <h3>Mock 전략 (Issue #171)</h3>
 * <p>EquipmentDbWorker를 Mock하여 비동기 저장 로직을 동기적으로 검증.
 * CharacterEquipmentRepository를 직접 Mock하면 @Async 스레드와 Context 불일치 문제 발생.</p>
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Execution(ExecutionMode.SAME_THREAD)  // CLAUDE.md Section 24: 병렬 실행 시 캐시 상태 충돌 방지
class EquipmentDataProviderConcurrencyTest extends IntegrationTestSupport {

    @Autowired private EquipmentFetchProvider fetchProvider;
    @Autowired private CacheManager cacheManager;

    // 💡 EquipmentDbWorker를 Mock으로 대체하여 비동기 저장 로직 검증
    @MockitoBean
    private EquipmentDbWorker dbWorker;

    private AtomicInteger persistCallCount;

    @BeforeEach
    void setUp() {
        // 캐시 초기화
        cacheManager.getCacheNames().forEach(cacheName -> {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) cache.clear();
        });
        reset(dbWorker, nexonApiClient);

        persistCallCount = new AtomicInteger(0);

        // dbWorker.persist() Mock - 호출 횟수 추적
        when(dbWorker.persist(anyString(), any(EquipmentResponse.class)))
                .thenAnswer(inv -> {
                    persistCallCount.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });
    }

    @Test
    @DisplayName("AOP 기반 캐시: 동시에 10명이 같은 유저 조회 시, API 호출과 DB 저장은 각각 1회만 발생해야 한다")
    void aopConcurrencyTest() throws InterruptedException {
        int threadCount = 10;
        // 테스트 간 캐시 키 충돌 방지를 위해 unique ID 사용
        String targetOcid = "ocid_concurrency_" + System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);  // 동시 출발 강제
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>(null);

        when(nexonApiClient.getItemDataByOcid(targetOcid))
                .thenReturn(CompletableFuture.completedFuture(new EquipmentResponse()));

        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        start.await();  // 모든 스레드가 동시에 출발
                        fetchProvider.fetchWithCache(targetOcid);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable t) {
                        firstFailure.compareAndSet(null, t);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            start.countDown();  // 출발 신호
            assertThat(latch.await(15, TimeUnit.SECONDS))
                    .as("모든 스레드가 15초 내에 완료되어야 함")
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
                .as("Executor가 5초 내에 종료되어야 함")
                .isTrue();

        // 작업 중 예외가 없었음을 검증
        assertThat(firstFailure.get())
                .as("동시성 테스트 중 예외가 발생하지 않아야 함")
                .isNull();

        // 비동기 작업 완료 대기 (최대 5초)
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    // 동시성 결함이 있으면 외부 API가 여러 번 호출되므로 함께 검증
                    verify(nexonApiClient, times(1)).getItemDataByOcid(targetOcid);
                    // DB 저장도 1회만 발생해야 함
                    assertThat(persistCallCount.get())
                            .as("DB 저장은 1회만 발생해야 함")
                            .isEqualTo(1);
                });
    }
}