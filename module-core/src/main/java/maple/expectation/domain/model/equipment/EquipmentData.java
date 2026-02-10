package maple.expectation.domain.model.equipment;

/**
 * Value Object representing equipment data in JSON format.
 *
 * <p>This Value Object is immutable and validates that the JSON content is not blank. It provides
 * JSON validation through {@link #isValidJson()} method.
 *
 * <p>As a pure domain model, this class has NO dependencies on Spring or JPA annotations. JSON
 * validation uses a lightweight heuristic approach without external dependencies.
 *
 * <h3>JSON Validation Strategy</h3>
 *
 * <p>Uses lightweight syntax checking (braces/brackets matching) rather than full parsing to
 * maintain domain purity. Full validation should be performed by infrastructure layer mappers when
 * needed.
 *
 * @param jsonContent the JSON string containing equipment data
 * @throws IllegalArgumentException if jsonContent is null or blank
 */
public record EquipmentData(String jsonContent) {

  /** Compact constructor that validates the JSON content. */
  public EquipmentData {
    if (jsonContent == null || jsonContent.isBlank()) {
      throw new IllegalArgumentException("EquipmentData cannot be null or blank");
    }
  }

  /**
   * Factory method to create EquipmentData.
   *
   * @param jsonContent the JSON string
   * @return validated EquipmentData
   */
  public static EquipmentData of(String jsonContent) {
    return new EquipmentData(jsonContent);
  }

  /**
   * Lightweight JSON validation using syntax checking.
   *
   * <p>This is a heuristic validation that checks basic JSON syntax:
   *
   * <ul>
   *   <li>Non-empty content
   *   <li>Balanced braces and brackets
   *   <li>Valid JSON structure markers
   * </ul>
   *
   * <p><b>Note:</b> For full JSON schema validation, use infrastructure layer mappers with Jackson
   * ObjectMapper. This method keeps the domain layer dependency-free.
   *
   * @return true if content appears to be valid JSON, false otherwise
   */
  public boolean isValidJson() {
    if (jsonContent == null || jsonContent.isBlank()) {
      return false;
    }

    String trimmed = jsonContent.trim();

    // Check JSON structure markers
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
      return false;
    }

    // Check balanced braces and brackets
    int braceCount = 0;
    int bracketCount = 0;

    for (char c : trimmed.toCharArray()) {
      if (c == '{') braceCount++;
      else if (c == '}') braceCount--;
      else if (c == '[') bracketCount++;
      else if (c == ']') bracketCount--;

      // Early exit if unbalanced
      if (braceCount < 0 || bracketCount < 0) {
        return false;
      }
    }

    return braceCount == 0 && bracketCount == 0;
  }

  /**
   * Returns the raw JSON content.
   *
   * <p>This method is provided for infrastructure layer mappers to convert between domain and
   * persistence models.
   *
   * @return the JSON string
   */
  public String jsonContent() {
    return jsonContent;
  }
}
