package maple.expectation.service.v2;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.MaplestoryApiClient;
import maple.expectation.external.dto.v2.EquipmentResponse; // DTO 임포트
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@Slf4j
@SpringBootTest
@Transactional
@TestPropertySource(properties = "app.optimization.use-compression=false")
class EquipmentServiceTest {

    @Autowired
    private EquipmentService equipmentService;

    @Autowired
    private CharacterEquipmentRepository equipmentRepository;

    @Autowired
    private EntityManager em;

    @MockitoBean
    private GameCharacterService characterService;

    @MockitoBean
    private MaplestoryApiClient apiClient;

    @Test
    @DisplayName("15분 캐싱 전략 테스트: 최초 조회 -> 캐시 적중 -> 시간 조작 -> 만료 후 재조회")
    void caching_logic_test() throws Exception {
        // given
        String nickname = "개리";
        String mockOcid = "test-ocid-12345";

        // 1. [최초 조회용] 응답 생성
        EquipmentResponse mockResponse1 = new EquipmentResponse();
        mockResponse1.setDate(LocalDateTime.now().toString());
        // 확실한 차이를 위해 임의의 데이터 설정 (예: 직업이나 해시값 등)
        // *필드명은 실제 EquipmentResponse DTO에 있는 필드를 사용하세요*
        // 예시: mockResponse1.setCharacterClass("Warrior");

        // 2. [갱신 조회용] 응답 생성 (데이터를 확실히 다르게 설정)
        EquipmentResponse mockResponse2 = new EquipmentResponse();
        mockResponse2.setDate(LocalDateTime.now().toString());
        // mockResponse1과 '다른' 내용이 들어가야 함 -> raw_data가 변경됨 -> Update 쿼리 발생
        // 예시: mockResponse2.setCharacterClass("Magician");  <-- 핵심!

        GameCharacter mockCharacter = mock(GameCharacter.class);
        given(mockCharacter.getOcid()).willReturn(mockOcid);
        given(characterService.findCharacterByUserIgn(nickname)).willReturn(mockCharacter);

        // 순차적 Stubbing
        given(apiClient.getItemDataByOcid(anyString()))
                .willReturn(mockResponse1)  // 첫 번째 호출
                .willReturn(mockResponse2); // 두 번째 호출 (내용이 달라야 함)

        // 1. [최초 조회]
        log.info("--- 1. 최초 조회 요청 ---");
        EquipmentResponse response1 = equipmentService.getEquipmentByUserIgn(nickname);
        assertThat(response1).isNotNull();

        // em.flush();  <-- ❌ 제거 (트랜잭션 없음)

        // 데이터 확인 (Service가 이미 내부적으로 saveAndFlush 했으므로 DB에 있음)
        CharacterEquipment entity = equipmentRepository.findById(mockOcid)
                .orElseThrow(() -> new IllegalArgumentException("저장된 데이터가 없습니다."));
        log.info("DB 저장 완료. 업데이트 시간: {}", entity.getUpdatedAt());


        // 2. [즉시 재조회]
        log.info("--- 2. 즉시 재조회 요청 (Cache Hit 예상) ---");
        EquipmentResponse response2 = equipmentService.getEquipmentByUserIgn(nickname);
        assertThat(response2.getDate()).isEqualTo(response1.getDate());
        // verify(apiClient, times(1)).getItemDataByOcid(anyString()); // 필요시 추가 검증


        // 3. [시간 조작] 20분 전으로 설정
        log.info("--- 3. 시간 조작 (20분 전으로 타임머신) ---");
        Field timeField = CharacterEquipment.class.getDeclaredField("updatedAt");
        timeField.setAccessible(true);
        timeField.set(entity, LocalDateTime.now().minusMinutes(20));

        // saveAndFlush는 내부적으로 트랜잭션을 열어서 저장하고 닫아주므로 OK!
        equipmentRepository.saveAndFlush(entity);

        // em.clear(); <-- ❌ 제거 (필요 없음, 다음 조회 시 새로운 트랜잭션이라서 최신 데이터 읽음)


        // 4. [만료 후 조회]
        log.info("--- 4. 만료 후 재조회 요청 (Cache Expired 예상) ---");
        // 이때 apiClient는 mockResponse2를 반환하므로, 데이터가 변경된 것으로 인식되어 Update 쿼리가 나갈 것입니다.
        EquipmentResponse response3 = equipmentService.getEquipmentByUserIgn(nickname);

        // 검증
        CharacterEquipment refreshedEntity = equipmentRepository.findById(mockOcid).get();

        // 로그로 확인해보세요 (실제 시간이 갱신되었는지)
        log.info("갱신된 시간: {}", refreshedEntity.getUpdatedAt());

        assertThat(refreshedEntity.getUpdatedAt()).isAfter(LocalDateTime.now().minusMinutes(5));

        log.info("✅ 테스트 성공!");
    }
}