package maple.expectation.domain.v2;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CharacterEquipment Entity Pure Unit Test Suite
 *
 * <p><b>Purpose:</b> Tests the business logic methods of CharacterEquipment entity without any
 * Spring/database dependencies.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Expiration logic: {@code isExpired()} threshold behavior
 *   <li>Freshness logic: {@code isFresh()} as inverse of {@code isExpired()}
 *   <li>Data presence: {@code hasData()} for various content states
 *   <li>Timestamp behavior: {@code updateData()} updates {@code updatedAt}
 * </ul>
 *
 * <p><b>Pure Unit Test:</b> No Spring, no database, no @SpringBootTest. Uses reflection to set
 * timestamps for deterministic testing.
 *
 * @see <a href="https://github.com/zbnerd/probabilistic-valuation-engine/issues/120">Issue #120</a>
 */
@Tag("unit")
@Tag("characterization")
@DisplayName("Unit: CharacterEquipment Entity Business Logic")
class CharacterEquipmentTest {

  private static final String TEST_JSON_CONTENT = "{\"item_id\": 123, \"name\": \"Test Sword\"}";

  // ==================== Test Suite 1: Expiration Logic ====================

  @Nested
  @DisplayName("Expiration logic behavior (isExpired)")
  class ExpirationLogicTests {

    @Test
    @DisplayName(
        "GIVEN: updatedAt 25 hours ago WHEN: Check isExpired(Duration.ofHours(24)) THEN: Returns true")
    void given_dataOlderThanTtl_when_checkExpired_shouldReturnTrue() {
      // GIVEN: Equipment updated 25 hours ago
      CharacterEquipment equipment =
          createEquipmentWithTimestamp(TEST_JSON_CONTENT, Duration.ofHours(-25));

      // WHEN: Check if expired with 24-hour TTL
      boolean isExpired = equipment.isExpired(Duration.ofHours(24));

      // THEN: Should be expired (25 hours > 24 hours TTL)
      assertThat(isExpired).as("Data older than TTL (25h > 24h) should be expired").isTrue();
    }

    @Test
    @DisplayName(
        "GIVEN: updatedAt exactly 24 hours ago WHEN: Check isExpired(Duration.ofHours(24)) THEN: Returns true (boundary is inclusive)")
    void given_dataExactlyAtThreshold_when_checkExpired_shouldReturnTrue() {
      // GIVEN: Equipment updated exactly 24 hours ago
      CharacterEquipment equipment =
          createEquipmentWithTimestamp(TEST_JSON_CONTENT, Duration.ofHours(-24));

      // WHEN: Check if expired with 24-hour TTL
      boolean isExpired = equipment.isExpired(Duration.ofHours(24));

      // THEN: Current behavior: EXPIRED at exact threshold
      // Documents that isBefore() makes boundary inclusive (age >= TTL means expired)
      assertThat(isExpired)
          .as("Data at exactly threshold (24h = 24h) is EXPIRED. Boundary is inclusive.")
          .isTrue();
    }

    @Test
    @DisplayName(
        "GIVEN: updatedAt 1 minute ago WHEN: Check isExpired(Duration.ofHours(1)) THEN: Returns false (fresh)")
    void given_recentData_when_checkExpired_shouldReturnFalse() {
      // GIVEN: Equipment updated 1 minute ago
      CharacterEquipment equipment =
          createEquipmentWithTimestamp(TEST_JSON_CONTENT, Duration.ofMinutes(-1));

      // WHEN: Check if expired with 1-hour TTL
      boolean isExpired = equipment.isExpired(Duration.ofHours(1));

      // THEN: Should NOT be expired (1 minute < 1 hour)
      assertThat(isExpired).as("Recent data (1min < 1hour) should be fresh").isFalse();
    }

    @Test
    @DisplayName(
        "GIVEN: null updatedAt WHEN: Check isExpired(any TTL) THEN: Returns true (defensive)")
    void given_nullUpdatedAt_when_checkExpired_shouldReturnTrue() {
      // GIVEN: Equipment with null updatedAt
      CharacterEquipment equipment = createEquipmentWithTimestamp(TEST_JSON_CONTENT, Duration.ZERO);
      setUpdatedAt(equipment, null);

      // WHEN: Check if expired with any TTL
      boolean isExpired = equipment.isExpired(Duration.ofDays(30));

      // THEN: Should be expired (null treated as expired)
      assertThat(isExpired)
          .as("Null updatedAt should be treated as expired regardless of TTL")
          .isTrue();
    }
  }

  // ==================== Test Suite 2: Freshness Logic ====================

  @Nested
  @DisplayName("Freshness logic behavior (isFresh)")
  class FreshnessLogicTests {

    @Test
    @DisplayName(
        "GIVEN: Various ages WHEN: Check isFresh() vs isExpired() THEN: Always returns opposite values")
    void given_variousAges_when_checkFreshVsExpired_shouldBeOpposite() {
      // Test cases: (age, ttl) pairs
      Duration[][] testCases = {
        {Duration.ofMinutes(5), Duration.ofHours(1)}, // Fresh
        {Duration.ofHours(2), Duration.ofHours(1)}, // Expired
        {Duration.ofHours(24), Duration.ofHours(24)}, // Boundary (fresh)
        {Duration.ofHours(25), Duration.ofHours(24)}, // Expired
      };

      for (Duration[] testCase : testCases) {
        Duration age = testCase[0];
        Duration ttl = testCase[1];

        // GIVEN: Equipment with specific age
        CharacterEquipment equipment =
            createEquipmentWithTimestamp(TEST_JSON_CONTENT, age.negated());

        // WHEN & THEN: Check consistency
        boolean expired = equipment.isExpired(ttl);
        boolean fresh = equipment.isFresh(ttl);

        assertThat(fresh)
            .as(
                "isFresh(%s) should equal !isExpired(%s) for age %s. Found: fresh=%s, expired=%s",
                ttl, ttl, age, fresh, expired)
            .isEqualTo(!expired);
      }
    }

    @Test
    @DisplayName("GIVEN: Data in the past WHEN: Check isFresh(Duration.ZERO) THEN: Returns false")
    void given_pastData_when_checkFreshWithZeroTtl_shouldReturnFalse() {
      // GIVEN: Equipment updated 1 nanosecond ago (in the past)
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
      // GIVEN: Equipment with null content
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid("test-ocid").jsonContent(null).build();

      // WHEN & THEN
      assertThat(equipment.hasData())
          .as("null jsonContent should return false from hasData()")
          .isFalse();
    }

    @Test
    @DisplayName("GIVEN: empty jsonContent WHEN: Check hasData() THEN: Returns false")
    void given_emptyJsonContent_when_checkHasData_shouldReturnFalse() {
      // GIVEN: Equipment with empty content
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid("test-ocid").jsonContent("").build();

      // WHEN & THEN
      assertThat(equipment.hasData())
          .as("Empty jsonContent should return false from hasData()")
          .isFalse();
    }

    @Test
    @DisplayName("GIVEN: whitespace-only jsonContent WHEN: Check hasData() THEN: Returns false")
    void given_whitespaceJsonContent_when_checkHasData_shouldReturnFalse() {
      // GIVEN: Equipment with whitespace content
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid("test-ocid").jsonContent("   \t\n   ").build();

      // WHEN & THEN
      assertThat(equipment.hasData())
          .as("Whitespace-only jsonContent should return false from hasData() (uses isBlank)")
          .isFalse();
    }

    @Test
    @DisplayName("GIVEN: valid jsonContent WHEN: Check hasData() THEN: Returns true")
    void given_validJsonContent_when_checkHasData_shouldReturnTrue() {
      // GIVEN: Equipment with valid content
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid("test-ocid").jsonContent(TEST_JSON_CONTENT).build();

      // WHEN & THEN
      assertThat(equipment.hasData())
          .as("Valid jsonContent should return true from hasData()")
          .isTrue();
    }
  }

  // ==================== Test Suite 4: Update Data Behavior ====================

  @Nested
  @DisplayName("Update data behavior")
  class UpdateDataTests {

    @Test
    @DisplayName(
        "GIVEN: New equipment via builder WHEN: Check updatedAt THEN: Is set to current time")
    void given_newEquipment_viaBuilder_when_checkUpdatedAt_shouldBeSet() {
      // WHEN: Create equipment via builder
      LocalDateTime beforeCreate = LocalDateTime.now();
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid("test-ocid").jsonContent(TEST_JSON_CONTENT).build();
      LocalDateTime afterCreate = LocalDateTime.now();

      // THEN: updatedAt should be set and within time window
      assertThat(equipment.getUpdatedAt())
          .as("Builder should auto-set updatedAt to current time")
          .isNotNull();
      assertThat(equipment.getUpdatedAt())
          .as("updatedAt should be between before and after create time")
          .isAfterOrEqualTo(beforeCreate.minusSeconds(1))
          .isBeforeOrEqualTo(afterCreate.plusSeconds(1));
    }

    @Test
    @DisplayName(
        "GIVEN: Existing equipment WHEN: Call updateData() THEN: updatedAt advances and content updates")
    void given_existingEquipment_when_updateData_shouldAdvanceTimestamp() {
      // GIVEN: Existing equipment
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid("test-ocid").jsonContent(TEST_JSON_CONTENT).build();

      LocalDateTime originalUpdatedAt = equipment.getUpdatedAt();

      // Wait a tiny bit to ensure timestamp difference (avoid flaky test)
      // Actually, with reflection we can set a specific timestamp
      setUpdatedAt(equipment, LocalDateTime.now().minusHours(1));
      originalUpdatedAt = equipment.getUpdatedAt();

      // WHEN: Call updateData() to change content
      String newJson = "{\"updated\": true}";
      equipment.updateData(newJson);

      // THEN: updatedAt should be newer and content updated
      assertThat(equipment.getUpdatedAt())
          .as("updateData() should advance updatedAt timestamp")
          .isAfter(originalUpdatedAt);
      assertThat(equipment.getJsonContent())
          .as("updateData() should update jsonContent")
          .isEqualTo(newJson);
    }

    @Test
    @DisplayName(
        "GIVEN: Equipment with specific timestamp WHEN: Check isExpired() THEN: Correctly evaluates based on that timestamp")
    void given_specificTimestamp_when_checkExpired_shouldUseThatTimestamp() {
      // GIVEN: Equipment with timestamp 10 minutes ago
      CharacterEquipment equipment =
          createEquipmentWithTimestamp(TEST_JSON_CONTENT, Duration.ofMinutes(-10));

      // WHEN: Check with 15-minute threshold (should be fresh)
      boolean with15Min = equipment.isExpired(Duration.ofMinutes(15));

      // WHEN: Check with 5-minute threshold (should be expired)
      boolean with5Min = equipment.isExpired(Duration.ofMinutes(5));

      // THEN: Correct evaluation
      assertThat(with15Min)
          .as("Equipment updated 10min ago should NOT be expired with 15min threshold")
          .isFalse();
      assertThat(with5Min)
          .as("Equipment updated 10min ago SHOULD be expired with 5min threshold")
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
    CharacterEquipment equipment =
        CharacterEquipment.builder().ocid("test-ocid").jsonContent(jsonContent).build();

    LocalDateTime targetTime = LocalDateTime.now().plus(offset);
    setUpdatedAt(equipment, targetTime);

    return equipment;
  }

  /**
   * Creates CharacterEquipment with current timestamp.
   *
   * @param jsonContent The JSON content
   * @return CharacterEquipment
   */
  private CharacterEquipment createEquipmentWithTimestamp(String jsonContent) {
    return CharacterEquipment.builder().ocid("test-ocid").jsonContent(jsonContent).build();
  }

  /**
   * Sets updatedAt via reflection for testing purposes.
   *
   * <p>This allows testing timestamp-dependent behavior without Thread.sleep().
   *
   * @param equipment The equipment to modify
   * @param updatedAt The timestamp to set
   */
  private void setUpdatedAt(CharacterEquipment equipment, LocalDateTime updatedAt) {
    try {
      Field field = CharacterEquipment.class.getDeclaredField("updatedAt");
      field.setAccessible(true);
      field.set(equipment, updatedAt);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set updatedAt via reflection", e);
    }
  }
}
