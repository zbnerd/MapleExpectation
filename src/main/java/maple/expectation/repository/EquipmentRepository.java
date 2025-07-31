package maple.expectation.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import maple.expectation.domain.Equipment;
import org.springframework.stereotype.Repository;

@Repository
public class EquipmentRepository {

    @PersistenceContext
    private EntityManager em;

    public Long save(Equipment equipment) {
        em.persist(equipment);
        return equipment.getEquipmentId();
    }

    public Equipment findById(Long id) {
        return em.find(Equipment.class, id);
    }

    public void delete(Equipment equipment) {
        em.remove(equipment);
    }

    public void update(Equipment equipment) {
        em.merge(equipment);
    }

}
