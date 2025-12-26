package maple.expectation.repository.v2;

import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.CharacterEquipment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterEquipmentRepository extends JpaRepository<CharacterEquipment, String> {
}