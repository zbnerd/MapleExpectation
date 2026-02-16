package maple.expectation.core.domain.model;

/**
 * Cube type enum representing different cube categories in MapleStory.
 *
 * <p>Pure domain model - no external dependencies.
 */
public enum CubeType {
  /** Black Cube - resets potential options */
  BLACK("블랙큐브"),

  /** Red Cube - resets potential options with higher chances for legendary */
  RED("레드큐브"),

  /** Additional Cube - resets additional potential options */
  ADDITIONAL("에디셔널큐브");

  private final String description;

  CubeType(String description) {
    this.description = description;
  }

  /**
   * Get the Korean description of this cube type.
   *
   * @return the description
   */
  public String getDescription() {
    return description;
  }
}
