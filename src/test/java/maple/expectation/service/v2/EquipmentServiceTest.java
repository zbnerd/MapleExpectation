package maple.expectation.service.v2;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.external.impl.RealNexonApiClient;
import maple.expectation.parser.EquipmentStreamingParser;
import maple.expectation.provider.EquipmentDataProvider;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.repository.v2.GameCharacterRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture; // 1. 임포트 추가
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Slf4j
@SpringBootTest
class EquipmentServiceTest {

    @Autowired
    private EquipmentService equipmentService;

    @Autowired
    private CharacterEquipmentRepository equipmentRepository;

    @Autowired
    private GameCharacterRepository gameCharacterRepository;

    @Autowired
    private EntityManager em;

    @MockitoBean
    private RealNexonApiClient realNexonApiClient;

    @MockitoSpyBean
    private EquipmentDataProvider equipmentProvider;

    @MockitoBean
    private EquipmentStreamingParser streamingParser;

    @MockitoBean
    private CubeService cubeService;

    private final String NICKNAME = "개리";
    private final String OCID = "test-ocid-12345";

    @BeforeEach
    void setUp() {
        CharacterOcidResponse mockOcidRes = new CharacterOcidResponse();
        mockOcidRes.setOcid(OCID);
        given(realNexonApiClient.getOcidByCharacterName(anyString())).willReturn(mockOcidRes);

        GameCharacter character = new GameCharacter(NICKNAME);
        character.setOcid(OCID);
        gameCharacterRepository.save(character);
    }

    @AfterEach
    void tearDown() {
        equipmentRepository.deleteAll();
        gameCharacterRepository.deleteAll();
    }

    @Test
    @DisplayName("15분 캐싱 전략 테스트: Proxy는 실제 객체, API 클라이언트는 Mock")
    void caching_logic_test() throws Exception {
        CharacterOcidResponse mockOcidRes = new CharacterOcidResponse();
        mockOcidRes.setOcid(OCID);
        given(realNexonApiClient.getOcidByCharacterName(anyString()))
                .willReturn(mockOcidRes);

        EquipmentResponse mockRes1 = new EquipmentResponse();
        mockRes1.setDate(LocalDateTime.now().toString());
        mockRes1.setCharacterClass("Warrior");

        EquipmentResponse mockRes2 = new EquipmentResponse();
        mockRes2.setDate(LocalDateTime.now().plusMinutes(1).toString());
        mockRes2.setCharacterClass("Magician");

        // 💡 2. 수정 포인트: 비동기 객체로 반환하도록 설정
        given(realNexonApiClient.getItemDataByOcid(OCID))
                .willReturn(CompletableFuture.completedFuture(mockRes1))
                .willReturn(CompletableFuture.completedFuture(mockRes2));

        log.info("--- STEP 1. 최초 조회 수행 ---");
        EquipmentResponse response1 = equipmentService.getEquipmentByUserIgn(NICKNAME);
        assertThat(response1.getCharacterClass()).isEqualTo("Warrior");

        CharacterEquipment savedEntity = equipmentRepository.findById(OCID)
                .orElseThrow(() -> new AssertionError("데이터가 DB에 저장되지 않았습니다."));

        log.info("--- STEP 2. 시간 조작 (20분 전으로 타임머신) ---");
        manipulateUpdatedAt(savedEntity, LocalDateTime.now().minusMinutes(20));
        equipmentRepository.saveAndFlush(savedEntity);

        log.info("--- STEP 3. 만료 후 재조회 (캐시 갱신 예상) ---");
        EquipmentResponse response2 = equipmentService.getEquipmentByUserIgn(NICKNAME);

        assertThat(response2.getCharacterClass()).isEqualTo("Magician");
        verify(realNexonApiClient, times(2)).getItemDataByOcid(OCID);
    }

    @Test
    @Disabled
    @DisplayName("Legacy API: 기대 비용 계산 검증")
    void calculateTotalExpectationLegacy_Success() {
        EquipmentResponse mockResponse = new EquipmentResponse();
        EquipmentResponse.ItemEquipment item = new EquipmentResponse.ItemEquipment();
        item.setItemName("앱솔랩스 숄더");
        item.setPotentialOptionGrade("레전드리");
        mockResponse.setItemEquipment(List.of(item));

        // 💡 3. 수정 포인트: completedFuture로 감싸기
        given(equipmentProvider.getEquipmentResponse(OCID))
                .willReturn(CompletableFuture.completedFuture(mockResponse));

        given(cubeService.calculateExpectedCost(any())).willReturn(50_000_000L);

        TotalExpectationResponse response = equipmentService.calculateTotalExpectationLegacy(NICKNAME);

        assertThat(response.getTotalCost()).isEqualTo(50_000_000L);
        assertThat(response.getItems().get(0).getItemName()).isEqualTo("앱솔랩스 숄더");
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

        // 💡 4. 수정 포인트: completedFuture로 감싸기
        given(equipmentProvider.getRawEquipmentData(anyString()))
                .willReturn(CompletableFuture.completedFuture(validGzipData));

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        equipmentService.streamEquipmentData(NICKNAME, outputStream);

        assertThat(outputStream.toString()).contains("test-content");
    }

    private void manipulateUpdatedAt(CharacterEquipment entity, LocalDateTime targetTime) throws Exception {
        Field timeField = CharacterEquipment.class.getDeclaredField("updatedAt");
        timeField.setAccessible(true);
        timeField.set(entity, targetTime);
    }
}