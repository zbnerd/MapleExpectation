package maple.expectation.service.v1;

import lombok.RequiredArgsConstructor;
import maple.expectation.domain.v1.Equipment;
import maple.expectation.repository.v1.EquipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Deprecated
@RequiredArgsConstructor
@Service("equipmentServiceV1")
@Transactional(readOnly = true)
public class EquipmentService {

    private final EquipmentRepository equipmentRepository;

    @Transactional
    public Long saveEquipment(Equipment equipment) {
        return equipmentRepository.save(equipment);
    }

    public Equipment findById(Long id) {
        return equipmentRepository.findById(id);
    }
}
