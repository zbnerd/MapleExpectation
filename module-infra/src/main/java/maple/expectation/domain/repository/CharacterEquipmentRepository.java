package maple.expectation.domain.repository;

import java.util.Optional;
import maple.expectation.domain.model.character.CharacterId;
import maple.expectation.domain.model.equipment.CharacterEquipment;

/**
 * Repository Port for CharacterEquipment domain entity.
 *
 * <p>This is a <b>Port</b> interface in the Hexagonal Architecture pattern. It defines the contract
 * for persisting and retrieving {@link CharacterEquipment} domain entities without exposing any
 * infrastructure details.
 *
 * <h3>Design Principles</h3>
 *
 * <ul>
 *   <li><b>Interface Segregation</b>: Only domain-related methods are exposed
 *   <li><b>Dependency Inversion</b>: Domain layer depends on this interface, not implementation
 *   <li><b>Infrastructure Agnostic</b>: No JPA, SQL, or database-specific types
 *   <li><b>Domain-First</b>: Uses domain types ({@link CharacterId}, {@link CharacterEquipment})
 * </ul>
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * // In Application Service or Domain Service
 * public class EquipmentApplicationService {
 *     private final CharacterEquipmentRepository repository;
 *
 *     public CharacterEquipment getEquipment(CharacterId id) {
 *         return repository.findById(id)
 *             .orElseThrow(() -> new EquipmentNotFoundException(id));
 *     }
 * }
 * }</pre>
 *
 * @see CharacterEquipment
 * @see CharacterId
 */
public interface CharacterEquipmentRepository {

  /**
   * Finds equipment by character ID.
   *
   * @param characterId the character identifier (must not be null)
   * @return Optional containing the equipment if found, empty otherwise
   * @throws IllegalArgumentException if characterId is null
   */
  Optional<CharacterEquipment> findById(CharacterId characterId);

  /**
   * Saves equipment (insert or update).
   *
   * <p>This method handles both new entities and updates to existing entities.
   *
   * @param equipment the equipment to save (must not be null)
   * @return the saved equipment (possibly with generated fields)
   * @throws IllegalArgumentException if equipment is null
   */
  CharacterEquipment save(CharacterEquipment equipment);

  /**
   * Deletes equipment by character ID.
   *
   * @param characterId the character identifier (must not be null)
   * @throws IllegalArgumentException if characterId is null
   */
  void deleteById(CharacterId characterId);

  /**
   * Checks if equipment exists for the given character ID.
   *
   * @param characterId the character identifier (must not be null)
   * @return true if equipment exists, false otherwise
   * @throws IllegalArgumentException if characterId is null
   */
  boolean existsById(CharacterId characterId);
}
