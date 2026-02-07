package maple.expectation.domain.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import maple.expectation.domain.v2.CharacterEquipment;

/**
 * CharacterEquipment Repository Interface (Port)
 *
 * <p><b>Purpose:</b> Defines the contract for equipment data persistence operations following the
 * Ports and Adapters pattern. This interface belongs to the domain layer and contains no
 * infrastructure dependencies.
 *
 * <p><b>Key Concepts:</b>
 *
 * <ul>
 *   <li>Equipment data is cached with a TTL (Time-To-Live) of 15 minutes
 *   <li>Data freshness is determined by the {@code updatedAt} timestamp
 *   <li>JSON content is GZIP-compressed for storage efficiency
 * </ul>
 *
 * <p><b>Contract:</b>
 *
 * <ul>
 *   <li>All methods return domain entities, not implementation details
 *   <li>Optional is used for single-result queries that may return nothing
 *   <li>Implementations must handle compression/decompression transparently
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * // Find equipment by OCID
 * Optional<CharacterEquipment> equipment = equipmentRepository.findByOcid("abc123");
 *
 * // Find fresh equipment (updated within last 15 minutes)
 * LocalDateTime threshold = LocalDateTime.now().minusMinutes(15);
 * Optional<CharacterEquipment> fresh = equipmentRepository.findByOcidAndUpdatedAtAfter("abc123", threshold);
 *
 * // Save or update equipment
 * CharacterEquipment newEquipment = CharacterEquipment.builder()
 *     .ocid("abc123")
 *     .jsonContent("{...}")
 *     .build();
 * equipmentRepository.save(newEquipment);
 * }</pre>
 *
 * <p><b>Implementation Notes:</b>
 *
 * <ul>
 *   <li>Implementations must handle GZIP compression/decompression of JSON content
 *   <li>Implementations should use the {@code idx_character_equipment_updated_at} index for
 *       time-based queries
 *   <li>Implementations must ensure thread-safety for concurrent updates
 * </ul>
 *
 * @see CharacterEquipment
 */
public interface CharacterEquipmentRepository {

  /**
   * Find equipment by character OCID
   *
   * <p>This method returns the equipment regardless of data freshness. Use {@link
   * #findByOcidAndUpdatedAtAfter(String, LocalDateTime)} to filter by freshness.
   *
   * @param ocid the character's OCID (must not be null)
   * @return Optional containing the equipment if found, empty otherwise
   * @throws IllegalArgumentException if ocid is null or blank
   */
  Optional<CharacterEquipment> findByOcid(String ocid);

  /**
   * Find equipment that was updated after a specific threshold
   *
   * <p>This method is used to implement TTL-based caching. Only equipment updated after the
   * threshold is returned, ensuring data freshness.
   *
   * <p><b>Typical Usage:</b>
   *
   * <pre>{@code
   * LocalDateTime threshold = LocalDateTime.now().minusMinutes(15);
   * Optional<CharacterEquipment> fresh = equipmentRepository.findByOcidAndUpdatedAtAfter(ocid, threshold);
   * if (fresh.isPresent()) {
   *     // Use cached data
   * } else {
   *     // Fetch from API
   * }
   * }</pre>
   *
   * @param ocid the character's OCID (must not be null)
   * @param threshold the minimum update time (must not be null)
   * @return Optional containing fresh equipment if found, empty otherwise
   * @throws IllegalArgumentException if ocid or threshold is null
   */
  Optional<CharacterEquipment> findByOcidAndUpdatedAtAfter(String ocid, LocalDateTime threshold);

  /**
   * Save or update equipment
   *
   * <p>If equipment with the same OCID exists, it is updated. Otherwise, a new record is created.
   * The {@code updatedAt} timestamp is automatically set to the current time.
   *
   * @param equipment the equipment to save (must not be null)
   * @return the saved equipment with updated timestamps
   * @throws IllegalArgumentException if equipment is null
   */
  CharacterEquipment save(CharacterEquipment equipment);

  /**
   * Delete equipment by OCID
   *
   * <p><b>Warning:</b> This is a destructive operation. Consider whether this is appropriate for
   * your use case.
   *
   * @param ocid the OCID of the equipment to delete (must not be null)
   * @throws IllegalArgumentException if ocid is null or blank
   */
  void deleteByOcid(String ocid);

  /**
   * Check if equipment exists and is fresh (updated within threshold)
   *
   * <p>This is a convenience method that combines existence and freshness checks.
   *
   * @param ocid the character's OCID (must not be null)
   * @param threshold the minimum update time (must not be null)
   * @return true if fresh equipment exists, false otherwise
   * @throws IllegalArgumentException if ocid or threshold is null
   */
  boolean existsByOcidAndUpdatedAtAfter(String ocid, LocalDateTime threshold);
}
