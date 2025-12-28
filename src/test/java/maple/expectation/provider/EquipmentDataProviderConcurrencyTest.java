package maple.expectation.provider;

import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.impl.RealNexonApiClient;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils; // 💡 중요: 이거 임포트 확인!
import org.springframework.test.util.ReflectionTestUtils;

import org.springframework.cache.CacheManager;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
class EquipmentDataProviderConcurrencyTest {

    @Autowired
    private EquipmentFetchProvider fetchProvider; // 🚀 실제 테스트 대상 (AOP가 붙은 관문)

    @MockitoBean
    @Qualifier("realNexonApiClient")
    private RealNexonApiClient targetClient; // 🚀 클라이언트는 Mock으로 처리 (API 호출 방지)

    @MockitoBean
    private CharacterEquipmentRepository equipmentRepository;

    @Autowired
    private CacheManager cacheManager;

    @Test
    @DisplayName("AOP 기반 캐시: 동시에 10명이 같은 유저 조회 시, 실제 DB 저장(Sync)은 1회만 발생해야 한다")
    void aopConcurrencyTest() throws InterruptedException {
        // Given
        int threadCount = 10;
        String targetOcid = "ocid_test_123";
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicReference<CharacterEquipment> mockDb = new AtomicReference<>(null);

        // 이전 테스트 캐시 초기화
        cacheManager.getCache("equipment").clear();

        // 1. DB 레포지토리 모킹
        when(equipmentRepository.findById(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(mockDb.get())
        );

        when(equipmentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CharacterEquipment entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "updatedAt", LocalDateTime.now());
            mockDb.set(entity);
            return entity;
        });

        // 2. 🚀 API 클라이언트가 가짜 데이터를 반환하도록 설정
        when(targetClient.getItemDataByOcid(targetOcid))
                .thenReturn(CompletableFuture.completedFuture(new EquipmentResponse()));

        // When
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    // 🚀 [핵심 수정] 클라이언트가 아니라 'FetchProvider'를 호출해야
                    // 그 위에 붙은 @NexonDataCache AOP가 작동합니다!
                    fetchProvider.fetchWithCache(targetOcid);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Then
        // 🚀 AOP의 분산 락/동기화 로직이 성공했다면 saveAndFlush는 딱 1번만 호출됩니다.
        verify(equipmentRepository, times(1)).saveAndFlush(any());
    }
}