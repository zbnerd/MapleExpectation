package maple.expectation.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.impl.RealNexonApiClient;
import maple.expectation.external.proxy.NexonApiCachingProxy;
import maple.expectation.global.lock.GuavaLockStrategy;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture; // 추가
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EquipmentDataProviderConcurrencyTest {

    private NexonApiCachingProxy proxy;

    @Mock
    private CharacterEquipmentRepository equipmentRepository;

    @Mock
    private RealNexonApiClient realClient;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private final LockStrategy lockStrategy = new GuavaLockStrategy();

    @BeforeEach
    void setUp() {
        proxy = new NexonApiCachingProxy(realClient, equipmentRepository, objectMapper, lockStrategy);
    }

    @Test
    @DisplayName("Proxy: 동시에 10명이 같은 유저 조회 시, 실제 API 호출은 1회만 발생해야 한다")
    void proxyConcurrencyTest() throws InterruptedException {
        // Given
        int threadCount = 10;
        String targetOcid = "ocid_test_123";
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicReference<CharacterEquipment> mockDb = new AtomicReference<>(null);

        // DB 조회 Mock
        lenient().when(equipmentRepository.findById(targetOcid)).thenAnswer(invocation ->
                Optional.ofNullable(mockDb.get())
        );

        // DB 저장 Mock
        lenient().when(equipmentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CharacterEquipment entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "updatedAt", LocalDateTime.now());
            mockDb.set(entity);
            return entity;
        });

        // ✨ 수정 포인트 1: 반환 타입을 CompletableFuture.completedFuture로 감싸기
        when(realClient.getItemDataByOcid(targetOcid))
                .thenReturn(CompletableFuture.completedFuture(new EquipmentResponse()));

        ReflectionTestUtils.setField(proxy, "USE_COMPRESSION", false);

        // When
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    // ✨ 수정 포인트 2: 비동기 작업이므로 .join()을 호출해 로직이 끝날 때까지 스레드를 대기시켜야 함
                    // 그래야 락 해제와 DB 저장이 끝난 후 latch가 줄어듭니다.
                    proxy.getItemDataByOcid(targetOcid).join();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // Then
        // 1. 진짜 락이 작동했다면, 첫 번째 스레드만 API를 호출하고 나머지는 DB에서 가져갔어야 함
        verify(realClient, times(1)).getItemDataByOcid(targetOcid);
        // 2. 저장도 1번만 일어남
        verify(equipmentRepository, times(1)).saveAndFlush(any());
    }
}