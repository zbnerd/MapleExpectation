package maple.expectation.service.v2;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field; // 리플렉션 (시간 조작용)
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest // 실제 DB와 연동 테스트
class EquipmentServiceTest {

    @Autowired
    private EquipmentService equipmentService;

    @Autowired
    private CharacterEquipmentRepository equipmentRepository;

    @Test
    @DisplayName("15분 캐싱 전략 테스트: 최초 조회 -> 캐시 적중 -> 시간 조작 -> 만료 후 재조회")
    void caching_logic_test() throws Exception {
        // given
        String nickname = "아델";

        // 1. [최초 조회] Cache Miss -> API 호출 -> DB 저장
        log.info("--- 1. 최초 조회 요청 ---");
        EquipmentResponse response1 = equipmentService.getEquipmentByUserIgn(nickname);
        assertThat(response1).isNotNull();

        // DB에 저장되었는지 확인
        CharacterEquipment entity = equipmentRepository.findAll().get(0);
        assertThat(entity).isNotNull();
        log.info("DB 저장 완료. 업데이트 시간: {}", entity.getUpdatedAt());

        // 2. [즉시 재조회] Cache Hit -> DB에서 가져옴 (로그 확인 필요)
        log.info("--- 2. 즉시 재조회 요청 (Cache Hit 예상) ---");
        EquipmentResponse response2 = equipmentService.getEquipmentByUserIgn(nickname);

        // 데이터가 같아야 함
        assertThat(response2.getDate()).isEqualTo(response1.getDate());


        // 3. [시간 조작] 강제로 20분 전으로 돌리기 (해킹)
        log.info("--- 3. 시간 조작 (20분 전으로 타임머신) ---");

        // JPA Auditing 필드라 setter가 없으므로 리플렉션으로 강제 주입
        Field timeField = CharacterEquipment.class.getDeclaredField("updatedAt");
        timeField.setAccessible(true);
        timeField.set(entity, LocalDateTime.now().minusMinutes(20)); // 20분 전으로 설정
        equipmentRepository.save(entity); // 시간 수정된 거 저장

        log.info("조작된 시간: {}", equipmentRepository.findById(entity.getOcid()).get().getUpdatedAt());

        // 4. [만료 후 조회] Cache Expired -> API 재호출 (로그 확인)
        log.info("--- 4. 만료 후 재조회 요청 (Cache Expired 예상) ---");
        EquipmentResponse response3 = equipmentService.getEquipmentByUserIgn(nickname);

        assertThat(equipmentRepository.findById(entity.getOcid()).get().getUpdatedAt()).isAfter(LocalDateTime.now().minusMinutes(5));
        assertThat(response3).isNotNull();
    }
}