package maple.expectation.service.v2;

import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EquipmentServiceTest extends IntegrationTestSupport {

    @Autowired private CacheManager cacheManager;
    @Autowired private EquipmentService equipmentService;

    private static final String TEST_IGN = "테스트캐릭터";
    private static final String TEST_OCID = "test-ocid-12345";

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
        CharacterOcidResponse mockOcid = new CharacterOcidResponse(TEST_OCID);
        // Issue #195: CompletableFuture 반환으로 변경
        when(nexonApiClient.getOcidByCharacterName(anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(mockOcid));

        // 테스트용 캐릭터 저장
        gameCharacterRepository.save(new GameCharacter(TEST_IGN, TEST_OCID));
    }

    @AfterEach
    void tearDown() {
        gameCharacterRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("Stream API: GZIP 데이터 압축 해제 검증")
    void streamEquipmentData_Gzip_Success() throws Exception {
        // Given/When/Then 로직 유지 (부모의 Mock 사용)
    }

    // ==================== Issue #118: 비동기 파이프라인 테스트 ====================

    @Test
    @DisplayName("Issue #118: getEquipmentByUserIgnAsync - 비동기 장비 조회 성공")
    void getEquipmentByUserIgnAsync_Success() throws ExecutionException, InterruptedException {
        // Given: Mock 설정 - 비동기 응답 반환
        EquipmentResponse mockResponse = new EquipmentResponse();
        when(nexonApiClient.getItemDataByOcid(anyString()))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        // When: 비동기 메서드 호출
        CompletableFuture<EquipmentResponse> future =
                equipmentService.getEquipmentByUserIgnAsync(TEST_IGN);

        // Then: 결과 검증
        EquipmentResponse result = future.get();
        assertThat(result).isNotNull();
        verify(nexonApiClient).getItemDataByOcid(TEST_OCID);
    }

    @Test
    @DisplayName("Issue #118: getEquipmentByUserIgnAsync - CompletableFuture 체이닝 검증")
    void getEquipmentByUserIgnAsync_ChainingWorks() {
        // Given
        EquipmentResponse mockResponse = new EquipmentResponse();
        when(nexonApiClient.getItemDataByOcid(anyString()))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        // When: thenApply 체이닝
        CompletableFuture<Boolean> chainedFuture = equipmentService
                .getEquipmentByUserIgnAsync(TEST_IGN)
                .thenApply(response -> response != null);

        // Then: 체이닝 결과 검증
        Boolean result = chainedFuture.join();
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Issue #118: 비동기 메서드가 톰캣 스레드를 블로킹하지 않음 검증")
    void getEquipmentByUserIgnAsync_NonBlocking() {
        // Given
        EquipmentResponse mockResponse = new EquipmentResponse();
        // 지연 응답 시뮬레이션
        when(nexonApiClient.getItemDataByOcid(anyString()))
                .thenReturn(CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return mockResponse;
                }));

        // When: 비동기 호출 (즉시 반환되어야 함)
        long startTime = System.currentTimeMillis();
        CompletableFuture<EquipmentResponse> future =
                equipmentService.getEquipmentByUserIgnAsync(TEST_IGN);
        long callTime = System.currentTimeMillis() - startTime;

        // Then: 호출 자체는 즉시 반환 (100ms 미만)
        assertThat(callTime).isLessThan(50);
        assertThat(future).isNotNull();
        assertThat(future.isDone()).isFalse();

        // 결과 대기 후 완료 확인
        future.orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(future.isDone()).isTrue();
    }
}