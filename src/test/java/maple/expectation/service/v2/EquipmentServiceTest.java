package maple.expectation.service.v2;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.impl.RealNexonApiClient;
import maple.expectation.parser.EquipmentStreamingParser;
import maple.expectation.provider.EquipmentDataProvider;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.repository.v2.GameCharacterRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
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
    private org.springframework.cache.CacheManager cacheManager;

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
    private EquipmentDataProvider equipmentProvider;

    @MockitoBean
    private EquipmentStreamingParser streamingParser;


    private final String USERIGN = "개리";
    private final String OCID = "test-ocid-12345";

    @BeforeEach
    void setUp() {
        // 💡 1. DB 청소 (기존 로직)
        equipmentRepository.deleteAllInBatch();
        gameCharacterRepository.deleteAllInBatch();

        // 💡 2. [추가] 모든 메모리 캐시 싹 비우기 (테스트 격리)
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());

        // ... 나머지 기존 생성자 및 모킹 로직 ...
        GameCharacter character = new GameCharacter(USERIGN, OCID);
        gameCharacterRepository.saveAndFlush(character);

        RealNexonApiClient actualClientTarget = AopTestUtils.getUltimateTargetObject(realNexonApiClient);
        CharacterOcidResponse mockOcidRes = new CharacterOcidResponse();
        mockOcidRes.setOcid(OCID);
        doReturn(mockOcidRes).when(actualClientTarget).getOcidByCharacterName(anyString());
    }

    @Test
    @DisplayName("15분 캐싱 전략 테스트: AOP 캐시가 작동하여 DB에 저장되고 만료 시 갱신된다")
    void caching_logic_test() throws Exception {
        // [Given] - 생략 (기존과 동일)
        EquipmentResponse mockRes1 = new EquipmentResponse();
        mockRes1.setCharacterClass("Warrior");
        EquipmentResponse mockRes2 = new EquipmentResponse();
        mockRes2.setCharacterClass("Magician");

        RealNexonApiClient actualClientTarget = AopTestUtils.getUltimateTargetObject(realNexonApiClient);
        doReturn(CompletableFuture.completedFuture(mockRes1))
                .doReturn(CompletableFuture.completedFuture(mockRes2))
                .when(actualClientTarget).getItemDataByOcid(OCID);

        log.info("--- STEP 1. 최초 조회 수행 ---");
        EquipmentResponse response1 = equipmentService.getEquipmentByUserIgn(USERIGN);
        assertThat(response1.getCharacterClass()).isEqualTo("Warrior");

        // 💡 [추가] STEP 2로 가기 전, 메모리(L1) 캐시를 강제로 비웁니다.
        // 그래야 다음 호출 때 L2(DB/AOP) 로직이 실행되는지 확인할 수 있습니다.
        cacheManager.getCache("equipment").clear();

        // DB에 잘 저장되었는지 확인
        CharacterEquipment savedEntity = equipmentRepository.findById(OCID)
                .orElseThrow(() -> new AssertionError("데이터가 DB에 저장되지 않았습니다."));

        log.info("--- STEP 2. 시간 조작 (20분 전으로 타임머신) ---");
        manipulateUpdatedAt(savedEntity, LocalDateTime.now().minusMinutes(20));
        equipmentRepository.saveAndFlush(savedEntity);

        log.info("--- STEP 3. 만료 후 재조회 (캐시 갱신 예상) ---");
        // L1이 비워졌고, DB(L2)는 만료되었으므로, 결국 실제 API를 호출하게 됩니다.
        EquipmentResponse response2 = equipmentService.getEquipmentByUserIgn(USERIGN);

        assertThat(response2.getCharacterClass()).isEqualTo("Magician");

        // Target이 2번 호출되었는지 확인
        verify(actualClientTarget, times(2)).getItemDataByOcid(OCID);
    }
    @Test
    @DisplayName("Stream API: GZIP 데이터 압축 해제 검증")
    void streamEquipmentData_Gzip_Success() throws Exception {
        String content = "{\"data\":\"test-content\"}";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write(content.getBytes());
        }
        byte[] validGzipData = bos.toByteArray();

        // 💡 6. Provider 알맹이 모킹
        EquipmentDataProvider actualProviderTarget = AopTestUtils.getUltimateTargetObject(equipmentProvider);
        doReturn(CompletableFuture.completedFuture(validGzipData))
                .when(actualProviderTarget).getRawEquipmentData(anyString());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        equipmentService.streamEquipmentData(USERIGN, outputStream);

        assertThat(outputStream.toString()).contains("test-content");
    }

    @Test
    @DisplayName("동일 유저 재조회 시 DB 호출 없이 캐시에서 반환되어야 한다")
    void issue11_verification_test() {
        // [Given]
        // 1. 가짜 응답 객체 생성
        EquipmentResponse mockResponse = new EquipmentResponse();
        mockResponse.setCharacterClass("Hero");

        // 2. [핵심] Provider를 모킹하여 '성공'을 보장합니다.
        // equipmentProvider는 @MockitoSpyBean이므로 doReturn을 사용해야 실제 로직을 안 탑니다.
        doReturn(CompletableFuture.completedFuture(mockResponse))
                .when(equipmentProvider).getEquipmentResponse(anyString());

        // [When]
        log.info("--- 1회차 호출 (캐시 미스 예상) ---");
        equipmentService.getEquipmentByUserIgn(USERIGN);

        log.info("--- 2회차 호출 (캐시 히트 예상) ---");
        equipmentService.getEquipmentByUserIgn(USERIGN);

        // [Then]
        // 캐시가 정상 작동한다면, 실제 서비스 로직 내부의 'provider.getEquipmentResponse'는
        // 딱 1번만 호출되어야 합니다. (2회차는 프록시가 가로채서 바로 반환하니까요!)
        verify(equipmentProvider, times(1)).getEquipmentResponse(anyString());
    }

    private void manipulateUpdatedAt(CharacterEquipment entity, LocalDateTime targetTime) throws Exception {
        Field timeField = CharacterEquipment.class.getDeclaredField("updatedAt");
        timeField.setAccessible(true);
        timeField.set(entity, targetTime);
    }
}