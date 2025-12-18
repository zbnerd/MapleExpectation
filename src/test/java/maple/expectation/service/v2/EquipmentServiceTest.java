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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
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

    // ✅ 캐싱 프록시(NexonApiCachingProxy) 내부에서 사용할 하위 클라이언트를 Mocking
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
        // 1. NPE 방지: OCID 조회 API에 대한 Mock 응답 정의
        CharacterOcidResponse mockOcidRes = new CharacterOcidResponse();
        mockOcidRes.setOcid(OCID);
        given(realNexonApiClient.getOcidByCharacterName(anyString())).willReturn(mockOcidRes);

        // 2. DB Miss 방지: 테스트용 캐릭터 저장
        // @Transactional이 없으므로 save 호출 시 즉시 DB에 반영됩니다.
        GameCharacter character = new GameCharacter(NICKNAME);
        character.setOcid(OCID);
        gameCharacterRepository.save(character);
    }

    @AfterEach
    void tearDown() {
        // ✅ @Transactional이 없으므로 테스트 종료 후 데이터를 수동으로 삭제해야 함
        equipmentRepository.deleteAll();
        gameCharacterRepository.deleteAll();
    }

    @Test
    @DisplayName("15분 캐싱 전략 테스트: Proxy는 실제 객체, API 클라이언트는 Mock")
    void caching_logic_test() throws Exception {
        // [Given] 1. OCID 조회를 위한 Mock 설정 (NPE 방지 핵심!)
        CharacterOcidResponse mockOcidRes = new CharacterOcidResponse();
        mockOcidRes.setOcid(OCID);
        given(realNexonApiClient.getOcidByCharacterName(anyString()))
                .willReturn(mockOcidRes);

        // [Given] 2. 아이템 데이터 응답 설정 (1차: Warrior, 2차: Magician)
        EquipmentResponse mockRes1 = new EquipmentResponse();
        mockRes1.setDate(LocalDateTime.now().toString());
        mockRes1.setCharacterClass("Warrior");

        EquipmentResponse mockRes2 = new EquipmentResponse();
        mockRes2.setDate(LocalDateTime.now().plusMinutes(1).toString());
        mockRes2.setCharacterClass("Magician");

        given(realNexonApiClient.getItemDataByOcid(OCID))
                .willReturn(mockRes1)
                .willReturn(mockRes2);



        // [When] 1. 최초 조회
        log.info("--- STEP 1. 최초 조회 수행 ---");
        EquipmentResponse response1 = equipmentService.getEquipmentByUserIgn(NICKNAME);
        assertThat(response1.getCharacterClass()).isEqualTo("Warrior");

        // [Then] DB에 데이터가 저장되었는지 확인
        CharacterEquipment savedEntity = equipmentRepository.findById(OCID)
                .orElseThrow(() -> new AssertionError("데이터가 DB에 저장되지 않았습니다."));

        // [When] 2. 시간 조작 (Reflection을 사용하여 20분 전으로 설정)
        log.info("--- STEP 2. 시간 조작 (20분 전으로 타임머신) ---");
        manipulateUpdatedAt(savedEntity, LocalDateTime.now().minusMinutes(20));
        equipmentRepository.saveAndFlush(savedEntity);

        // [When] 3. 만료 후 재조회
        log.info("--- STEP 3. 만료 후 재조회 (캐시 갱신 예상) ---");
        EquipmentResponse response2 = equipmentService.getEquipmentByUserIgn(NICKNAME);

        // [Then] 데이터가 신규 응답(Magician)으로 갱신되었는지 검증
        assertThat(response2.getCharacterClass()).isEqualTo("Magician");
        verify(realNexonApiClient, times(2)).getItemDataByOcid(OCID);
    }

    @Test
    @DisplayName("Legacy API: 기대 비용 계산 검증")
    void calculateTotalExpectationLegacy_Success() {
        // [Given]
        EquipmentResponse mockResponse = new EquipmentResponse();
        EquipmentResponse.ItemEquipment item = new EquipmentResponse.ItemEquipment();
        item.setItemName("앱솔랩스 숄더");
        item.setPotentialOptionGrade("레전드리");
        mockResponse.setItemEquipment(List.of(item));

        given(equipmentProvider.getEquipmentResponse(OCID)).willReturn(mockResponse);
        given(cubeService.calculateExpectedCost(any())).willReturn(50_000_000L);

        // [When]
        TotalExpectationResponse response = equipmentService.calculateTotalExpectationLegacy(NICKNAME);

        // [Then]
        assertThat(response.getTotalCost()).isEqualTo(50_000_000L);
        assertThat(response.getItems().get(0).getItemName()).isEqualTo("앱솔랩스 숄더");
    }

    @Test
    @DisplayName("Stream API: GZIP 데이터 압축 해제 검증")
    void streamEquipmentData_Gzip_Success() throws Exception {
        // 1. 유효한 GZIP 바이너리 데이터 생성
        String content = "{\"data\":\"test-content\"}";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write(content.getBytes());
        }
        byte[] validGzipData = bos.toByteArray();

        given(equipmentProvider.getRawEquipmentData(anyString()))
                .willReturn(validGzipData);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        equipmentService.streamEquipmentData(NICKNAME, outputStream);

        assertThat(outputStream.toString()).contains("test-content");
    }

    /**
     * 엔티티의 updatedAt 필드를 강제로 수정하기 위한 리플렉션 헬퍼 메서드
     */
    private void manipulateUpdatedAt(CharacterEquipment entity, LocalDateTime targetTime) throws Exception {
        Field timeField = CharacterEquipment.class.getDeclaredField("updatedAt");
        timeField.setAccessible(true);
        timeField.set(entity, targetTime);
    }
}