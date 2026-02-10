package maple.expectation.application.dto;

import java.time.LocalDateTime;
import maple.expectation.domain.v2.GameCharacter;

/**
 * GameCharacter Data Transfer Object
 *
 * <p><b>Purpose:</b> Encapsulates character data for transfer between application layers. This DTO
 * separates the API contract from the domain model, allowing independent evolution.
 *
 * <p><b>Mapping:</b>
 *
 * <ul>
 *   <li>Entity: {@link GameCharacter}
 *   <li>Fields: Subset of entity fields (excludes equipment blob for security)
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * // Convert from entity
 * GameCharacter entity = repository.findByOcid("abc123").orElseThrow();
 * GameCharacterDto dto = GameCharacterDto.from(entity);
 *
 * // Convert to entity
 * GameCharacter entity = dto.toEntity();
 *
 * // Use in API response
 * return ResponseEntity.ok(dto);
 * }</pre>
 *
 * <p><b>Design Decisions:</b>
 *
 * <ul>
 *   <li>Excludes {@code equipment} field to prevent large JSON blobs in API responses
 *   <li>Includes {@code likeCount} for display purposes
 *   <li>Provides factory methods for clean conversion
 * </ul>
 *
 * @see GameCharacter
 */
public class GameCharacterDto extends BaseDto {

  private Long id;
  private String userIgn;
  private String ocid;
  private String worldName;
  private String characterClass;
  private String characterImage;
  private LocalDateTime basicInfoUpdatedAt;
  private Long likeCount;
  private LocalDateTime updatedAt;

  // ==================== Constructors ====================

  /** Default constructor (for frameworks) */
  public GameCharacterDto() {}

  /** Full constructor */
  private GameCharacterDto(
      Long id,
      String userIgn,
      String ocid,
      String worldName,
      String characterClass,
      String characterImage,
      LocalDateTime basicInfoUpdatedAt,
      Long likeCount,
      LocalDateTime updatedAt,
      LocalDateTime createdAt,
      Long version) {
    this.id = id;
    this.userIgn = userIgn;
    this.ocid = ocid;
    this.worldName = worldName;
    this.characterClass = characterClass;
    this.characterImage = characterImage;
    this.basicInfoUpdatedAt = basicInfoUpdatedAt;
    this.likeCount = likeCount;
    this.updatedAt = updatedAt;
    this.createdAt = createdAt;
    this.version = version;
  }

  // ==================== Factory Methods ====================

  /**
   * Create DTO from domain entity
   *
   * <p>This method extracts only the necessary fields from the entity, excluding sensitive or large
   * data like the equipment blob.
   *
   * @param entity the domain entity (must not be null)
   * @return the DTO representation
   * @throws IllegalArgumentException if entity is null
   */
  public static GameCharacterDto from(GameCharacter entity) {
    if (entity == null) {
      throw new IllegalArgumentException("Entity must not be null");
    }
    return new GameCharacterDto(
        entity.getId(),
        entity.getUserIgn(),
        entity.getOcid(),
        entity.getWorldName(),
        entity.getCharacterClass(),
        entity.getCharacterImage(),
        entity.getBasicInfoUpdatedAt(),
        entity.getLikeCount(),
        entity.getUpdatedAt(),
        null, // createdAt not tracked in entity
        entity.getVersion());
  }

  /**
   * Create DTO for new character creation
   *
   * <p>This factory method is used when creating a new character from user input.
   *
   * @param userIgn the in-game name
   * @param ocid the character OCID
   * @return the DTO for creation
   */
  public static GameCharacterDto forCreation(String userIgn, String ocid) {
    GameCharacterDto dto = new GameCharacterDto();
    dto.userIgn = userIgn;
    dto.ocid = ocid;
    dto.likeCount = 0L;
    dto.initTimestamps();
    return dto;
  }

  // ==================== Conversion Methods ====================

  /**
   * Convert DTO to domain entity
   *
   * <p>This method creates a new entity with the DTO's data. Note that this creates a new entity
   * instance - for updates, you should load the existing entity and copy fields.
   *
   * @return the domain entity
   */
  public GameCharacter toEntity() {
    GameCharacter entity = new GameCharacter(this.userIgn, this.ocid);
    entity.setWorldName(this.worldName);
    entity.setCharacterClass(this.characterClass);
    entity.setCharacterImage(this.characterImage);
    entity.setBasicInfoUpdatedAt(this.basicInfoUpdatedAt);
    return entity;
  }

  // ==================== Getters/Setters ====================

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getUserIgn() {
    return userIgn;
  }

  public void setUserIgn(String userIgn) {
    this.userIgn = userIgn;
  }

  public String getOcid() {
    return ocid;
  }

  public void setOcid(String ocid) {
    this.ocid = ocid;
  }

  public String getWorldName() {
    return worldName;
  }

  public void setWorldName(String worldName) {
    this.worldName = worldName;
  }

  public String getCharacterClass() {
    return characterClass;
  }

  public void setCharacterClass(String characterClass) {
    this.characterClass = characterClass;
  }

  public String getCharacterImage() {
    return characterImage;
  }

  public void setCharacterImage(String characterImage) {
    this.characterImage = characterImage;
  }

  public LocalDateTime getBasicInfoUpdatedAt() {
    return basicInfoUpdatedAt;
  }

  public void setBasicInfoUpdatedAt(LocalDateTime basicInfoUpdatedAt) {
    this.basicInfoUpdatedAt = basicInfoUpdatedAt;
  }

  public Long getLikeCount() {
    return likeCount;
  }

  public void setLikeCount(Long likeCount) {
    this.likeCount = likeCount;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
