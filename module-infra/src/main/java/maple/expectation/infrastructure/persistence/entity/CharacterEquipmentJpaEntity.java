package maple.expectation.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import maple.expectation.util.converter.GzipStringConverter;

/**
 * JPA Entity for Character Equipment persistence.
 *
 * <p>This is a PERSISTENCE entity with JPA annotations. It belongs to the infrastructure layer and
 * should only be used by repository implementations.
 *
 * <p><b>Important:</b> Business logic has been moved to {@link
 * maple.expectation.domain.model.equipment.CharacterEquipment}. This entity is purely for
 * database mapping.
 *
 * @see maple.expectation.domain.model.equipment.CharacterEquipment
 */
@Entity
@Table(
    name = "character_equipment",
    indexes =
        @Index(name = "idx_character_equipment_updated_at", columnList = "updated_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterEquipmentJpaEntity {

  @Id
  @Column(length = 100)
  private String ocid;

  @Convert(converter = GzipStringConverter.class)
  @Lob
  @Column(columnDefinition = "LONGBLOB", nullable = false)
  private String jsonContent;

  private LocalDateTime updatedAt;

  /**
   * Creates a new CharacterEquipmentJpaEntity.
   *
   * @param ocid the character OCID (primary key)
   * @param jsonContent the equipment JSON content (GZIP compressed)
   * @return new CharacterEquipmentJpaEntity instance
   */
  @Builder
  public CharacterEquipmentJpaEntity(String ocid, String jsonContent) {
    this.ocid = ocid;
    this.jsonContent = jsonContent;
    this.updatedAt = LocalDateTime.now();
  }

  /**
   * Updates the equipment data and timestamp.
   *
   * <p><b>Note:</b> This method exists for JPA/Hibernate usage. Domain-level updates should use
   * the domain entity's {@link maple.expectation.domain.model.equipment.CharacterEquipment#updateData
   * updateData()} method.
   *
   * @param newJsonContent the new JSON content
   */
  public void updateData(String newJsonContent) {
    this.jsonContent = newJsonContent;
    this.updatedAt = LocalDateTime.now();
  }
}
