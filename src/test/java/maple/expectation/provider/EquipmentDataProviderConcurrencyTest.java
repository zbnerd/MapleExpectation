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
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EquipmentDataProviderConcurrencyTest extends IntegrationTestSupport {

    @Autowired private EquipmentFetchProvider fetchProvider;
    @Autowired private CacheManager cacheManager;

    // 💡 equipmentRepository를 Mock으로 오버라이드하여 stubbing 가능하게 함
    @MockitoBean
    private maple.expectation.repository.v2.CharacterEquipmentRepository equipmentRepository;

    @Test
    @DisplayName("AOP 기반 캐시: 동시에 10명이 같은 유저 조회 시, DB 저장은 1회만 발생해야 한다")
    void aopConcurrencyTest() throws InterruptedException {
        int threadCount = 10;
        String targetOcid = "ocid_test_123";
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicReference<CharacterEquipment> mockDb = new AtomicReference<>(null);

        cacheManager.getCache("equipment").clear();

        when(equipmentRepository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(mockDb.get()));
        when(equipmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
            CharacterEquipment entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "updatedAt", LocalDateTime.now());
            mockDb.set(entity);
            return entity;
        });

        when(nexonApiClient.getItemDataByOcid(targetOcid))
                .thenReturn(CompletableFuture.completedFuture(new EquipmentResponse()));

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try { fetchProvider.fetchWithCache(targetOcid); }
                finally { latch.countDown(); }
            });
        }

        latch.await();
        executor.shutdown();

        verify(equipmentRepository, times(1)).saveAndFlush(any());
    }
}