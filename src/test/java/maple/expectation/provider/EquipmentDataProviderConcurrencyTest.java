package maple.expectation.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.impl.RealNexonApiClient;
import maple.expectation.external.proxy.NexonApiCachingProxy;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EquipmentDataProviderConcurrencyTest {

    @InjectMocks
    private NexonApiCachingProxy proxy;

    @Mock
    private CharacterEquipmentRepository equipmentRepository;

    @Mock
    private RealNexonApiClient realClient;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Proxy: 동시에 10명이 같은 유저 조회 시, 실제 API 호출은 1회만 발생해야 한다")
    void proxyConcurrencyTest() throws InterruptedException {
        // Given
        int threadCount = 10;
        String targetOcid = "ocid_test_123";
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicReference<CharacterEquipment> mockDb = new AtomicReference<>(null);

        // DB 조회 Mocking (Proxy 내부의 isValidCache 로직을 타게 됨)
        lenient().when(equipmentRepository.findById(targetOcid)).thenAnswer(invocation ->
                Optional.ofNullable(mockDb.get())
        );

        // DB 저장 Mocking
        lenient().when(equipmentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CharacterEquipment entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "updatedAt", LocalDateTime.now());
            mockDb.set(entity);
            return entity;
        });

        // 실제 API 호출은 딱 1번만 성공한다고 가정
        when(realClient.getItemDataByOcid(targetOcid)).thenReturn(new EquipmentResponse());

        // 설정값 주입
        ReflectionTestUtils.setField(proxy, "USE_COMPRESSION", false);

        // When
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    proxy.getItemDataByOcid(targetOcid); // Proxy를 호출!
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // Then
        // 1. 실제 외부 API(realClient) 호출은 1회여야 함
        verify(realClient, times(1)).getItemDataByOcid(targetOcid);
        // 2. DB 저장(saveAndFlush)도 1회여야 함
        verify(equipmentRepository, times(1)).saveAndFlush(any());
    }
}