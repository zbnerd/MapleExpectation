package maple.expectation.core.domain.model;

/**
 * Cube probability rate domain model.
 *
 * <p>Represents the probability rate for a specific option appearing on a cube.
 *
 * <p>Pure domain model - no external dependencies.
 *
 * @param cubeType the type of cube (BLACK, RED, ADDITIONAL)
 * @param optionName the name of the potential option
 * @param rate the probability rate (0.0 to 1.0)
 * @param slot the slot number (1, 2, or 3)
 * @param grade the potential option grade (RARE, EPIC, LEGENDARY)
 * @param level the base equipment level
 * @param part the equipment part (헤어, 아이언, etc.)
 */
public record CubeRate(
    CubeType cubeType,
    String optionName,
    double rate,
    int slot,
    String grade,
    int level,
    String part) {
  /**
   * Validates the cube rate data.
   *
   * @throws IllegalArgumentException if rate is not between 0 and 1, or if required fields are
   *     null/blank
   */
  public CubeRate {
    if (cubeType == null) {
      throw new IllegalArgumentException("cubeType cannot be null");
    }
    if (optionName == null || optionName.isBlank()) {
      throw new IllegalArgumentException("optionName cannot be null or blank");
    }
    if (rate < 0.0 || rate > 1.0) {
      throw new IllegalArgumentException("rate must be between 0.0 and 1.0, got: " + rate);
    }
    if (slot < 1 || slot > 3) {
      throw new IllegalArgumentException("slot must be 1, 2, or 3, got: " + slot);
    }
    if (grade == null || grade.isBlank()) {
      throw new IllegalArgumentException("grade cannot be null or blank");
    }
    if (level < 0) {
      throw new IllegalArgumentException("level cannot be negative, got: " + level);
    }
    if (part == null || part.isBlank()) {
      throw new IllegalArgumentException("part cannot be null or blank");
    }
  }

  /** Create a cube rate with minimal required fields. */
  public static CubeRate of(CubeType cubeType, String optionName, double rate) {
    return new CubeRate(cubeType, optionName, rate, 1, "EPIC", 200, "모자");
  }
}
