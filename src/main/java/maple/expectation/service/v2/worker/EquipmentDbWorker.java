package maple.expectation.service.v2.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentDbWorker {
    private final CharacterEquipmentRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * REQUIRES_NEW를 통해 호출측 트랜잭션과 무관하게 즉시 커밋합니다.
     * 이 작업이 끝나야만 404(조회 실패) 현상이 근본적으로 해결됩니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(String ocid, EquipmentResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            CharacterEquipment entity = repository.findById(ocid)
                    .orElseGet(() -> CharacterEquipment.builder().ocid(ocid).build());

            entity.updateData(json);
            repository.saveAndFlush(entity); // 즉시 물리적 저장
            log.info("💾 [Async DB Save Success] ocid: {}", ocid);
        } catch (Exception e) {
            log.error("❌ [Async DB Save Error] ocid: {}", ocid, e);
        }
    }
}