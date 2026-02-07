package maple.expectation.application.dto;

import java.time.LocalDateTime;

/**
 * Base DTO class for all application data transfer objects
 *
 * <p><b>Purpose:</b> Provides common fields and functionality for all DTOs in the application
 * layer. This class follows the Data Transfer Object pattern to encapsulate data transfer between
 * layers.
 *
 * <p><b>Design Principles:</b>
 *
 * <ul>
 *   <li>Immutable where possible (use records for simple DTOs)
 *   <li>Clear separation from domain entities (no business logic)
 *   <li>Supports bidirectional mapping (entity ↔ DTO)
 *   <li>Includes audit fields for tracking
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * public class GameCharacterDto extends BaseDto {
 *     private String userIgn;
 *     private String ocid;
 *     // getters/setters
 * }
 * }</pre>
 *
 * <p><b>Implementation Notes:</b>
 *
 * <ul>
 *   <li>Subclasses should use Java Records for immutability when possible (Java 17+)
 *   <li>Subclasses should implement validation logic in setters or factory methods
 *   <li>Consider using MapStruct for entity-DTO mapping
 * </ul>
 *
 * @see LocalDateTime
 */
public abstract class BaseDto {

  /** Timestamp when the record was created (immutable) */
  protected LocalDateTime createdAt;

  /** Timestamp when the record was last updated */
  protected LocalDateTime updatedAt;

  /** Version field for optimistic locking (optional) */
  protected Long version;

  // ==================== Getters ====================

  /**
   * Get the creation timestamp
   *
   * @return the timestamp when this record was created, or null if not set
   */
  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  /**
   * Get the last update timestamp
   *
   * @return the timestamp when this record was last updated, or null if never updated
   */
  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  /**
   * Get the version for optimistic locking
   *
   * @return the version number, or null if not set
   */
  public Long getVersion() {
    return version;
  }

  // ==================== Setters ====================

  /**
   * Set the creation timestamp
   *
   * <p><b>Note:</b> This should typically be set only once during creation.
   *
   * @param createdAt the creation timestamp
   */
  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  /**
   * Set the last update timestamp
   *
   * <p><b>Note:</b> This should be updated whenever the entity changes.
   *
   * @param updatedAt the last update timestamp
   */
  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  /**
   * Set the version for optimistic locking
   *
   * @param version the version number
   */
  public void setVersion(Long version) {
    this.version = version;
  }

  // ==================== Utility Methods ====================

  /**
   * Check if this DTO is newly created (no ID or version)
   *
   * <p>This is useful for determining whether to perform an insert or update operation.
   *
   * @return true if this DTO represents a new record (version is null), false otherwise
   */
  public boolean isNew() {
    return this.version == null;
  }

  /**
   * Mark this DTO as updated
   *
   * <p>This sets the {@code updatedAt} field to the current time.
   */
  public void markAsUpdated() {
    this.updatedAt = LocalDateTime.now();
  }

  /**
   * Initialize timestamps for a new record
   *
   * <p>This sets both {@code createdAt} and {@code updatedAt} to the current time.
   */
  public void initTimestamps() {
    LocalDateTime now = LocalDateTime.now();
    this.createdAt = now;
    this.updatedAt = now;
  }
}
