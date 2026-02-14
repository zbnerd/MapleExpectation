package maple.expectation.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;
import maple.expectation.domain.model.character.GameCharacter;
import maple.expectation.domain.repository.GameCharacterRepository;
import maple.expectation.infrastructure.persistence.entity.GameCharacterJpaEntity;
import maple.expectation.infrastructure.persistence.jpa.GameCharacterJpaRepository;
import maple.expectation.infrastructure.persistence.jpa.GameCharacterJpaRepositoryCustom;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of GameCharacter Repository.
 *
 * <p>This class maps between JPA entities and domain models, ensuring the domain layer remains pure
 * and free of infrastructure concerns.
 *
 * <p><b>Transactional:</b> All write operations are transactional by default.
 */
@Repository
@Transactional
public class GameCharacterRepositoryImpl implements GameCharacterRepository {

  private final GameCharacterJpaRepository jpaRepo;
  private final GameCharacterJpaRepositoryCustom jpaCustomRepo;

  /**
   * Creates a new GameCharacterRepositoryImpl.
   *
   * @param jpaRepo Spring Data JPA repository
   * @param jpaCustomRepo custom JPA repository with complex queries
   */
  public GameCharacterRepositoryImpl(
      GameCharacterJpaRepository jpaRepo, GameCharacterJpaRepositoryCustom jpaCustomRepo) {
    this.jpaRepo = jpaRepo;
    this.jpaCustomRepo = jpaCustomRepo;
  }

  @Override
  public Optional<GameCharacter> findByOcid(String ocid) {
    return jpaRepo.findByOcid(ocid).map(GameCharacterJpaEntity::toDomain);
  }

  @Override
  public Optional<GameCharacter> findByUserIgn(String userIgn) {
    return jpaRepo.findByUserIgn(userIgn).map(GameCharacterJpaEntity::toDomain);
  }

  @Override
  public List<GameCharacter> findAll() {
    return jpaRepo.findAll().stream().map(GameCharacterJpaEntity::toDomain).toList();
  }

  @Override
  public List<GameCharacter> findActiveCharacters() {
    return jpaCustomRepo.findActiveCharacters().stream()
        .map(GameCharacterJpaEntity::toDomain)
        .toList();
  }

  @Override
  public GameCharacter save(GameCharacter character) {
    if (character == null) {
      throw new IllegalArgumentException("Character cannot be null");
    }

    GameCharacterJpaEntity jpaEntity = GameCharacterJpaEntity.fromDomain(character);
    GameCharacterJpaEntity saved = jpaRepo.save(jpaEntity);
    return saved.toDomain();
  }

  @Override
  public void deleteByOcid(String ocid) {
    jpaRepo.deleteByOcid(ocid);
  }

  @Override
  public boolean existsByOcid(String ocid) {
    return jpaRepo.existsByOcid(ocid);
  }
}
