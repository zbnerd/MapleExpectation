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

        // 1. Mock 객체 생성 (JSON 문자열 대신 자바 객체 생성)
        EquipmentResponse mockResponse = new EquipmentResponse();
        // 테스트 검증에 필요한 날짜 필드는 꼭 세팅해줘야 합니다.
        // (Setter가 없다면 Reflection이나 @Builder 등을 사용해야 함)
        // 여기서는 Setter가 있다고 가정하거나 리플렉션으로 넣습니다.
        mockResponse.setDate(LocalDateTime.now().toString());


        // 2. GameCharacterService Mocking
        GameCharacter mockCharacter = mock(GameCharacter.class);
        given(mockCharacter.getOcid()).willReturn(mockOcid);
        given(characterService.findCharacterByUserIgn(nickname)).willReturn(mockCharacter);

        // ✨ 3. MaplestoryApiClient Mocking (핵심 변경)
        // API 클라이언트가 "객체"를 반환하므로, 우리도 "객체"를 리턴해줍니다.
        given(apiClient.getItemDataByOcid(anyString()))
                .willReturn(mockResponse);


        // --- 이후 로직은 동일 ---

        // 1. [최초 조회]
        log.info("--- 1. 최초 조회 요청 ---");
        EquipmentResponse response1 = equipmentService.getEquipmentByUserIgn(nickname);

        assertThat(response1).isNotNull();
        assertThat(response1.getDate()).isEqualTo(mockResponse.getDate()); // 날짜 확인

        em.flush();
        CharacterEquipment entity = equipmentRepository.findById(mockOcid)
                .orElseThrow(() -> new IllegalArgumentException("저장된 데이터가 없습니다."));
        log.info("DB 저장 완료. 업데이트 시간: {}", entity.getUpdatedAt());


        // 2. [즉시 재조회]
        log.info("--- 2. 즉시 재조회 요청 (Cache Hit 예상) ---");
        EquipmentResponse response2 = equipmentService.getEquipmentByUserIgn(nickname);

        assertThat(response2.getDate()).isEqualTo(response1.getDate());


        // 3. [시간 조작] 20분 전으로 설정
        log.info("--- 3. 시간 조작 (20분 전으로 타임머신) ---");
        Field timeField = CharacterEquipment.class.getDeclaredField("updatedAt");
        timeField.setAccessible(true);
        timeField.set(entity, LocalDateTime.now().minusMinutes(20));

        equipmentRepository.saveAndFlush(entity);
        em.clear();


        // 4. [만료 후 조회]
        log.info("--- 4. 만료 후 재조회 요청 (Cache Expired 예상) ---");
        EquipmentResponse response3 = equipmentService.getEquipmentByUserIgn(nickname);

        CharacterEquipment refreshedEntity = equipmentRepository.findById(mockOcid).get();
        assertThat(refreshedEntity.getUpdatedAt()).isAfter(LocalDateTime.now().minusMinutes(5));
    }
}