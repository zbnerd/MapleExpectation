package maple.expectation.domain.model.equipment;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import maple.expectation.domain.model.character.CharacterId;

/**
 * Rich Domain Model for Character Equipment.
 *
 * <p>This is a PURE domain entity with NO JPA annotations, NO Spring dependencies, and NO
 * infrastructure concerns. It encapsulates business logic related to equipment data freshness and
 * expiration.
 *
 * <h3>Design Principles</h3>
 *
 * <ul>
 *   <li><b>Immutability</b>: State cannot be changed after creation. Use {@link
 *       #updateData(EquipmentData)} for state transitions.
 *   <li><b>Factory Methods</b>: {@link #create()} for new entities, {@link #restore()} for
 *       reconstitution from persistence.
 *   <li><b>Business Logic</b>: Encapsulates expiration, freshness, and data validation.
 *   <li><b>Clean Architecture</b>: Zero dependencies on infrastructure layer.
 * </ul>
 *
 * <h3>Business Rules</h3>
 *
 * <ul>
 *   <li>Data is considered expired if updated timestamp is older than TTL
 *   <li>Data must not be blank to be considered valid
 *   <li>State transitions create new instances (immutability pattern)
 * </ul>
 *
 * @see CharacterId
 * @see EquipmentData
 */
public class CharacterEquipment {

  private final CharacterId characterId;
  private final EquipmentData equipmentData;
  private final LocalDateTime updatedAt;

  /**
   * Private constructor enforcing factory method usage.
   *
   * @param id the character identifier (must not be null)
   * @param data the equipment data (must not be null)
   * @param updated the last update timestamp (defaults to now if null)
   * @throws NullPointerException if id or data is null
   */
  private CharacterEquipment(CharacterId id, EquipmentData data, LocalDateTime updated) {
    this.characterId = Objects.requireNonNull(id, "CharacterId cannot be null");
    this.equipmentData = Objects.requireNonNull(data, "EquipmentData cannot be null");
    this.updatedAt = updated != null ? updated : LocalDateTime.now();
  }

  /**
   * Factory method to create a new CharacterEquipment entity.
   *
   * <p>Use this for NEW entities being created for the first time. The updatedAt timestamp is
   * automatically set to current time.
   *
   * @param id the character identifier
   * @param data the equipment data
   * @return new CharacterEquipment instance
   */
  public static CharacterEquipment create(CharacterId id, EquipmentData data) {
    return new CharacterEquipment(id, data, LocalDateTime.now());
  }

  /**
   * Factory method to restore CharacterEquipment from persistence.
   *
   * <p>Use this when reconstituting entities from database or cache. Preserves the original
   * updatedAt timestamp.
   *
   * @param id the character identifier
   * @param data the equipment data
   * @param updatedAt the original update timestamp
   * @return restored CharacterEquipment instance
   */
  public static CharacterEquipment restore(
      CharacterId id, EquipmentData data, LocalDateTime updatedAt) {
    return new CharacterEquipment(id, data, updatedAt);
  }

  // ==================== Business Methods ====================

  /**
   * Checks if the equipment data has expired based on TTL.
   *
   * <p>Data is considered expired if it was updated longer ago than the specified TTL.
   *
   * @param ttl Time-To-Live duration (must not be null)
   * @return true if data is expired, false if still fresh
   * @throws NullPointerException if ttl is null
   */
  public boolean isExpired(Duration ttl) {
    Objects.requireNonNull(ttl, "TTL cannot be null");
    return updatedAt.isBefore(LocalDateTime.now().minus(ttl));
  }

  /**
   * Checks if the equipment data is still fresh (not expired).
   *
   * <p>This is the negation of {@link #isExpired(Duration)}.
   *
   * @param ttl Time-To-Live duration (must not be null)
   * @return true if data is fresh, false if expired
   */
  public boolean isFresh(Duration ttl) {
    return !isExpired(ttl);
  }

  /**
   * Checks if valid equipment data exists.
   *
   * <p>Validates that the underlying JSON content is well-formed.
   *
   * @return true if data exists and is valid JSON
   */
  public boolean hasData() {
    return equipmentData != null && equipmentData.isValidJson();
  }

  /**
   * Creates a new instance with updated equipment data.
   *
   * <p>This follows the immutability pattern - instead of modifying state, a new instance is
   * created with the updated data and current timestamp.
   *
   * @param newData the new equipment data
   * @return new CharacterEquipment instance with updated data
   */
  public CharacterEquipment updateData(EquipmentData newData) {
    return new CharacterEquipment(this.characterId, newData, LocalDateTime.now());
  }

  // ==================== Getters ====================

  /**
   * Returns the character identifier.
   *
   * @return the CharacterId
   */
  public CharacterId getCharacterId() {
    return characterId;
  }

  /**
   * Returns the equipment data.
   *
   * @return the EquipmentData
   */
  public EquipmentData getEquipmentData() {
    return equipmentData;
  }

  /**
   * Returns the last update timestamp.
   *
   * @return the LocalDateTime when this data was last updated
   */
  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  // ==================== Object Methods ====================

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    CharacterEquipment that = (CharacterEquipment) o;
    return Objects.equals(characterId, that.characterId)
        && Objects.equals(equipmentData, that.equipmentData)
        && Objects.equals(updatedAt, that.updatedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(characterId, equipmentData, updatedAt);
  }

  @Override
  public String toString() {
    return "CharacterEquipment{"
        + "characterId="
        + characterId
        + ", updatedAt="
        + updatedAt
        + ", hasData="
        + hasData()
        + '}';
  }
}
