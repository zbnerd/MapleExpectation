package maple.expectation.service;

import lombok.RequiredArgsConstructor;
import maple.expectation.domain.Equipment;
import maple.expectation.repository.EquipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
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
