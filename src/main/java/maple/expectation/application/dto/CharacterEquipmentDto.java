package maple.expectation.application.dto;

import java.time.LocalDateTime;
import maple.expectation.domain.v2.CharacterEquipment;

/**
 * CharacterEquipment Data Transfer Object
 *
 * <p><b>Purpose:</b> Encapsulates equipment data for transfer between application layers. This DTO
 * separates the API contract from the domain model, allowing independent evolution.
 *
 * <p><b>Key Concepts:</b>
 *
 * <ul>
 *   <li>JSON content is GZIP-compressed in the entity but can be decompressed for DTO
 *   <li>TTL-based freshness is determined by the {@code updatedAt} timestamp
 *   <li>Large JSON blobs should be handled carefully in API responses
 * </ul>
 *
 * <p><b>Mapping:</b>
 *
 * <ul>
 *   <li>Entity: {@link CharacterEquipment}
 *   <li>Fields: Same as entity (OCID, JSON content, timestamps)
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * // Convert from entity
 * CharacterEquipment entity = repository.findByOcid("abc123").orElseThrow();
 * CharacterEquipmentDto dto = CharacterEquipmentDto.from(entity);
 *
 * // Check freshness
 * boolean isFresh = dto.isFresh(Duration.ofMinutes(15));
 *
 * // Convert to entity
 * CharacterEquipment entity = dto.toEntity();
 * }</pre>
 *
 * <p><b>Design Decisions:</b>
 *
 * <ul>
 *   <li>JSON content is stored as-is (decompression handled by entity)
 *   <li>Provides helper methods for freshness checking
 *   <li>Supports both compressed and decompressed content
 * </ul>
 *
 * @see CharacterEquipment
 */
public class CharacterEquipmentDto extends BaseDto {

  private String ocid;
  private String jsonContent;
  private LocalDateTime updatedAt;

  // ==================== Constructors ====================

  /** Default constructor (for frameworks) */
  public CharacterEquipmentDto() {}

  /** Full constructor */
  private CharacterEquipmentDto(String ocid, String jsonContent, LocalDateTime updatedAt) {
    this.ocid = ocid;
    this.jsonContent = jsonContent;
    this.updatedAt = updatedAt;
  }

  // ==================== Factory Methods ====================

  /**
   * Create DTO from domain entity
   *
   * <p>This method extracts all fields from the entity. The JSON content remains in its compressed
   * form (as stored in the entity).
   *
   * @param entity the domain entity (must not be null)
   * @return the DTO representation
   * @throws IllegalArgumentException if entity is null
   */
  public static CharacterEquipmentDto from(CharacterEquipment entity) {
    if (entity == null) {
      throw new IllegalArgumentException("Entity must not be null");
    }
    return new CharacterEquipmentDto(
        entity.getOcid(), entity.getJsonContent(), entity.getUpdatedAt());
  }

  /**
   * Create DTO for new equipment
   *
   * <p>This factory method is used when creating new equipment from API data.
   *
   * @param ocid the character OCID
   * @param jsonContent the equipment JSON (can be compressed or uncompressed)
   * @return the DTO for creation
   */
  public static CharacterEquipmentDto forCreation(String ocid, String jsonContent) {
    CharacterEquipmentDto dto = new CharacterEquipmentDto();
    dto.ocid = ocid;
    dto.jsonContent = jsonContent;
    dto.initTimestamps();
    return dto;
  }

  // ==================== Conversion Methods ====================

  /**
   * Convert DTO to domain entity
   *
   * <p>This method creates a new entity using the builder pattern.
   *
   * @return the domain entity
   */
  public CharacterEquipment toEntity() {
    return CharacterEquipment.builder().ocid(this.ocid).jsonContent(this.jsonContent).build();
  }

  // ==================== Getters/Setters ====================

  public String getOcid() {
    return ocid;
  }

  public void setOcid(String ocid) {
    this.ocid = ocid;
  }

  public String getJsonContent() {
    return jsonContent;
  }

  public void setJsonContent(String jsonContent) {
    this.jsonContent = jsonContent;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  // ==================== Utility Methods ====================

  /**
   * Check if equipment data exists
   *
   * @return true if JSON content is not null and not blank
   */
  public boolean hasData() {
    return this.jsonContent != null && !this.jsonContent.isBlank();
  }

  /**
   * Check if equipment data is fresh (not expired)
   *
   * <p>This method uses the same logic as {@link CharacterEquipment#isFresh(java.time.Duration)}.
   *
   * @param ttlMinutes the time-to-live in minutes (e.g., 15 for 15-minute cache)
   * @return true if data is fresh (updated within TTL), false otherwise
   */
  public boolean isFresh(long ttlMinutes) {
    if (this.updatedAt == null) {
      return false;
    }
    LocalDateTime threshold = LocalDateTime.now().minusMinutes(ttlMinutes);
    return this.updatedAt.isAfter(threshold);
  }

  /**
   * Check if equipment data is expired
   *
   * <p>This is the opposite of {@link #isFresh(long)}.
   *
   * @param ttlMinutes the time-to-live in minutes
   * @return true if data is expired (updated before TTL), false otherwise
   */
  public boolean isExpired(long ttlMinutes) {
    return !isFresh(ttlMinutes);
  }

  /**
   * Get the age of the equipment data in minutes
   *
   * @return the age in minutes, or null if updatedAt is not set
   */
  public Long getAgeInMinutes() {
    if (this.updatedAt == null) {
      return null;
    }
    return java.time.Duration.between(this.updatedAt, LocalDateTime.now()).toMinutes();
  }
}
