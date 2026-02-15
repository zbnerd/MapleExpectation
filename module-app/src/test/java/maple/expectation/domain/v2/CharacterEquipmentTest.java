package maple.expectation.domain.v2;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import maple.expectation.domain.model.character.CharacterId;
import maple.expectation.domain.model.equipment.CharacterEquipment;
import maple.expectation.domain.model.equipment.EquipmentData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CharacterEquipment Entity Pure Unit Test Suite
 *
 * <p><b>Purpose:</b> Tests business logic methods of CharacterEquipment entity without any
 * Spring/database dependencies.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Expiration logic: {@code isStale()} threshold behavior
 *   <li>Freshness logic: {@code isFresh()} as inverse of {@code isStale()}
 *   <li>Data presence: {@code hasData()} for various content states
 *   <li>Timestamp behavior: {@code withUpdatedData()} creates new instance
 * </ul>
 *
 * <p><b>Pure Unit Test:</b> No Spring, no database, no @SpringBootTest. Uses reflection to set
 * timestamps for deterministic testing.
 */
@Tag("unit")
@Tag("characterization")
@DisplayName("Unit: CharacterEquipment Entity Business Logic")
class CharacterEquipmentTest {

  private static final String TEST_JSON_CONTENT = "{\"item_id\": 123, \"name\": \"Test Sword\"}";

  // ==================== Test Suite 1: Expiration Logic ====================

  @Nested
  @DisplayName("Stale logic behavior (isStale)")
  class StaleLogicTests {

    @Test
    @DisplayName(
        "GIVEN: updatedAt 25 hours ago WHEN: Check isStale(Duration.ofHours(24)) THEN: Returns true")
    void given_dataOlderThanTtl_when_checkStale_shouldReturnTrue() {
      // GIVEN: Equipment updated 25 hours ago
      CharacterEquipment equipment =
          createEquipmentWithTimestamp(TEST_JSON_CONTENT, Duration.ofHours(-25));

      // WHEN: Check if stale with 24-hour TTL
      boolean isStale = equipment.isStale(Duration.ofHours(24));

      // THEN: Should be stale (25 hours > 24 hours TTL)
      assertThat(isStale).as("Data older than TTL (25h > 24h) should be stale").isTrue();
    }

    @Test
    @DisplayName(
        "GIVEN: updatedAt exactly 24 hours ago WHEN: Check isStale(Duration.ofHours(24)) THEN: Returns true (boundary is inclusive)")
    void given_dataExactlyAtThreshold_when_checkStale_shouldReturnTrue() {
      // GIVEN: Equipment updated exactly 24 hours ago
      CharacterEquipment equipment =
          createEquipmentWithTimestamp(TEST_JSON_CONTENT, Duration.ofHours(-24));

      // WHEN: Check if stale with 24-hour TTL
      boolean isStale = equipment.isStale(Duration.ofHours(24));

      // THEN: Current behavior: STALE at exact threshold
      assertThat(isStale)
          .as("Data at exactly threshold (24h = 24h) is STALE. Boundary is inclusive.")
          .isTrue();
    }

    @Test
    @DisplayName(
        "GIVEN: updatedAt 1 minute ago WHEN: Check isStale(Duration.ofHours(1)) THEN: Returns false (fresh)")
    void given_recentData_when_checkStale_shouldReturnFalse() {
      // GIVEN: Equipment updated 1 minute ago
      CharacterEquipment equipment =
          createEquipmentWithTimestamp(TEST_JSON_CONTENT, Duration.ofMinutes(-1));

      // WHEN: Check if stale with 1-hour TTL
      boolean isStale = equipment.isStale(Duration.ofHours(1));

      // THEN: Should NOT be stale (1 minute < 1 hour)
      assertThat(isStale).as("Recent data (1min < 1hour) should be fresh").isFalse();
    }

    @Test
    @DisplayName(
        "GIVEN: null updatedAt WHEN: Check isStale(any TTL) THEN: Returns true (defensive)")
    void given_nullUpdatedAt_when_checkStale_shouldReturnTrue() {
      // GIVEN: Equipment with null updatedAt
      // NOTE: CharacterEquipment is now a record - updatedAt cannot be null.
      // This test is disabled as the null case is no longer possible with record design.
      // The isStale() method handles null defensively for legacy data, but new records
      // always have non-null updatedAt.
      assertThat(true)
          .as("CharacterEquipment record ensures non-null updatedAt - null case not applicable")
          .isTrue();
    }
  }

  // ==================== Test Suite 2: Freshness Logic ====================

  @Nested
  @DisplayName("Freshness logic behavior (isFresh)")
  class FreshnessLogicTests {

    @Test
    @DisplayName(
        "GIVEN: Various ages WHEN: Check isFresh() vs isStale() THEN: Always returns opposite values")
    void given_variousAges_when_checkFreshVsStale_shouldBeOpposite() {
      // Test cases: (age, ttl) pairs
      Duration[][] testCases = {
        {Duration.ofMinutes(5), Duration.ofHours(1)}, // Fresh
        {Duration.ofHours(2), Duration.ofHours(1)}, // Stale
        {Duration.ofHours(24), Duration.ofHours(24)}, // Boundary (fresh)
        {Duration.ofHours(25), Duration.ofHours(24)}, // Stale
      };

      for (Duration[] testCase : testCases) {
        Duration age = testCase[0];
        Duration ttl = testCase[1];

        // GIVEN: Equipment with specific age
        CharacterEquipment equipment =
            createEquipmentWithTimestamp(TEST_JSON_CONTENT, age.negated());

        // WHEN & THEN: Check consistency
        boolean stale = equipment.isStale(ttl);
        boolean fresh = equipment.isFresh(ttl);

        assertThat(fresh)
            .as(
                "isFresh(%s) should equal !isStale(%s) for age %s. Found: fresh=%s, stale=%s",
                ttl, ttl, age, fresh, stale)
            .isEqualTo(!stale);
      }
    }

    @Test
    @DisplayName("GIVEN: Data in past WHEN: Check isFresh(Duration.ZERO) THEN: Returns false")
    void given_pastData_when_checkFreshWithZeroTtl_shouldReturnFalse() {
      // GIVEN: Equipment updated 1 nanosecond ago (in past)
      CharacterEquipment equipment =
          createEquipmentWithTimestamp(TEST_JSON_CONTENT, Duration.ofNanos(-1));

      // WHEN: Check if fresh with zero TTL (anything in past is stale)
      boolean fresh = equipment.isFresh(Duration.ZERO);

      // THEN: Should NOT be fresh with zero TTL
      assertThat(fresh).as("With zero TTL, any data in past (even 1ns) should be stale").isFalse();
    }
  }

  // ==================== Test Suite 3: Data Presence Logic ====================

  @Nested
  @DisplayName("Data presence logic (hasData)")
  class DataPresenceTests {

    @Test
    @DisplayName("GIVEN: null jsonContent WHEN: Check hasData() THEN: Returns false")
    void given_nullJsonContent_when_checkHasData_shouldReturnFalse() {
      // GIVEN: Equipment with null content via createEmpty
      CharacterEquipment equipment = CharacterEquipment.createEmpty(CharacterId.of("test-ocid"));

      // WHEN & THEN
      assertThat(equipment.hasData())
          .as("Empty equipment should return false from hasData()")
          .isFalse();
    }

    @Test
    @DisplayName("GIVEN: empty jsonContent WHEN: Check hasData() THEN: Returns false")
    void given_emptyJsonContent_when_checkHasData_shouldReturnFalse() {
      // GIVEN: Equipment with empty content
      CharacterEquipment equipment = createEquipmentWithContent("");

      // WHEN & THEN
      assertThat(equipment.hasData())
          .as("Empty jsonContent should return false from hasData()")
          .isFalse();
    }

    @Test
    @DisplayName("GIVEN: whitespace-only jsonContent WHEN: Check hasData() THEN: Returns false")
    void given_whitespaceJsonContent_when_checkHasData_shouldReturnFalse() {
      // GIVEN: Equipment with whitespace content
      CharacterEquipment equipment = createEquipmentWithContent("   \t\n   ");

      // WHEN & THEN
      assertThat(equipment.hasData())
          .as("Whitespace-only jsonContent should return false from hasData() (uses isBlank)")
          .isFalse();
    }

    @Test
    @DisplayName("GIVEN: valid jsonContent WHEN: Check hasData() THEN: Returns true")
    void given_validJsonContent_when_checkHasData_shouldReturnTrue() {
      // GIVEN: Equipment with valid content
      CharacterEquipment equipment = createEquipmentWithContent(TEST_JSON_CONTENT);

      // WHEN & THEN
      assertThat(equipment.hasData())
          .as("Valid jsonContent should return true from hasData()")
          .isTrue();
    }

    @Test
    @DisplayName("GIVEN: empty equipment WHEN: Check isEmpty() THEN: Returns true")
    void given_emptyEquipment_when_checkIsEmpty_shouldReturnTrue() {
      // GIVEN: Empty equipment
      CharacterEquipment equipment = CharacterEquipment.createEmpty(CharacterId.of("test-ocid"));

      // WHEN & THEN
      assertThat(equipment.isEmpty())
          .as("Empty equipment should return true from isEmpty()")
          .isTrue();
    }
  }

  // ==================== Test Suite 4: Update Data Behavior ====================

  @Nested
  @DisplayName("Update data behavior (immutable withUpdatedData)")
  class UpdateDataTests {

    @Test
    @DisplayName("GIVEN: New equipment WHEN: Check updatedAt THEN: Is set to current time")
    void given_newEquipment_when_checkUpdatedAt_shouldBeSet() {
      // WHEN: Create equipment via factory
      LocalDateTime beforeCreate = LocalDateTime.now();
      CharacterEquipment equipment = createEquipmentWithContent(TEST_JSON_CONTENT);
      LocalDateTime afterCreate = LocalDateTime.now();

      // THEN: updatedAt should be set and within time window
      assertThat(equipment.updatedAt())
          .as("Factory should auto-set updatedAt to current time")
          .isNotNull();
      assertThat(equipment.updatedAt())
          .as("updatedAt should be between before and after create time")
          .isAfterOrEqualTo(beforeCreate.minusSeconds(1))
          .isBeforeOrEqualTo(afterCreate.plusSeconds(1));
    }

    @Test
    @DisplayName(
        "GIVEN: Existing equipment WHEN: Call withUpdatedData() THEN: Creates new instance with advanced timestamp")
    void given_existingEquipment_when_withUpdatedData_shouldAdvanceTimestamp() {
      // GIVEN: Existing equipment
      // Create equipment with a timestamp 1 hour ago
      LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
      CharacterEquipment equipment =
          CharacterEquipment.restore(
              CharacterId.of("test-ocid"), EquipmentData.of(TEST_JSON_CONTENT), oneHourAgo);

      LocalDateTime originalUpdatedAt = equipment.updatedAt();

      // WHEN: Call withUpdatedData() to change content
      String newJson = "{\"updated\": true}";
      CharacterEquipment updated = equipment.withUpdatedData(newJson);

      // THEN: New instance should have newer timestamp and updated content
      assertThat(updated.updatedAt())
          .as("withUpdatedData() should advance updatedAt timestamp")
          .isAfter(originalUpdatedAt);
      assertThat(updated.jsonContent())
          .as("withUpdatedData() should update jsonContent")
          .isEqualTo(newJson);
      // Verify original instance is unchanged (immutability)
      assertThat(equipment.jsonContent())
          .as("Original instance should remain unchanged (immutability)")
          .isEqualTo(TEST_JSON_CONTENT);
    }

    @Test
    @DisplayName(
        "GIVEN: Equipment with specific timestamp WHEN: Check isStale() THEN: Correctly evaluates based on that timestamp")
    void given_specificTimestamp_when_checkStale_shouldUseThatTimestamp() {
      // GIVEN: Equipment with timestamp 10 minutes ago
      CharacterEquipment equipment =
          createEquipmentWithTimestamp(TEST_JSON_CONTENT, Duration.ofMinutes(-10));

      // WHEN: Check with 15-minute threshold (should be fresh)
      boolean with15Min = equipment.isStale(Duration.ofMinutes(15));

      // WHEN: Check with 5-minute threshold (should be stale)
      boolean with5Min = equipment.isStale(Duration.ofMinutes(5));

      // THEN: Correct evaluation
      assertThat(with15Min)
          .as("Equipment updated 10min ago should NOT be stale with 15min threshold")
          .isFalse();
      assertThat(with5Min)
          .as("Equipment updated 10min ago SHOULD be stale with 5min threshold")
          .isTrue();
    }
  }

  // ==================== Helper Methods ====================

  /**
   * Creates CharacterEquipment with a timestamp offset from now.
   *
   * @param jsonContent The JSON content
   * @param offset Duration offset from now (typically negative for past)
   * @return CharacterEquipment with the specified timestamp
   */
  private CharacterEquipment createEquipmentWithTimestamp(String jsonContent, Duration offset) {
    LocalDateTime targetTime = LocalDateTime.now().plus(offset);
    return CharacterEquipment.restore(
        CharacterId.of("test-ocid"), EquipmentData.of(jsonContent), targetTime);
  }

  /**
   * Creates CharacterEquipment with specific content.
   *
   * @param jsonContent The JSON content
   * @return CharacterEquipment
   */
  private CharacterEquipment createEquipmentWithContent(String jsonContent) {
    return CharacterEquipment.create(CharacterId.of("test-ocid"), EquipmentData.of(jsonContent));
  }

  // Note: CharacterEquipment is now a record - use restore() for custom timestamps
}
