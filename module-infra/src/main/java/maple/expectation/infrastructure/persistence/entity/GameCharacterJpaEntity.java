package maple.expectation.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import maple.expectation.domain.model.character.CharacterId;
import maple.expectation.domain.model.character.GameCharacter;
import maple.expectation.domain.model.character.UserIgn;

/**
 * JPA Entity for Game Character persistence.
 *
 * <p>This is a PERSISTENCE entity with JPA annotations. It belongs to infrastructure layer and
 * should only be used by repository implementations.
 *
 * <p><b>Important:</b> Business logic has been moved to {@link
 * maple.expectation.domain.model.character.GameCharacter}. This entity is purely for database
 * mapping.
 *
 * @see maple.expectation.domain.model.character.GameCharacter
 */
@Entity
@Table(name = "game_character")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameCharacterJpaEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String userIgn;

  @Column(nullable = false, unique = true)
  private String ocid;

  @Column(length = 50)
  private String worldName;

  @Column(length = 50)
  private String characterClass;

  @Column(length = 2048)
  private String characterImage;

  @Column private LocalDateTime basicInfoUpdatedAt;

  @Version private Long version;

  private Long likeCount = 0L;

  private LocalDateTime updatedAt;

  /**
   * Creates a new GameCharacterJpaEntity.
   *
   * @param userIgn in-game name
   * @param ocid character OCID
   * @return new GameCharacterJpaEntity instance
   */
  public GameCharacterJpaEntity(UserIgn userIgn, CharacterId ocid) {
    this.userIgn = userIgn.value();
    this.ocid = ocid.value();
    this.likeCount = 0L;
    this.updatedAt = LocalDateTime.now();
  }

  /**
   * Converts JPA entity to domain model.
   *
   * <p><b>Note:</b> Equipment is stored separately in character_equipment table. Use {@code
   * CharacterEquipmentRepository} to load equipment data.
   *
   * @return GameCharacter domain instance
   */
  public GameCharacter toDomain() {
    // Create value objects from strings
    UserIgn userIgn = UserIgn.of(this.userIgn);
    CharacterId characterId = CharacterId.of(this.ocid);

    // Create new instance with required constructor params
    // Equipment is null - it must be loaded separately from CharacterEquipmentRepository
    return GameCharacter.restore(
        this.id,
        characterId,
        userIgn,
        null, // equipment stored separately
        this.worldName,
        this.characterClass,
        this.characterImage,
        this.basicInfoUpdatedAt,
        this.likeCount,
        this.version,
        this.updatedAt);
  }

  /**
   * Converts domain model to JPA entity.
   *
   * <p><b>Note:</b> This is a static factory method. For existing entities, update fields directly.
   *
   * @param domain GameCharacter domain instance
   * @return GameCharacterJpaEntity instance
   */
  public static GameCharacterJpaEntity fromDomain(GameCharacter domain) {
    if (domain == null) {
      throw new IllegalArgumentException("Domain cannot be null");
    }

    GameCharacterJpaEntity entity;
    if (domain.id() == null) {
      // New entity
      entity = new GameCharacterJpaEntity(domain.userIgn(), domain.characterId());
    } else {
      // Existing entity - preserve ID and version
      entity = new GameCharacterJpaEntity();
      entity.id = domain.id();
      entity.userIgn = domain.userIgn().value();
      entity.ocid = domain.characterId().value();
    }

    // Copy mutable fields
    entity.worldName = domain.worldName();
    entity.characterClass = domain.characterClass();
    entity.characterImage = domain.characterImage();
    entity.basicInfoUpdatedAt = domain.basicInfoUpdatedAt();
    entity.likeCount = domain.likeCount();
    entity.updatedAt = domain.updatedAt();
    // Note: equipment is stored separately in character_equipment table

    return entity;
  }
}
