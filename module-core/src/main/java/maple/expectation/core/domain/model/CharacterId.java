package maple.expectation.core.domain.model;

/**
 * Character ID domain model.
 *
 * <p>Represents a unique character identifier.
 *
 * <p>Pure domain model - no external dependencies.
 *
 * @param value the OCID (unique character identifier)
 */
public record CharacterId(String value) {
  /** Validates the character ID. */
  public CharacterId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Character ID cannot be null or blank");
    }
  }

  /** Create a character ID. */
  public static CharacterId of(String ocid) {
    return new CharacterId(ocid);
  }

  /**
   * Check if the character ID is valid.
   *
   * @return true if valid, false otherwise
   */
  public boolean isValid() {
    return value != null && !value.isBlank();
  }
}
