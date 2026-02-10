package maple.expectation.domain.repository;

import java.util.List;
import java.util.Optional;
import maple.expectation.domain.v2.GameCharacter;

/**
 * GameCharacter Repository Interface (Port)
 *
 * <p><b>Purpose:</b> Defines the contract for character data persistence operations following the
 * Ports and Adapters pattern. This interface belongs to the domain layer and contains no
 * infrastructure dependencies.
 *
 * <p><b>Contract:</b>
 *
 * <ul>
 *   <li>All methods return domain entities, not implementation details
 *   <li>Optional is used for single-result queries that may return nothing
 *   <li>Empty collections are returned for list queries (never null)
 *   <li>Exceptions are unchecked and domain-specific
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * // Find character by OCID
 * Optional<GameCharacter> character = gameCharacterRepository.findByOcid("abc123");
 *
 * // Save new character
 * GameCharacter newCharacter = new GameCharacter("Player1", "xyz789");
 * GameCharacter saved = gameCharacterRepository.save(newCharacter);
 *
 * // Find all active characters
 * List<GameCharacter> all = gameCharacterRepository.findAll();
 * }</pre>
 *
 * <p><b>Implementation Notes:</b>
 *
 * <ul>
 *   <li>Implementations must handle transaction management
 *   <li>Implementations should handle optimistic locking conflicts
 *   <li>Implementations must ensure thread-safety for concurrent operations
 * </ul>
 *
 * @see GameCharacter
 */
public interface GameCharacterRepository {

  /**
   * Find a character by their OCID (Origin Character ID)
   *
   * <p>OCID is a unique identifier assigned by Nexon's API and does not change over time.
   *
   * @param ocid the character's OCID (must not be null)
   * @return Optional containing the character if found, empty otherwise
   * @throws IllegalArgumentException if ocid is null or blank
   */
  Optional<GameCharacter> findByOcid(String ocid);

  /**
   * Find a character by their in-game name (IGN)
   *
   * <p>IGN can change over time, so this method returns the first match. Use with caution.
   *
   * @param userIgn the character's in-game name (must not be null)
   * @return Optional containing the character if found, empty otherwise
   * @throws IllegalArgumentException if userIgn is null or blank
   */
  Optional<GameCharacter> findByUserIgn(String userIgn);

  /**
   * Retrieve all characters from the database
   *
   * <p><b>Warning:</b> This method may return a large dataset. Use pagination or filtering for
   * production queries.
   *
   * @return List of all characters (empty list if none exist, never null)
   */
  List<GameCharacter> findAll();

  /**
   * Find all active characters (updated within the last 30 days)
   *
   * <p>Active characters are defined by {@link GameCharacter#isActive()} - characters updated
   * within 30 days are considered active.
   *
   * @return List of active characters (empty list if none exist, never null)
   */
  List<GameCharacter> findActiveCharacters();

  /**
   * Save a character (create or update)
   *
   * <p>If the character has a null ID, a new record is created. Otherwise, the existing record is
   * updated. This operation must handle optimistic locking via the version field.
   *
   * @param character the character to save (must not be null)
   * @return the saved character with generated ID and updated timestamps
   * @throws IllegalArgumentException if character is null
   * @throws javax.persistence.OptimisticLockException if version conflict occurs
   */
  GameCharacter save(GameCharacter character);

  /**
   * Delete a character by OCID
   *
   * <p><b>Warning:</b> This is a destructive operation. Consider soft deletion for production use.
   *
   * @param ocid the OCID of the character to delete (must not be null)
   * @throws IllegalArgumentException if ocid is null or blank
   */
  void deleteByOcid(String ocid);

  /**
   * Check if a character exists by OCID
   *
   * <p>This method is more efficient than {@link #findByOcid(String)} when you only need to check
   * existence without loading the entity.
   *
   * @param ocid the OCID to check (must not be null)
   * @return true if a character with the given OCID exists, false otherwise
   * @throws IllegalArgumentException if ocid is null or blank
   */
  boolean existsByOcid(String ocid);
}
