package maple.expectation.core.domain.model;

/**
 * Potential stat domain model.
 *
 * <p>Represents a potential option that can appear on equipment.
 *
 * <p>Pure domain model - no external dependencies.
 *
 * @param optionName the name of the potential option
 * @param statType the type of stat (STR, DEX, INT, LUK, etc.)
 * @param isPercent whether the stat is percentage-based
 */
public record PotentialStat(String optionName, String statType, boolean isPercent) {
  /** Validates the potential stat data. */
  public PotentialStat {
    if (optionName == null || optionName.isBlank()) {
      throw new IllegalArgumentException("optionName cannot be null or blank");
    }
    if (statType == null || statType.isBlank()) {
      throw new IllegalArgumentException("statType cannot be null or blank");
    }
  }

  /** Create a potential stat. */
  public static PotentialStat of(String optionName, String statType, boolean isPercent) {
    return new PotentialStat(optionName, statType, isPercent);
  }

  /** Create a percentage-based potential stat. */
  public static PotentialStat percentStat(String optionName, String statType) {
    return new PotentialStat(optionName, statType, true);
  }

  /** Create a flat-value potential stat. */
  public static PotentialStat flatStat(String optionName, String statType) {
    return new PotentialStat(optionName, statType, false);
  }
}
