package maple.expectation.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.MaplestoryApiClient;
import maple.expectation.external.dto.v2.EquipmentResponse;
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
    private EquipmentDataProvider provider;

    @Mock
    private CharacterEquipmentRepository equipmentRepository;

    @Mock
    private MaplestoryApiClient apiClient;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("동시에 10명이 같은 유저 조회 시, API 호출은 1회만 발생해야 한다")
    void concurrencyTest() throws InterruptedException {
        // Given
        int threadCount = 10;
        String targetOcid = "ocid_test_123";
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // 💡 [핵심 해결책] 실제 DB처럼 동작하도록 'AtomicReference'를 사용하여 상태 구현
        AtomicReference<CharacterEquipment> mockDb = new AtomicReference<>(null);

        // 1. findById: mockDb에 있는 값을 반환하도록 설정 (동적으로 변함!)
        lenient().when(equipmentRepository.findById(targetOcid)).thenAnswer(invocation -> {
            return Optional.ofNullable(mockDb.get());
        });

        // 2. saveAndFlush: 호출되면 mockDb에 값을 저장 (JPA 동작 흉내)
        lenient().when(equipmentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CharacterEquipment entity = invocation.getArgument(0);

            // 주의: Unit Test에선 JPA Auditing(@CreatedDate)이 동작 안 하므로 시간 수동 설정 필요
            // Provider 로직의 isValidCache() 통과를 위해 현재 시간 주입
            if (entity.getUpdatedAt() == null) {
                // Entity에 setUpdatedAt이 없다면 Reflection으로 강제 주입
                // (Entity에 @Setter가 있다면 entity.setUpdatedAt(LocalDateTime.now()) 사용)
                try {
                    ReflectionTestUtils.setField(entity, "updatedAt", LocalDateTime.now());
                } catch (Exception e) {
                    // 필드명이 다르거나 없는 경우 무시 (혹은 테스트 실패 처리)
                }
            }

            mockDb.set(entity); // 가짜 DB 업데이트
            return entity;
        });

        // 3. API 호출 Stubbing
        when(apiClient.getItemDataByOcid(targetOcid)).thenReturn(new EquipmentResponse());

        // 4. @Value 주입
        ReflectionTestUtils.setField(provider, "USE_COMPRESSION", false);

        // When
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    provider.getRawEquipmentData(targetOcid);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // Then
        // 이제 2번째 스레드부터는 mockDb에 저장된 값을 읽어가므로 API를 호출하지 않음!
        verify(apiClient, times(1)).getItemDataByOcid(targetOcid);
        verify(equipmentRepository, times(1)).saveAndFlush(any());
    }
}