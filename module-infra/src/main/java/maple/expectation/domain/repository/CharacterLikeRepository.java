package maple.expectation.domain.repository;

import java.util.List;
import java.util.Optional;
import maple.expectation.domain.model.like.CharacterLike;

/**
 * CharacterLike Repository Interface (Port)
 *
 * <p><b>Purpose:</b> Defines the contract for character "like" (favorite) persistence operations
 * following the Ports and Adapters pattern. This interface belongs to the domain layer and contains
 * no infrastructure dependencies.
 *
 * <p><b>Key Concepts:</b>
 *
 * <ul>
 *   <li>Prevents duplicate likes via UNIQUE constraint on (target_ocid, liker_account_id)
 *   <li>Supports pagination for user's liked characters list
 *   <li>Tracks statistics (total likes per character, total likes by user)
 * </ul>
 *
 * <p><b>Contract:</b>
 *
 * <ul>
 *   <li>All methods return domain entities, not implementation details
 *   <li>Optional is used for single-result queries that may return nothing
 *   <li>Empty collections are returned for list queries (never null)
 *   <li>Self-like prevention is handled at the service layer, not repository
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * // Check if user already liked a character
 * Optional<CharacterLike> existing = likeRepository.findByTargetOcidAndLikerAccountId("char123", "user456");
 *
 * // Save new like
 * CharacterLike like = CharacterLike.of("char123", "user456");
 * likeRepository.save(like);
 *
 * // Get all likes by a user
 * List<CharacterLike> userLikes = likeRepository.findByLikerAccountId("user456");
 *
 * // Count total likes for a character
 * long totalLikes = likeRepository.countByTargetOcid("char123");
 *
 * // Unlike (delete)
 * likeRepository.delete(like);
 * }</pre>
 *
 * <p><b>Implementation Notes:</b>
 *
 * <ul>
 *   <li>Implementations must enforce UNIQUE constraint on (target_ocid, liker_account_id)
 *   <li>Implementations should use indexes: {@code idx_target_ocid}, {@code idx_liker_account_id}
 *   <li>Delete operations should be transactional
 * </ul>
 *
 * @see CharacterLike
 */
public interface CharacterLikeRepository {

  /**
   * Find a like record by target character and liker account
   *
   * <p>This method is used to check for duplicate likes before creating a new one.
   *
   * @param targetOcid the OCID of the character being liked (must not be null)
   * @param likerAccountId the account ID of the user who liked (must not be null)
   * @return Optional containing the like record if found, empty otherwise
   * @throws IllegalArgumentException if targetOcid or likerAccountId is null/blank
   */
  Optional<CharacterLike> findByTargetOcidAndLikerAccountId(
      String targetOcid, String likerAccountId);

  /**
   * Find all likes given by a specific account
   *
   * <p>This method returns all characters liked by a user, ordered by creation time (newest first).
   *
   * @param likerAccountId the account ID of the user (must not be null)
   * @return List of likes (empty list if none exist, never null)
   * @throws IllegalArgumentException if likerAccountId is null/blank
   */
  List<CharacterLike> findByLikerAccountId(String likerAccountId);

  /**
   * Find all likes received by a specific character
   *
   * <p>This method returns all users who liked a specific character.
   *
   * @param targetOcid the OCID of the character (must not be null)
   * @return List of likes (empty list if none exist, never null)
   * @throws IllegalArgumentException if targetOcid is null/blank
   */
  List<CharacterLike> findByTargetOcid(String targetOcid);

  /**
   * Save a like record (create or update)
   *
   * <p><b>Note:</b> Due to the UNIQUE constraint, attempting to save a duplicate like will result
   * in an exception. Check existence first with {@link #findByTargetOcidAndLikerAccountId(String,
   * String)}.
   *
   * @param like the like record to save (must not be null)
   * @return the saved like record with generated ID
   * @throws IllegalArgumentException if like is null
   * @throws org.springframework.dao.DataIntegrityViolationException if unique constraint is
   *     violated
   */
  CharacterLike save(CharacterLike like);

  /**
   * Delete a like record
   *
   * <p>This method is idempotent - deleting a non-existent like has no effect.
   *
   * @param like the like record to delete (must not be null)
   * @throws IllegalArgumentException if like is null
   */
  void delete(CharacterLike like);

  /**
   * Delete a like by target character and liker account
   *
   * <p>This is a convenience method that combines finding and deleting.
   *
   * @param targetOcid the OCID of the character (must not be null)
   * @param likerAccountId the account ID of the user (must not be null)
   * @throws IllegalArgumentException if targetOcid or likerAccountId is null/blank
   */
  void deleteByTargetOcidAndLikerAccountId(String targetOcid, String likerAccountId);

  /**
   * Count total likes received by a specific character
   *
   * <p>This method is optimized for counting and should be preferred over loading all entities and
   * calling {@link List#size()}.
   *
   * @param targetOcid the OCID of the character (must not be null)
   * @return the number of likes for the character
   * @throws IllegalArgumentException if targetOcid is null/blank
   */
  long countByTargetOcid(String targetOcid);

  /**
   * Count total likes given by a specific account
   *
   * <p>This method is optimized for counting and should be preferred over loading all entities and
   * calling {@link List#size()}.
   *
   * @param likerAccountId the account ID of the user (must not be null)
   * @return the number of likes given by the user
   * @throws IllegalArgumentException if likerAccountId is null/blank
   */
  long countByLikerAccountId(String likerAccountId);

  /**
   * Check if a like exists
   *
   * <p>This method is more efficient than {@link #findByTargetOcidAndLikerAccountId(String,
   * String)} when you only need to check existence without loading the entity.
   *
   * @param targetOcid the OCID of the character (must not be null)
   * @param likerAccountId the account ID of the user (must not be null)
   * @return true if the like exists, false otherwise
   * @throws IllegalArgumentException if targetOcid or likerAccountId is null/blank
   */
  boolean existsByTargetOcidAndLikerAccountId(String targetOcid, String likerAccountId);
}
