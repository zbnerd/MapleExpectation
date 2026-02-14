package maple.expectation.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;
import maple.expectation.domain.model.like.CharacterLike;
import maple.expectation.domain.repository.CharacterLikeRepository;
import maple.expectation.infrastructure.persistence.entity.CharacterLikeJpaEntity;
import maple.expectation.infrastructure.persistence.jpa.CharacterLikeJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of CharacterLike Repository.
 *
 * <p>This class maps between JPA entities and domain models, ensuring domain layer remains pure and
 * free of infrastructure concerns.
 *
 * <p><b>Transactional:</b> All write operations are transactional by default.
 */
@Repository
@Transactional
public class CharacterLikeRepositoryImpl implements CharacterLikeRepository {

  private final CharacterLikeJpaRepository jpaRepo;

  /**
   * Creates a new CharacterLikeRepositoryImpl.
   *
   * @param jpaRepo Spring Data JPA repository
   */
  public CharacterLikeRepositoryImpl(CharacterLikeJpaRepository jpaRepo) {
    this.jpaRepo = jpaRepo;
  }

  @Override
  public Optional<CharacterLike> findByTargetOcidAndLikerAccountId(
      String targetOcid, String likerAccountId) {
    return jpaRepo
        .findByTargetOcidAndLikerAccountId(targetOcid, likerAccountId)
        .map(CharacterLikeJpaEntity::toDomain);
  }

  @Override
  public List<CharacterLike> findByLikerAccountId(String likerAccountId) {
    return jpaRepo.findByLikerAccountIdOrderByCreatedAtDesc(likerAccountId).stream()
        .map(CharacterLikeJpaEntity::toDomain)
        .toList();
  }

  @Override
  public List<CharacterLike> findByTargetOcid(String targetOcid) {
    return jpaRepo.findByTargetOcidOrderByCreatedAtDesc(targetOcid).stream()
        .map(CharacterLikeJpaEntity::toDomain)
        .toList();
  }

  @Override
  public CharacterLike save(CharacterLike like) {
    if (like == null) {
      throw new IllegalArgumentException("Like cannot be null");
    }

    CharacterLikeJpaEntity jpaEntity = CharacterLikeJpaEntity.fromDomain(like);
    CharacterLikeJpaEntity saved = jpaRepo.save(jpaEntity);
    return saved.toDomain();
  }

  @Override
  public void delete(CharacterLike like) {
    if (like == null) {
      throw new IllegalArgumentException("Like cannot be null");
    }

    CharacterLikeJpaEntity jpaEntity = CharacterLikeJpaEntity.fromDomain(like);
    jpaRepo.delete(jpaEntity);
  }

  @Override
  public void deleteByTargetOcidAndLikerAccountId(String targetOcid, String likerAccountId) {
    jpaRepo.deleteByTargetOcidAndLikerAccountId(targetOcid, likerAccountId);
  }

  @Override
  public long countByTargetOcid(String targetOcid) {
    return jpaRepo.countByTargetOcid(targetOcid);
  }

  @Override
  public long countByLikerAccountId(String likerAccountId) {
    return jpaRepo.countByLikerAccountId(likerAccountId);
  }

  @Override
  public boolean existsByTargetOcidAndLikerAccountId(String targetOcid, String likerAccountId) {
    return jpaRepo.existsByTargetOcidAndLikerAccountId(targetOcid, likerAccountId);
  }
}
