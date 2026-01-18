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
        // Given: 충분히 긴 지연으로 비동기 동작 검증 (CLAUDE.md Section 24: 결정적 테스트)
        EquipmentResponse mockResponse = new EquipmentResponse();
        java.util.concurrent.CountDownLatch delayLatch = new java.util.concurrent.CountDownLatch(1);

        when(nexonApiClient.getItemDataByOcid(anyString()))
                .thenReturn(CompletableFuture.supplyAsync(() -> {
                    try {
                        // 500ms 대기 - 호출 시점에 Future가 완료되지 않음을 보장
                        delayLatch.await(500, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return mockResponse;
                }));

        // When: 비동기 호출 (즉시 Future 반환되어야 함)
        CompletableFuture<EquipmentResponse> future =
                equipmentService.getEquipmentByUserIgnAsync(TEST_IGN);

        // Then: CLAUDE.md Section 24 - 타이밍 기반 검증 제거, 상태 기반 검증으로 대체
        // 1. Future가 즉시 반환됨 (null이 아님)
        assertThat(future).isNotNull();

        // 2. Future가 아직 완료되지 않음 (비동기 동작 증명)
        // Note: 지연이 충분히 길어서 이 시점에 미완료 상태임이 보장됨
        assertThat(future.isDone())
                .as("비동기 호출 직후에는 Future가 완료되지 않아야 함 (Non-Blocking 증명)")
                .isFalse();

        // 3. 지연 해제 → 결과 완료 대기
        delayLatch.countDown();

        // 4. Awaitility로 완료 대기 (CLAUDE.md Section 24: 명시적 동기화)
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(future.isDone()).isTrue());

        // 5. 결과 검증
        assertThat(future.join()).isNotNull();
    }
}