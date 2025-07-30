package maple.expectation.repository;

import maple.expectation.domain.GameCharacter;
import org.springframework.stereotype.Repository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Repository
public class GameCharacterRepository {
    @PersistenceContext
    private EntityManager em;

    public String save(GameCharacter character) {
        em.persist(character);
        return character.getUserIgn();
    }



    public GameCharacter findByUserIgn(String userIgn) {
        return em.createQuery("SELECT c FROM GameCharacter c WHERE c.userIgn = :userIgn", GameCharacter.class)
                .setParameter("userIgn", userIgn)
                .getResultList()
                .stream()
                .findFirst()
                .orElse(null);
    }

    public void delete(GameCharacter character) {
        em.remove(character);
    }

    public void update(GameCharacter character) {
        em.merge(character);
    }
}
