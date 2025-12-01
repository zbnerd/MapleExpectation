package maple.expectation.repository;

import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import maple.expectation.domain.GameCharacter;
import maple.expectation.exception.CharacterNotFoundException;
import org.springframework.stereotype.Repository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.Optional;

@Repository
public class GameCharacterRepository {
    @PersistenceContext
    private EntityManager em;

    @Transactional
    public String save(GameCharacter character) {
        em.persist(character);
        return character.getUserIgn();
    }

    public GameCharacter findByUserIgn(String userIgn) {
        return findOptionalByUserIgn(userIgn)
                .orElseThrow(() -> new CharacterNotFoundException("캐릭터 없음"));
    }

    public Optional<GameCharacter> findOptionalByUserIgn(String userIgn) {
        return em.createQuery("SELECT c FROM GameCharacter c WHERE c.userIgn = :userIgn", GameCharacter.class)
                .setParameter("userIgn", userIgn)
                .getResultList()
                .stream()
                .findFirst();
    }

    public GameCharacter findByUserIgnWithPessimisticLock(String userIgn) {
        return em.createQuery(
                        "SELECT c FROM GameCharacter c WHERE c.userIgn = :userIgn", GameCharacter.class)
                .setParameter("userIgn", userIgn)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE) // 🔒 비관적 락 적용
                .getResultList()
                .stream()
                .findFirst()
                .orElseThrow(() -> new CharacterNotFoundException("캐릭터 없음"));
    }

    @Transactional
    public void delete(GameCharacter character) {
        GameCharacter managed = em.contains(character) ? character : em.merge(character);
        em.remove(managed);
    }

    @Transactional
    public void deleteById(Long id) {
        GameCharacter managed = em.find(GameCharacter.class, id);
        if (managed != null) {
            em.remove(managed);
        }
    }

    public void update(GameCharacter character) {
        em.merge(character);
    }
}
