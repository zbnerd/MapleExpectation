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

    @MockitoBean
    private CubeService cubeService;

    private final String USERIGN = "개리";
    private final String OCID = "test-ocid-12345";

    @BeforeEach
    void setUp() {
        // 💡 1. 수동 DB 청소 (순서 유지)
        equipmentRepository.deleteAllInBatch();
        gameCharacterRepository.deleteAllInBatch();

        // 💡 2. [수정 포인트] 테스트용 기초 데이터 생성
        // Setter를 쓰지 않고, 생성 시점에 이름과 OCID를 모두 주입합니다.
        GameCharacter character = new GameCharacter(USERIGN, OCID);

        // 이제 character는 태어날 때부터 완벽한 상태이므로 바로 저장합니다.
        gameCharacterRepository.saveAndFlush(character);

        // 💡 3. AOP 프록시를 우회하여 진짜 알맹이에 모킹 설정
        RealNexonApiClient actualClientTarget = AopTestUtils.getUltimateTargetObject(realNexonApiClient);

        CharacterOcidResponse mockOcidRes = new CharacterOcidResponse();
        mockOcidRes.setOcid(OCID);

        // OCID 조회 설정
        doReturn(mockOcidRes).when(actualClientTarget).getOcidByCharacterName(anyString());
    }

    @Test
    @DisplayName("15분 캐싱 전략 테스트: AOP 캐시가 작동하여 DB에 저장되고 만료 시 갱신된다")
    void caching_logic_test() throws Exception {
        // [Given]
        EquipmentResponse mockRes1 = new EquipmentResponse();
        mockRes1.setCharacterClass("Warrior");

        EquipmentResponse mockRes2 = new EquipmentResponse();
        mockRes2.setCharacterClass("Magician");

        // 💡 4. 비동기 API 응답 설정 (AOP 알맹이에 설정)
        RealNexonApiClient actualClientTarget = AopTestUtils.getUltimateTargetObject(realNexonApiClient);
        doReturn(CompletableFuture.completedFuture(mockRes1))
                .doReturn(CompletableFuture.completedFuture(mockRes2))
                .when(actualClientTarget).getItemDataByOcid(OCID);

        log.info("--- STEP 1. 최초 조회 수행 ---");
        EquipmentResponse response1 = equipmentService.getEquipmentByUserIgn(USERIGN);
        assertThat(response1.getCharacterClass()).isEqualTo("Warrior");

        // DB에 잘 저장되었는지 확인
        CharacterEquipment savedEntity = equipmentRepository.findById(OCID)
                .orElseThrow(() -> new AssertionError("데이터가 DB에 저장되지 않았습니다."));

        log.info("--- STEP 2. 시간 조작 (20분 전으로 타임머신) ---");
        manipulateUpdatedAt(savedEntity, LocalDateTime.now().minusMinutes(20));
        equipmentRepository.saveAndFlush(savedEntity);

        log.info("--- STEP 3. 만료 후 재조회 (캐시 갱신 예상) ---");
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

    private void manipulateUpdatedAt(CharacterEquipment entity, LocalDateTime targetTime) throws Exception {
        Field timeField = CharacterEquipment.class.getDeclaredField("updatedAt");
        timeField.setAccessible(true);
        timeField.set(entity, targetTime);
    }
}