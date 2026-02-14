package maple.expectation.infrastructure.persistence.jpa;

import java.util.List;
import maple.expectation.infrastructure.persistence.entity.CharacterLikeJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA Repository for CharacterLike.
 *
 * <p>This is an INTERNAL repository interface used only by infrastructure layer. Domain layer uses
 * {@link maple.expectation.domain.repository.CharacterLikeRepository} instead.
 *
 * @see maple.expectation.infrastructure.persistence.repository.CharacterLikeRepositoryImpl
 */
public interface CharacterLikeJpaRepository extends JpaRepository<CharacterLikeJpaEntity, Long> {

  /**
   * Find like by target OCID and liker account ID.
   *
   * @param targetOcid OCID of character
   * @param likerAccountId account ID of user
   * @return JPA entity or empty
   */
  java.util.Optional<CharacterLikeJpaEntity> findByTargetOcidAndLikerAccountId(
      String targetOcid, String likerAccountId);

  /**
   * Find all likes by liker account ID, ordered by creation time (newest first).
   *
   * @param likerAccountId account ID of user
   * @return list of JPA entities
   */
  List<CharacterLikeJpaEntity> findByLikerAccountIdOrderByCreatedAtDesc(String likerAccountId);

  /**
   * Find all likes for target OCID, ordered by creation time (newest first).
   *
   * @param targetOcid OCID of character
   * @return list of JPA entities
   */
  List<CharacterLikeJpaEntity> findByTargetOcidOrderByCreatedAtDesc(String targetOcid);

  /**
   * Count likes by target OCID.
   *
   * @param targetOcid OCID of character
   * @return count of likes
   */
  long countByTargetOcid(String targetOcid);

  /**
   * Count likes by liker account ID.
   *
   * @param likerAccountId account ID of user
   * @return count of likes
   */
  long countByLikerAccountId(String likerAccountId);

  /**
   * Check if like exists by target OCID and liker account ID.
   *
   * @param targetOcid OCID of character
   * @param likerAccountId account ID of user
   * @return true if exists
   */
  boolean existsByTargetOcidAndLikerAccountId(String targetOcid, String likerAccountId);

  /**
   * Delete like by target OCID and liker account ID.
   *
   * @param targetOcid OCID of character
   * @param likerAccountId account ID of user
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  void deleteByTargetOcidAndLikerAccountId(String targetOcid, String likerAccountId);
}
