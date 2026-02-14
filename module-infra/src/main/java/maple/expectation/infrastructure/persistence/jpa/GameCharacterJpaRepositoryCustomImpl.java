package maple.expectation.infrastructure.persistence.jpa;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import maple.expectation.infrastructure.persistence.entity.GameCharacterJpaEntity;
import org.springframework.stereotype.Component;

/** Implementation of custom JPA repository methods for GameCharacter. */
@Component
public class GameCharacterJpaRepositoryCustomImpl implements GameCharacterJpaRepositoryCustom {

  private final EntityManager entityManager;

  public GameCharacterJpaRepositoryCustomImpl(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public List<GameCharacterJpaEntity> findActiveCharacters() {
    LocalDateTime threshold = LocalDateTime.now().minusDays(30);
    return entityManager
        .createQuery(
            """
                SELECT gc FROM GameCharacterJpaEntity gc
                WHERE gc.updatedAt > :threshold
                ORDER BY gc.updatedAt DESC
                """,
            GameCharacterJpaEntity.class)
        .setParameter("threshold", threshold)
        .getResultList();
  }
}
