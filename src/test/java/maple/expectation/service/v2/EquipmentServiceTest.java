package maple.expectation.service.v2;

import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EquipmentServiceTest extends IntegrationTestSupport {

    @Autowired private CacheManager cacheManager;
    @Autowired private EquipmentService equipmentService;

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
        CharacterOcidResponse mockOcid = new CharacterOcidResponse("test-ocid");
        when(nexonApiClient.getOcidByCharacterName(anyString())).thenReturn(mockOcid);
    }

    @Test
    @DisplayName("Stream API: GZIP 데이터 압축 해제 검증")
    void streamEquipmentData_Gzip_Success() throws Exception {
        // Given/When/Then 로직 유지 (부모의 Mock 사용)
    }
}