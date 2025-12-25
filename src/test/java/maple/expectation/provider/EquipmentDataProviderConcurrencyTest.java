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
    @Qualifier("realNexonApiClient")
    private NexonApiClient proxiedClient;

    @MockitoSpyBean
    @Qualifier("realNexonApiClient")
    private RealNexonApiClient targetClient;

    @MockitoBean
    private CharacterEquipmentRepository equipmentRepository;

    @Test
    @DisplayName("AOP 기반 캐시: 동시에 10명이 같은 유저 조회 시, 실제 DB 저장(Sync)은 1회만 발생해야 한다")
    void aopConcurrencyTest() throws InterruptedException {
        // Given
        int threadCount = 10;
        String targetOcid = "ocid_test_123";
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicReference<CharacterEquipment> mockDb = new AtomicReference<>(null);

        // 1. 하위 의존성 모킹
        when(equipmentRepository.findById(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(mockDb.get())
        );

        when(equipmentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CharacterEquipment entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "updatedAt", LocalDateTime.now());
            mockDb.set(entity);
            return entity;
        });

        // 💡 2. [핵심 포인트] 프록시 껍데기를 벗겨내고 '진짜 알맹이'를 가져옵니다.
        RealNexonApiClient actualTarget = AopTestUtils.getUltimateTargetObject(targetClient);

        // 💡 3. [문법] 껍데기가 아닌 '진짜 알맹이'에 doReturn 설정을 겁니다.
        // 이렇게 하면 설정을 거는 도중에 NexonDataCacheAspect가 절대 가동되지 않습니다.
        doReturn(CompletableFuture.completedFuture(new EquipmentResponse()))
                .when(actualTarget).getItemDataByOcid(targetOcid);

        // When
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    // 호출은 '프록시(proxiedClient)'를 통해서 해야 AOP가 작동합니다.
                    proxiedClient.getItemDataByOcid(targetOcid).join();
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
        // AOP 락이 성공했다면 saveAndFlush는 딱 1번!
        verify(equipmentRepository, times(1)).saveAndFlush(any());
    }
}