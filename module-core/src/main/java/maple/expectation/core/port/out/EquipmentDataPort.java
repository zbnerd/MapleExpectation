package maple.expectation.core.port.out;

import java.util.Optional;
import maple.expectation.core.domain.model.CharacterId;
import maple.expectation.domain.model.equipment.EquipmentData;

/**
 * Port for retrieving equipment data.
 *
 * <p>Implemented by module-infra adapters (database, cache, external API).
 *
 * <p>This interface abstracts the data source for equipment data, allowing core business logic to
 * remain independent of infrastructure.
 */
public interface EquipmentDataPort {

  /**
   * Find equipment data by character ID.
   *
   * @param characterId the unique character identifier
   * @return Optional containing the equipment data, or empty if not found
   */
  Optional<EquipmentData> findByCharacterId(CharacterId characterId);

  /**
   * Find equipment data by character OCID.
   *
   * @param ocid the character's OCID
   * @return Optional containing the equipment data, or empty if not found
   */
  Optional<EquipmentData> findByOcid(String ocid);

  /**
   * Save or update equipment data for a character.
   *
   * @param characterId the unique character identifier
   * @param equipmentData the equipment data to save
   */
  void save(CharacterId characterId, EquipmentData equipmentData);

  /**
   * Delete equipment data for a character.
   *
   * @param characterId the unique character identifier
   */
  void deleteByCharacterId(CharacterId characterId);
}
