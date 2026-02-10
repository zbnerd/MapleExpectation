package maple.expectation.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import maple.expectation.domain.model.character.CharacterId;
import maple.expectation.domain.model.equipment.CharacterEquipment;
import maple.expectation.domain.model.equipment.EquipmentData;
import maple.expectation.infrastructure.persistence.entity.CharacterEquipmentJpaEntity;

/**
 * Mapper for converting between JPA entity and domain entity.
 *
 * <p>This mapper is responsible for:
 *
 * <ul>
 *   <li>Converting JPA entity → Domain entity (for read operations)
 *   <li>Converting Domain entity → JPA entity (for write operations)
 * </ul>
 *
 * <h3>Design Principles</h3>
 *
 * <ul>
 *   <li><b>Stateless</b>: All methods are static and thread-safe
 *   <li><b>Null Safety</b>: Validates input and throws {@link IllegalArgumentException} for null
 *       values
 *   <li><b>Complete Mapping</b>: All fields including {@code updatedAt} are preserved
 *   <li><b>Separation of Concerns</b>: Infrastructure concern isolated from domain layer
 * </ul>
 *
 * @see CharacterEquipmentJpaEntity
 * @see maple.expectation.domain.model.equipment.CharacterEquipment
 */
public final class CharacterEquipmentMapper {

  private CharacterEquipmentMapper() {
    // Utility class - prevent instantiation
  }

  /**
   * Converts JPA entity to domain entity.
   *
   * <p>Use this method when loading data from database or cache. Preserves the original {@code
   * updatedAt} timestamp.
   *
   * @param jpaEntity the JPA entity (must not be null)
   * @return domain entity with restored state
   * @throws IllegalArgumentException if jpaEntity is null
   */
  public static CharacterEquipment toDomain(CharacterEquipmentJpaEntity jpaEntity) {
    if (jpaEntity == null) {
      throw new IllegalArgumentException("JPA entity cannot be null");
    }

    return CharacterEquipment.restore(
        CharacterId.of(jpaEntity.getOcid()),
        EquipmentData.of(jpaEntity.getJsonContent()),
        jpaEntity.getUpdatedAt());
  }

  /**
   * Converts domain entity to JPA entity for new insertion.
   *
   * <p>Use this method when saving a new entity to database. The JPA entity will have {@code
   * updatedAt} set from the domain entity.
   *
   * @param domainEntity the domain entity (must not be null)
   * @return JPA entity ready for persistence
   * @throws IllegalArgumentException if domainEntity is null
   */
  public static CharacterEquipmentJpaEntity toJpaEntity(CharacterEquipment domainEntity) {
    if (domainEntity == null) {
      throw new IllegalArgumentException("Domain entity cannot be null");
    }

    return CharacterEquipmentJpaEntity.builder()
        .ocid(domainEntity.getCharacterId().value())
        .jsonContent(domainEntity.getEquipmentData().jsonContent())
        .build();
  }

  /**
   * Updates existing JPA entity from domain entity.
   *
   * <p>Use this method when updating an existing entity in database. Preserves the JPA entity's
   * identity and updates only the data fields.
   *
   * @param jpaEntity the existing JPA entity to update (must not be null)
   * @param domainEntity the domain entity with new data (must not be null)
   * @throws IllegalArgumentException if either parameter is null
   */
  public static void updateJpaEntity(
      CharacterEquipmentJpaEntity jpaEntity, CharacterEquipment domainEntity) {
    if (jpaEntity == null) {
      throw new IllegalArgumentException("JPA entity cannot be null");
    }
    if (domainEntity == null) {
      throw new IllegalArgumentException("Domain entity cannot be null");
    }

    // Use JPA entity's updateData method to preserve entity lifecycle
    jpaEntity.updateData(domainEntity.getEquipmentData().jsonContent());
  }

  /**
   * Converts domain entity to JPA entity, reusing existing JPA entity instance.
   *
   * <p>This is an optimized version for batch updates where you want to reuse existing JPA entity
   * instances.
   *
   * @param jpaEntity the existing JPA entity to update
   * @param domainEntity the domain entity with new data
   * @return the updated JPA entity (same instance as jpaEntity parameter)
   */
  public static CharacterEquipmentJpaEntity toJpaEntity(
      CharacterEquipmentJpaEntity jpaEntity, CharacterEquipment domainEntity) {
    updateJpaEntity(jpaEntity, domainEntity);
    return jpaEntity;
  }
}
