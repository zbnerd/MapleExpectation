package maple.expectation.service.v2.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EquipmentCacheService {
    private final CharacterEquipmentRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Optional<EquipmentResponse> getValidCache(String ocid) {
        return repository.findById(ocid)
                .filter(e -> e.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(15)))
                .map(this::convertToResponse);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW) // ✅ 메인 로직과 별개로 무조건 저장
    public void saveCache(String ocid, EquipmentResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            CharacterEquipment entity = repository.findById(ocid)
                    .orElseGet(() -> CharacterEquipment.builder().ocid(ocid).build());
            entity.updateData(json);
            repository.saveAndFlush(entity);
        } catch (Exception e) {
            log.error("❌ 캐시 저장 실패: ocid={}", ocid, e);
        }
    }

    private EquipmentResponse convertToResponse(CharacterEquipment entity) {
        try {
            return objectMapper.readValue(entity.getJsonContent(), EquipmentResponse.class);
        } catch (Exception e) { return null; }
    }
}