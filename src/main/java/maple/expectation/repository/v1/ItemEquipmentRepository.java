package maple.expectation.repository.v1;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import maple.expectation.domain.v1.ItemEquipment;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ItemEquipmentRepository {

    @PersistenceContext
    private EntityManager em;

    public Long save(ItemEquipment itemEquipment) {
        em.persist(itemEquipment);
        return itemEquipment.getId();
    }

    public List<ItemEquipment> saveAll(List<ItemEquipment> itemEquipments) {
        for (ItemEquipment itemEquipment : itemEquipments) {
            em.persist(itemEquipment);
        }
        return itemEquipments;
    }

    public ItemEquipment findById(Long id) {
        return em.find(ItemEquipment.class, id);
    }

    public List<ItemEquipment> findAll() {
        return em.createQuery("select i from ItemEquipment i", ItemEquipment.class)
                .getResultList();
    }

    public void update(ItemEquipment itemEquipment) {
        em.merge(itemEquipment);
    }

    public void delete(ItemEquipment itemEquipment) {
        em.remove(itemEquipment);
    }
}
