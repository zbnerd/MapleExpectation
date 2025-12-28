package maple.expectation.service.v2;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.impl.RealNexonApiClient;
import maple.expectation.parser.EquipmentStreamingParser;
import maple.expectation.provider.EquipmentDataProvider;
import maple.expectation.provider.EquipmentFetchProvider; // 추가
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.repository.v2.GameCharacterRepository;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Slf4j
@SpringBootTest
class EquipmentServiceTest {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private EquipmentService equipmentService;

    @Autowired
    private CharacterEquipmentRepository equipmentRepository;

    @Autowired
    private GameCharacterRepository gameCharacterRepository;

    @MockitoSpyBean
    @Qualifier("realNexonApiClient")
    private RealNexonApiClient realNexonApiClient;

    @MockitoSpyBean
    private EquipmentFetchProvider fetchProvider; // 🚀 캐시 관문 스파이 추가

    @MockitoSpyBean
    private EquipmentDataProvider equipmentProvider;

    @MockitoBean
    private EquipmentStreamingParser streamingParser;

    private final String USERIGN = "개리";
    private final String OCID = "test-ocid-12345";

    @BeforeEach
    void setUp() throws Exception {
        equipmentRepository.deleteAllInBatch();
        gameCharacterRepository.deleteAllInBatch();
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());

        // 💡 모든 스파이 객체의 AOP 타겟을 획득하고 초기화
        RealNexonApiClient spyClient = AopTestUtils.getTargetObject(realNexonApiClient);
        EquipmentFetchProvider spyFetch = AopTestUtils.getTargetObject(fetchProvider);
        EquipmentDataProvider spyProvider = AopTestUtils.getTargetObject(equipmentProvider);

        Mockito.reset(spyClient, spyFetch, spyProvider);

        GameCharacter character = new GameCharacter(USERIGN, OCID);
        gameCharacterRepository.saveAndFlush(character);

        CharacterOcidResponse mockOcidRes = new CharacterOcidResponse();
        mockOcidRes.setOcid(OCID);
        doReturn(mockOcidRes).when(spyClient).getOcidByCharacterName(anyString());
    }

    @Test
    @DisplayName("15분 캐싱 전략 테스트: 데이터는 동일하지만 만료 시 API를 재호출한다")
    void caching_logic_test() throws Exception {
        RealNexonApiClient spyClient = AopTestUtils.getTargetObject(realNexonApiClient);

        EquipmentResponse mockRes = new EquipmentResponse();
        mockRes.setCharacterClass("Warrior");

        // 🚀 클라이언트 호출은 여전히 비동기이므로 CompletableFuture로 스터빙
        doReturn(CompletableFuture.completedFuture(mockRes))
                .when(spyClient).getItemDataByOcid(OCID);

        log.info("--- STEP 1. 최초 조회 수행 ---");
        EquipmentResponse response1 = equipmentService.getEquipmentByUserIgn(USERIGN);
        assertThat(response1.getCharacterClass()).isEqualTo("Warrior");

        // L1 캐시만 비워서 L2(Redis)나 L3(DB)를 타게 유도
        cacheManager.getCache("equipment").evict(OCID); // 키를 OCID로 변경 (FetchProvider 기준)

        log.info("--- STEP 2. 시간 조작 (20분 전으로 타임머신) ---");
        CharacterEquipment savedEntity = equipmentRepository.findById(OCID).orElseThrow();
        manipulateUpdatedAt(savedEntity, LocalDateTime.now().minusMinutes(20));
        equipmentRepository.saveAndFlush(savedEntity);

        log.info("--- STEP 3. 만료 후 재조회 ---");
        EquipmentResponse response2 = equipmentService.getEquipmentByUserIgn(USERIGN);

        assertThat(response2.getCharacterClass()).isEqualTo("Warrior");

        // 🚀 최종적으로 클라이언트(API)가 2번 호출되었는지 검증
        verify(spyClient, times(2)).getItemDataByOcid(OCID);
    }

    @Test
    @DisplayName("Stream API: GZIP 데이터 압축 해제 검증")
    void streamEquipmentData_Gzip_Success() throws Exception {
        EquipmentDataProvider spyProvider = AopTestUtils.getTargetObject(equipmentProvider);

        String content = "{\"data\":\"test-content\"}";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write(content.getBytes());
        }
        byte[] validGzipData = bos.toByteArray();

        doReturn(CompletableFuture.completedFuture(validGzipData))
                .when(spyProvider).getRawEquipmentData(anyString());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        equipmentService.streamEquipmentData(USERIGN, outputStream);

        assertThat(outputStream.toString()).contains("test-content");
    }

    private void manipulateUpdatedAt(CharacterEquipment entity, LocalDateTime targetTime) throws Exception {
        Field timeField = CharacterEquipment.class.getDeclaredField("updatedAt");
        timeField.setAccessible(true);
        timeField.set(entity, targetTime);
    }
}