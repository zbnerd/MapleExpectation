package maple.expectation.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.Optional;
import maple.expectation.infrastructure.persistence.entity.CharacterEquipmentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository for CharacterEquipmentJpaEntity.
 *
 * <p>This repository is used by the adapter layer to persist JPA entities.
 */
@Repository
public interface CharacterEquipmentJpaRepository
    extends JpaRepository<CharacterEquipmentJpaEntity, String> {

  /**
   * Find equipment updated after threshold (for TTL-based caching).
   *
   * @param ocid the character OCID
   * @param threshold the minimum update time
   * @return equipment if found and fresh
   */
  Optional<CharacterEquipmentJpaEntity> findByOcidAndUpdatedAtAfter(
      String ocid, LocalDateTime threshold);
}
