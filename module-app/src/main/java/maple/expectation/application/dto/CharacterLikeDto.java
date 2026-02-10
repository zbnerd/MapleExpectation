package maple.expectation.application.dto;

import java.time.LocalDateTime;
import maple.expectation.domain.CharacterLike;

/**
 * CharacterLike Data Transfer Object
 *
 * <p><b>Purpose:</b> Encapsulates character "like" (favorite) data for transfer between application
 * layers. This DTO separates the API contract from the domain model, allowing independent
 * evolution.
 *
 * <p><b>Key Concepts:</b>
 *
 * <ul>
 *   <li>Represents a like relationship between a user and a character
 *   <li>Unique constraint on (target_ocid, liker_account_id) prevents duplicate likes
 *   <li>Immutable after creation (only deletion is allowed)
 * </ul>
 *
 * <p><b>Mapping:</b>
 *
 * <ul>
 *   <li>Entity: {@link CharacterLike}
 *   <li>Fields: Same as entity (IDs, creation timestamp)
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * // Convert from entity
 * CharacterLike entity = repository.findByTargetOcidAndLikerAccountId("char123", "user456").orElseThrow();
 * CharacterLikeDto dto = CharacterLikeDto.from(entity);
 *
 * // Create new like
 * CharacterLikeDto dto = CharacterLikeDto.forCreation("char123", "user456");
 * CharacterLike entity = dto.toEntity();
 * repository.save(entity);
 *
 * // Use in API response
 * return ResponseEntity.ok(dto);
 * }</pre>
 *
 * <p><b>Design Decisions:</b>
 *
 * <ul>
 *   <li>Includes creation timestamp for sorting and display
 *   <li>Provides factory methods for clean creation
 *   <li>Immutable after creation (no setters for key fields)
 * </ul>
 *
 * @see CharacterLike
 */
public class CharacterLikeDto extends BaseDto {

  private Long id;
  private String targetOcid;
  private String likerAccountId;
  private LocalDateTime createdAt;

  // ==================== Constructors ====================

  /** Default constructor (for frameworks) */
  public CharacterLikeDto() {}

  /** Full constructor (private, use factory methods) */
  private CharacterLikeDto(
      Long id, String targetOcid, String likerAccountId, LocalDateTime createdAt) {
    this.id = id;
    this.targetOcid = targetOcid;
    this.likerAccountId = likerAccountId;
    this.createdAt = createdAt;
  }

  // ==================== Factory Methods ====================

  /**
   * Create DTO from domain entity
   *
   * <p>This method extracts all fields from the entity.
   *
   * @param entity the domain entity (must not be null)
   * @return the DTO representation
   * @throws IllegalArgumentException if entity is null
   */
  public static CharacterLikeDto from(CharacterLike entity) {
    if (entity == null) {
      throw new IllegalArgumentException("Entity must not be null");
    }
    return new CharacterLikeDto(
        entity.getId(), entity.getTargetOcid(), entity.getLikerAccountId(), entity.getCreatedAt());
  }

  /**
   * Create DTO for new like
   *
   * <p>This factory method is used when creating a new like from user input.
   *
   * @param targetOcid the OCID of the character being liked
   * @param likerAccountId the account ID of the user who liked
   * @return the DTO for creation
   */
  public static CharacterLikeDto forCreation(String targetOcid, String likerAccountId) {
    CharacterLikeDto dto = new CharacterLikeDto();
    dto.targetOcid = targetOcid;
    dto.likerAccountId = likerAccountId;
    dto.createdAt = LocalDateTime.now();
    return dto;
  }

  // ==================== Conversion Methods ====================

  /**
   * Convert DTO to domain entity
   *
   * <p>This method uses the entity's factory method {@link CharacterLike#of(String, String)}.
   *
   * @return the domain entity
   */
  public CharacterLike toEntity() {
    return CharacterLike.of(this.targetOcid, this.likerAccountId);
  }

  // ==================== Getters ====================

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getTargetOcid() {
    return targetOcid;
  }

  public String getLikerAccountId() {
    return likerAccountId;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  // ==================== Utility Methods ====================

  /**
   * Check if this like represents a user liking their own character
   *
   * <p><b>Note:</b> This is a basic string comparison. Self-like prevention at the service layer
   * should compare against the user's full list of character OCIDs.
   *
   * @param userCharacterOcids the OCIDs of characters owned by the user
   * @return true if this is a self-like (target OCID is in user's characters)
   */
  public boolean isSelfLike(java.util.List<String> userCharacterOcids) {
    return userCharacterOcids != null && userCharacterOcids.contains(this.targetOcid);
  }

  /**
   * Get the age of the like in days
   *
   * @return the age in days, or null if createdAt is not set
   */
  public Long getAgeInDays() {
    if (this.createdAt == null) {
      return null;
    }
    return java.time.Duration.between(this.createdAt, LocalDateTime.now()).toDays();
  }

  /**
   * Check if this like was created recently (within the specified days)
   *
   * @param days the threshold in days
   * @return true if the like was created within the threshold, false otherwise
   */
  public boolean isRecent(long days) {
    if (this.createdAt == null) {
      return false;
    }
    LocalDateTime threshold = LocalDateTime.now().minusDays(days);
    return this.createdAt.isAfter(threshold);
  }
}
