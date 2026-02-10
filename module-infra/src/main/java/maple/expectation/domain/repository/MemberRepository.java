package maple.expectation.domain.repository;

import java.util.Optional;
import maple.expectation.domain.v2.Member;

/**
 * Member Repository Interface (Port)
 *
 * <p><b>Purpose:</b> Defines the contract for member (user) data persistence operations following
 * the Ports and Adapters pattern. This interface belongs to the domain layer and contains no
 * infrastructure dependencies.
 *
 * <p><b>Key Concepts:</b>
 *
 * <ul>
 *   <li>Members are identified by a UUID (v4 format, 36 characters)
 *   <li>Optimistic locking is used via the {@code version} field to prevent lost updates
 *   <li>Point balance is a critical field that requires transactional consistency
 * </ul>
 *
 * <p><b>Contract:</b>
 *
 * <ul>
 *   <li>All methods return domain entities, not implementation details
 *   <li>Optional is used for single-result queries that may return nothing
 *   <li>Implementations must handle optimistic locking for concurrent updates
 *   <li>Point operations must be atomic and thread-safe
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * // Find member by UUID
 * Optional<Member> member = memberRepository.findByUuid("550e8400-e29b-41d4-a716-446655440000");
 *
 * // Create new guest member
 * Member guest = Member.createGuest(1000L);
 * memberRepository.save(guest);
 *
 * // Update member points (with optimistic locking)
 * Member member = memberRepository.findByUuid(uuid).orElseThrow();
 * member.deductPoints(100L); // Domain logic handles validation
 * memberRepository.save(member); // Throws OptimisticLockException if version mismatch
 * }</pre>
 *
 * <p><b>Implementation Notes:</b>
 *
 * <ul>
 *   <li>Implementations must use the {@code idx_uuid} unique index for efficient lookups
 *   <li>Implementations must handle optimistic locking via the {@code version} field
 *   <li>Implementations should use SELECT FOR UPDATE or similar for point operations
 *   <li>Implementations must ensure atomicity for point deduction operations
 * </ul>
 *
 * @see Member
 */
public interface MemberRepository {

  /**
   * Find a member by their UUID
   *
   * <p>UUID is the primary identifier for members and is guaranteed to be unique.
   *
   * @param uuid the member's UUID (must not be null, 36-character format)
   * @return Optional containing the member if found, empty otherwise
   * @throws IllegalArgumentException if uuid is null or blank
   */
  Optional<Member> findByUuid(String uuid);

  /**
   * Find a member by their database ID
   *
   * <p>This method is primarily used for internal operations and testing. Prefer {@link
   * #findByUuid(String)} for business logic.
   *
   * @param id the database ID (must not be null)
   * @return Optional containing the member if found, empty otherwise
   * @throws IllegalArgumentException if id is null
   */
  Optional<Member> findById(Long id);

  /**
   * Save a member (create or update)
   *
   * <p>If the member has a null ID, a new record is created. Otherwise, the existing record is
   * updated.
   *
   * <p><b>Optimistic Locking:</b> When updating an existing member, the {@code version} field is
   * checked. If the version in the database differs from the entity's version, an {@code
   * OptimisticLockException} is thrown.
   *
   * <p><b>Point Operations:</b> This method must be called within a transaction when modifying
   * points to ensure atomicity.
   *
   * @param member the member to save (must not be null)
   * @return the saved member with updated version
   * @throws IllegalArgumentException if member is null
   * @throws javax.persistence.OptimisticLockException if version conflict occurs during update
   */
  Member save(Member member);

  /**
   * Delete a member by UUID
   *
   * <p><b>Warning:</b> This is a destructive operation. Consider soft deletion for production use.
   * Point balances and related data should be handled before deletion.
   *
   * @param uuid the UUID of the member to delete (must not be null)
   * @throws IllegalArgumentException if uuid is null or blank
   */
  void deleteByUuid(String uuid);

  /**
   * Check if a member exists by UUID
   *
   * <p>This method is more efficient than {@link #findByUuid(String)} when you only need to check
   * existence without loading the entity.
   *
   * @param uuid the UUID to check (must not be null)
   * @return true if a member with the given UUID exists, false otherwise
   * @throws IllegalArgumentException if uuid is null or blank
   */
  boolean existsByUuid(String uuid);

  /**
   * Find or create a guest member
   *
   * <p>This is a convenience method that finds an existing member by UUID or creates a new guest
   * member if not found. Useful for auto-registration scenarios.
   *
   * <p><b>Note:</b> This method may have performance implications and should be used with caution
   * in high-traffic scenarios.
   *
   * @param uuid the UUID to search for
   * @param initialPoint the initial point balance for new guests
   * @return the existing or newly created member
   * @throws IllegalArgumentException if uuid is null or initialPoint is negative
   */
  Member findOrCreateGuest(String uuid, Long initialPoint);
}
