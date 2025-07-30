package maple.expectation.service;

import lombok.RequiredArgsConstructor;
import maple.expectation.domain.ItemEquipment;
import maple.expectation.external.MaplestoryApiClient;
import maple.expectation.repository.ItemEquipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class ItemEquipmentService {

    private final ItemEquipmentRepository itemEquipmentRepository;
    private final MaplestoryApiClient maplestoryApiClient;
    
    @Transactional
    public Long saveItem(ItemEquipment itemEquipment) {
        itemEquipmentRepository.save(itemEquipment);
        return itemEquipment.getId();
    }
    
    @Transactional
    public List<Long> saveItems(List<ItemEquipment> itemEquipments) {
        itemEquipmentRepository.saveAll(itemEquipments);
        return itemEquipments.stream()
                .map(ItemEquipment::getId)
                .collect(Collectors.toList());
    }


}
