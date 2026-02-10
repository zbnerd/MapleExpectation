package maple.expectation.domain.model.character;

/**
 * Value Object representing a unique character identifier.
 *
 * <p>This Value Object is immutable and validates that the OCID is not blank. Used throughout the
 * domain layer to ensure type safety.
 *
 * @param value the OCID (Original Character ID) string
 * @throws IllegalArgumentException if value is blank
 */
public record CharacterId(String value) {

  /** Compact constructor that validates the OCID. */
  public CharacterId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CharacterId cannot be null or blank");
    }
  }

  /**
   * Factory method to create CharacterId.
   *
   * @param value the OCID string
   * @return validated CharacterId
   */
  public static CharacterId of(String value) {
    return new CharacterId(value);
  }
}
