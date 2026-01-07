package maple.expectation.provider;

import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class EquipmentDataProviderConcurrencyTest extends IntegrationTestSupport {

    @Autowired private EquipmentFetchProvider fetchProvider;
    @Autowired private CacheManager cacheManager;

    // 💡 equipmentRepository를 Mock으로 오버라이드하여 stubbing 가능하게 함
    @MockitoBean
    private maple.expectation.repository.v2.CharacterEquipmentRepository equipmentRepository;

    @BeforeEach
    void setUp() {
        // 이전 테스트의 캐시 및 Mock 상태 완전 초기화 (null 방어)
        cacheManager.getCacheNames().forEach(cacheName -> {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) cache.clear();
        });
        reset(equipmentRepository, nexonApiClient);
    }

    @Test
    @DisplayName("AOP 기반 캐시: 동시에 10명이 같은 유저 조회 시, DB 저장은 1회만 발생해야 한다")
    void aopConcurrencyTest() throws InterruptedException {
        int threadCount = 10;
        // ✅ 테스트 간 캐시 키 충돌 방지를 위해 unique ID 사용
        String targetOcid = "ocid_concurrency_" + System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);  // 동시 출발 강제
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicReference<CharacterEquipment> mockDb = new AtomicReference<>(null);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>(null);  // 예외 가시화

        when(equipmentRepository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(mockDb.get()));
        when(equipmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
            CharacterEquipment entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "updatedAt", LocalDateTime.now());
            mockDb.set(entity);
            return entity;
        });

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
                        // 최초 예외만 기록 (동시성 안전)
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
            // 스레드 누수 방지: assertion 실패 시에도 반드시 종료
            executor.shutdownNow();
        }

        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
                .as("Executor가 5초 내에 종료되어야 함")
                .isTrue();

        // 작업 중 예외가 없었음을 검증
        assertThat(firstFailure.get())
                .as("동시성 테스트 중 예외가 발생하지 않아야 함")
                .isNull();

        // 동시성 결함이 있으면 외부 API가 여러 번 호출되므로 함께 검증
        verify(nexonApiClient, times(1)).getItemDataByOcid(targetOcid);
        verify(equipmentRepository, times(1)).saveAndFlush(any());
    }
}