package maple.expectation.application.service;

import java.time.Duration;
import java.util.Optional;
import maple.expectation.domain.model.equipment.CharacterEquipment;
import maple.expectation.domain.model.character.CharacterId;
import maple.expectation.domain.model.equipment.EquipmentData;
import maple.expectation.domain.repository.CharacterEquipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application Service for CharacterEquipment domain operations.
 *
 * <p>This service orchestrates business operations and manages transaction boundaries. It acts as
 * the use-case layer in Clean Architecture, coordinating between the domain layer and infrastructure.
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Transaction management (@Transactional)
 *   <li>Use-case orchestration (find, save, update operations)
 *   <li>Domain logic coordination
 * </ul>
 *
 * <h3>Design Principles</h3>
 *
 * <ul>
 *   <li><b>Thin Layer</b>: Delegates business logic to domain entities
 *   <li><b>Stateless</b>: No instance state, only dependencies
 *   <li><b>Transaction Boundary</b>: All write operations are transactional
 * </ul>
 */
@Service
@Transactional
public class EquipmentApplicationService {

  private final CharacterEquipmentRepository equipmentRepository;
  private static final Duration DEFAULT_TTL = Duration.ofHours(24);

  /**
   * Creates a new EquipmentApplicationService.
   *
   * @param equipmentRepository the repository port (must not be null)
   */
  public EquipmentApplicationService(CharacterEquipmentRepository equipmentRepository) {
    this.equipmentRepository = equipmentRepository;
  }

  /**
   * Finds equipment by character ID.
   *
   * <p>This is a read-only operation that doesn't require a transaction.
   *
   * @param characterId the character identifier
   * @return Optional containing the equipment if found
   */
  @Transactional(readOnly = true)
  public Optional<CharacterEquipment> findEquipment(CharacterId characterId) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    return equipmentRepository.findById(characterId);
  }

  /**
   * Finds fresh equipment (updated within TTL).
   *
   * <p>Returns equipment only if it was updated within the default TTL (24 hours).
   *
   * @param characterId the character identifier
   * @return Optional containing fresh equipment if found
   */
  @Transactional(readOnly = true)
  public Optional<CharacterEquipment> findFreshEquipment(CharacterId characterId) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    return equipmentRepository
        .findById(characterId)
        .filter(equipment -> equipment.isFresh(DEFAULT_TTL));
  }

  /**
   * Saves or updates equipment data.
   *
   * <p>This creates a new equipment record or updates an existing one within a transaction.
   *
   * @param characterId the character identifier
   * @param jsonData the equipment JSON content
   * @return the saved equipment
   */
  public CharacterEquipment saveEquipment(CharacterId characterId, String jsonData) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    if (jsonData == null || jsonData.isBlank()) {
      throw new IllegalArgumentException("JSON data cannot be null or blank");
    }

    EquipmentData equipmentData = EquipmentData.of(jsonData);

    // Check if equipment exists
    Optional<CharacterEquipment> existing = equipmentRepository.findById(characterId);

    if (existing.isPresent()) {
      // Update existing equipment
      CharacterEquipment updated = existing.get().updateData(equipmentData);
      return equipmentRepository.save(updated);
    } else {
      // Create new equipment
      CharacterEquipment newEquipment = CharacterEquipment.create(characterId, equipmentData);
      return equipmentRepository.save(newEquipment);
    }
  }

  /**
   * Updates equipment data.
   *
   * <p>Updates the equipment JSON content and timestamp.
   *
   * @param characterId the character identifier
   * @param jsonData the new equipment JSON content
   * @return the updated equipment
   * @throws IllegalArgumentException if equipment not found
   */
  public CharacterEquipment updateEquipment(CharacterId characterId, String jsonData) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    if (jsonData == null || jsonData.isBlank()) {
      throw new IllegalArgumentException("JSON data cannot be null or blank");
    }

    CharacterEquipment equipment =
        equipmentRepository
            .findById(characterId)
            .orElseThrow(() -> new IllegalArgumentException("Equipment not found: " + characterId));

    EquipmentData newData = EquipmentData.of(jsonData);
    CharacterEquipment updated = equipment.updateData(newData);
    return equipmentRepository.save(updated);
  }

  /**
   * Deletes equipment by character ID.
   *
   * @param characterId the character identifier
   */
  public void deleteEquipment(CharacterId characterId) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    equipmentRepository.deleteById(characterId);
  }

  /**
   * Checks if equipment exists.
   *
   * @param characterId the character identifier
   * @return true if equipment exists, false otherwise
   */
  @Transactional(readOnly = true)
  public boolean equipmentExists(CharacterId characterId) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    return equipmentRepository.existsById(characterId);
  }
}
