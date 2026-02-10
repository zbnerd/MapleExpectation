package maple.expectation.infrastructure.persistence.repository;

import java.util.Optional;
import maple.expectation.domain.model.equipment.CharacterEquipment;
import maple.expectation.domain.model.character.CharacterId;
import maple.expectation.infrastructure.persistence.CharacterEquipmentJpaRepository;
import maple.expectation.infrastructure.persistence.entity.CharacterEquipmentJpaEntity;
import maple.expectation.infrastructure.persistence.mapper.CharacterEquipmentMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter implementation of CharacterEquipment repository.
 *
 * <p>This is the <b>Adapter</b> in the Hexagonal Architecture pattern. It bridges the domain-layer
 * repository port with the infrastructure-layer Spring Data JPA repository.
 *
 * @see maple.expectation.domain.repository.CharacterEquipmentRepository
 */
@Repository
@Transactional
public class CharacterEquipmentRepositoryImpl
    implements maple.expectation.domain.repository.CharacterEquipmentRepository {

  private final CharacterEquipmentJpaRepository jpaRepo;

  public CharacterEquipmentRepositoryImpl(CharacterEquipmentJpaRepository jpaRepo) {
    this.jpaRepo = jpaRepo;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<CharacterEquipment> findById(CharacterId characterId) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    return jpaRepo.findById(characterId.value()).map(CharacterEquipmentMapper::toDomain);
  }

  @Override
  public CharacterEquipment save(CharacterEquipment equipment) {
    if (equipment == null) {
      throw new IllegalArgumentException("Equipment cannot be null");
    }

    CharacterId id = equipment.getCharacterId();
    Optional<CharacterEquipmentJpaEntity> existing = jpaRepo.findById(id.value());

    if (existing.isPresent()) {
      CharacterEquipmentJpaEntity jpaEntity = existing.get();
      CharacterEquipmentMapper.updateJpaEntity(jpaEntity, equipment);
      jpaRepo.save(jpaEntity);
      return equipment;
    } else {
      CharacterEquipmentJpaEntity jpaEntity = CharacterEquipmentMapper.toJpaEntity(equipment);
      jpaRepo.save(jpaEntity);
      return equipment;
    }
  }

  @Override
  public void deleteById(CharacterId characterId) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    jpaRepo.deleteById(characterId.value());
  }

  @Override
  @Transactional(readOnly = true)
  public boolean existsById(CharacterId characterId) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    return jpaRepo.existsById(characterId.value());
  }
}
