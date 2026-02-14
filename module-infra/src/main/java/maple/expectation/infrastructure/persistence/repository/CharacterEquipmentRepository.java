package maple.expectation.infrastructure.persistence.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import maple.expectation.infrastructure.persistence.entity.CharacterEquipmentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterEquipmentRepository
    extends JpaRepository<CharacterEquipmentJpaEntity, String> {

  /**
   * 15분 TTL 체크를 포함한 유효 데이터 조회
   *
   * <p>updatedAt이 threshold 이후인 데이터만 반환
   *
   * @param ocid 캐릭터 OCID
   * @param threshold 유효 기준 시간 (예: now - 15분)
   * @return 유효한 장비 데이터 (없거나 만료되면 empty)
   */
  Optional<CharacterEquipmentJpaEntity> findByOcidAndUpdatedAtAfter(
      String ocid, LocalDateTime threshold);
}
