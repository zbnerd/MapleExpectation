package maple.expectation.application.dto

import java.time.LocalDateTime

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
 *   <li>Immutable where possible (use data classes for simple DTOs)
 *   <li>Clear separation from domain entities (no business logic)
 *   <li>Supports bidirectional mapping (entity ↔ DTO)
 *   <li>Includes audit fields for tracking
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * data class GameCharacterDto(
 *     override val createdAt: LocalDateTime? = null,
 *     override val updatedAt: LocalDateTime? = null,
 *     override val version: Long? = null,
 *     val userIgn: String,
 *     val ocid: String
 * ) : BaseDto()
 * }</pre>
 *
 * <p><b>Implementation Notes:</b>
 *
 * <ul>
 *   <li>Use data classes for immutability where possible
 *   <li>Implement validation logic in factory methods or companion objects
 *   <li>Consider using Kotlin serialization for entity-DTO mapping
 * </ul>
 *
 * @see LocalDateTime
 */
abstract class BaseDto {
    /** Timestamp when the record was created (immutable) */
    open val createdAt: LocalDateTime? = null

    /** Timestamp when the record was last updated */
    open val updatedAt: LocalDateTime? = null

    /** Version field for optimistic locking (optional) */
    open val version: Long? = null

    /**
     * Check if this DTO is newly created (no ID or version)
     *
     * <p>This is useful for determining whether to perform an insert or update operation.
     *
     * @return true if this DTO represents a new record (version is null), false otherwise
     */
    open fun isNew(): Boolean = version == null

    /**
     * Mark this DTO as updated
     *
     * <p>This sets the {@code updatedAt} field to the current time.
     */
    open fun markAsUpdated() {
        // This would be implemented as a copy method in data classes
        // For abstract class, this is just a documentation placeholder
    }

    /**
     * Initialize timestamps for a new record
     *
     * <p>This sets both {@code createdAt} and {@code updatedAt} to the current time.
     */
    open fun initTimestamps() {
        // This would be implemented as a copy method in data classes
        // For abstract class, this is just a documentation placeholder
    }
}