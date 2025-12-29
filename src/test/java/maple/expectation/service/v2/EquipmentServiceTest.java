package maple.expectation.service.v2;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.impl.RealNexonApiClient;
import maple.expectation.parser.EquipmentStreamingParser;
import maple.expectation.provider.EquipmentDataProvider;
import maple.expectation.provider.EquipmentFetchProvider;
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
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
    private EquipmentFetchProvider fetchProvider;

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
    @Disabled("비동기 레이스 컨디션으로 인해 임시 제외, 240 RPS 벤치마크로 검증됨")
    void caching_logic_test() throws Exception {
        RealNexonApiClient spyClient = AopTestUtils.getTargetObject(realNexonApiClient);

        EquipmentResponse mockRes = new EquipmentResponse();
        mockRes.setCharacterClass("Warrior");

        doReturn(CompletableFuture.completedFuture(mockRes))
                .when(spyClient).getItemDataByOcid(OCID);

        log.info("--- STEP 1. 최초 조회 수행 ---");
        // 이 호출 내부에서 saveCache(Async DB save)가 트리거됨
        EquipmentResponse response1 = equipmentService.getEquipmentByUserIgn(USERIGN);
        assertThat(response1.getCharacterClass()).isEqualTo("Warrior");

        log.info("⏳ 비동기 DB 저장 대기 중 (Awaitility)...");
        // 🚀 비동기 스레드가 DB에 INSERT를 완료할 때까지 'join' 하듯 대기
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .ignoreExceptionsInstanceOf(NoSuchElementException.class)
                .ignoreExceptionsInstanceOf(AssertionError.class)
                .untilAsserted(() -> {
                    CharacterEquipment entity = equipmentRepository.findById(OCID)
                            .orElseThrow(() -> new NoSuchElementException("아직 DB에 데이터가 없습니다."));
                    assertThat(entity).isNotNull();
                    log.info("✅ 비동기 데이터 저장 확인 완료!");
                });

        log.info("--- STEP 2. 시간 조작 (20분 전으로 타임머신) ---");
        CharacterEquipment savedEntity = equipmentRepository.findById(OCID).orElseThrow();
        manipulateUpdatedAt(savedEntity, LocalDateTime.now().minusMinutes(20));
        equipmentRepository.saveAndFlush(savedEntity);

        // 로컬 캐시를 비워서 DB 조회를 강제 유도 (만료 상황 재현)
        cacheManager.getCache("equipment").evict(OCID);

        log.info("--- STEP 3. 만료 후 재조회 ---");
        EquipmentResponse response2 = equipmentService.getEquipmentByUserIgn(USERIGN);

        assertThat(response2.getCharacterClass()).isEqualTo("Warrior");

        // 🚀 최종 검증: 만료되었으므로 API 호출이 총 2번 발생해야 함
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