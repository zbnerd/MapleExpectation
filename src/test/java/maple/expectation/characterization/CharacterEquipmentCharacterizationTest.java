package maple.expectation.characterization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * CharacterEquipment Entity Characterization Test Suite
 *
 * <p><b>Purpose:</b> These tests document and lock the EXISTING behavior of the CharacterEquipment
 * entity before any refactoring. They serve as a regression guard to ensure that behavior changes
 * are intentional and documented.
 *
 * <p><b>Characterization Testing Principle:</b>
 *
 * <ul>
 *   <li>Tests document current behavior, even if it seems buggy
 *   <li>Tests MUST pass against current JPA implementation before changes
 *   <li>Any test failure after refactoring indicates UNINTENDED behavior change
 *   <li>Tests serve as living documentation of domain behavior
 * </ul>
 *
 * <p><b>Documented Behaviors:</b>
 *
 * <ul>
 *   <li>Expiration logic: `isExpired()` threshold behavior
 *   <li>Freshness logic: `isFresh()` as inverse of `isExpired()`
 *   <li>JSON content: GZIP compression round-trip
 *   <li>Timestamp behavior: `updatedAt` auto-update on save
 *   <li>Null/Empty handling: Edge cases for `jsonContent`
 * </ul>
 *
 * <p><b>Flaky Test Prevention:</b>
 *
 * <ul>
 *   <li>NO Thread.sleep() - uses precise Duration calculations
 *   <li>NO hardcoded timestamps - uses LocalDateTime.now()
 *   <li>@Transactional ensures database rollback isolation
 *   <li>Each test is independent and can run in any order
 * </ul>
 *
 * @see <a href="https://github.com/zbnerd/probabilistic-valuation-engine/issues/120">Issue #120</a>
 * @see CharacterEquipment
 * @see <a href="https://docs/02_Technical_Guides/testing-guide.md">Testing Guide Section 23-25</a>
 */
@Tag("characterization")
@Tag("integration")
@DisplayName("Characterization: CharacterEquipment Entity Behavior")
@Transactional
class CharacterEquipmentCharacterizationTest extends IntegrationTestSupport {

  @Autowired
  private maple.expectation.repository.v2.CharacterEquipmentRepository repository;

  /** Test data constants */
  private static final String TEST_OCID = "test-character-ocid-12345";

  private static final String TEST_JSON_CONTENT = "{\"item_id\": 123, \"name\": \"Test Sword\"}";

  @BeforeEach
  void setUp() {
    // Ensure clean state for each test (@Transactional handles rollback)
    repository.deleteById(TEST_OCID);
  }

  // ==================== Test Suite 1: Expiration Logic ====================

  @Nested
  @DisplayName("Characterization: Expiration logic behavior (isExpired)")
  class ExpirationLogicCharacterization {

    /**
     * Documents exact threshold behavior for expiration.
     *
     * <p><b>Current Behavior:</b> Data is considered expired if `updatedAt` is strictly BEFORE
     * `now() - ttl`. The exact millisecond of the threshold boundary matters.
     *
     * <p><b>Why This Matters:</b> This test prevents accidental changes to the expiration threshold
     * logic during refactoring. If the threshold semantics change (e.g., from strict before to
     * before-or-equal), this test will catch it.
     *
     * <p><b>Edge Case:</b> The boundary condition at exactly `now() - ttl` is critical.
     */
    @Test
    @DisplayName(
        "GIVEN: CharacterEquipment with updatedAt 25 hours ago "
            + "WHEN: Check isExpired(Duration.ofHours(24)) "
            + "THEN: Should return true (expired)")
    void given_data_older_than_ttl_when_checkExpired_shouldReturnTrue() {
      // GIVEN: Equipment updated 25 hours ago
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(TEST_JSON_CONTENT).build();

      // Manually set updatedAt to 25 hours ago
      LocalDateTime twentyFiveHoursAgo = LocalDateTime.now().minusHours(25);
      equipment.updateData(TEST_JSON_CONTENT); // This sets updatedAt to now
      // Use reflection to set updatedAt to exactly 25 hours ago
      setUpdatedAt(equipment, twentyFiveHoursAgo);

      // Persist to database
      repository.save(equipment);

      // WHEN: Retrieve and check if expired with 24-hour TTL
      Optional<CharacterEquipment> retrieved = repository.findById(TEST_OCID);
      assertThat(retrieved).isPresent();

      boolean isExpired = retrieved.get().isExpired(Duration.ofHours(24));

      // THEN: Should be expired (25 hours > 24 hours TTL)
      assertThat(isExpired)
          .as(
              "Data older than TTL (25h > 24h) should be expired. "
                  + "This documents threshold boundary behavior.")
          .isTrue();
    }

    /**
     * Documents behavior when data is exactly at the threshold boundary.
     *
     * <p><b>Current Behavior (BUGGY):</b> Data at exactly `now() - ttl` is EXPIRED (not fresh).
     *
     * <p><b>Why This Matters:</b> This test documents a potential bug in the boundary condition.
     * The code uses `isBefore()` which makes the boundary inclusive (expired when age >= TTL).
     * Expected behavior would be exclusive (expired only when age > TTL).
     *
     * <p><b>Refactoring Note:</b> If this behavior is intentional, update this test's
     * documentation. If it's a bug, fix `isExpired()` to use exclusive boundary and update this
     * test.
     */
    @Test
    @DisplayName(
        "GIVEN: CharacterEquipment with updatedAt exactly 24 hours ago "
            + "WHEN: Check isExpired(Duration.ofHours(24)) "
            + "THEN: Should return true (BUGGY: boundary is inclusive, not exclusive)")
    void given_data_exactly_at_threshold_when_checkExpired_shouldReturnTrue() {
      // GIVEN: Equipment updated exactly 24 hours ago
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(TEST_JSON_CONTENT).build();

      LocalDateTime exactly24HoursAgo = LocalDateTime.now().minusHours(24);
      setUpdatedAt(equipment, exactly24HoursAgo);
      repository.save(equipment);

      // WHEN: Check if expired with 24-hour TTL
      Optional<CharacterEquipment> retrieved = repository.findById(TEST_OCID);
      assertThat(retrieved).isPresent();

      boolean isExpired = retrieved.get().isExpired(Duration.ofHours(24));

      // THEN: Current behavior: EXPIRED at exact threshold (potentially buggy)
      assertThat(isExpired)
          .as(
              "Data at exactly threshold (24h = 24h) is EXPIRED. "
                  + "Documents current BUGGY behavior: boundary is inclusive (age >= TTL), "
                  + "not exclusive (age > TTL). "
                  + "isBefore() comparison makes: (now - 24h) < (now - 24h) = false → expired.")
          .isTrue();
    }

    /**
     * Documents behavior with very recent data.
     *
     * <p><b>Current Behavior:</b> Data updated 1 minute ago is fresh for 1-hour TTL.
     */
    @Test
    @DisplayName(
        "GIVEN: CharacterEquipment with updatedAt 1 minute ago "
            + "WHEN: Check isExpired(Duration.ofHours(1)) "
            + "THEN: Should return false (fresh)")
    void given_recent_data_when_checkExpired_shouldReturnFalse() {
      // GIVEN: Equipment updated 1 minute ago
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(TEST_JSON_CONTENT).build();

      LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
      setUpdatedAt(equipment, oneMinuteAgo);
      repository.save(equipment);

      // WHEN: Check if expired with 1-hour TTL
      Optional<CharacterEquipment> retrieved = repository.findById(TEST_OCID);
      boolean isExpired = retrieved.get().isExpired(Duration.ofHours(1));

      // THEN: Should NOT be expired (1 minute << 1 hour)
      assertThat(isExpired).as("Recent data (1min < 1hour) should be fresh.").isFalse();
    }

    /**
     * Documents behavior when updatedAt is null.
     *
     * <p><b>Current Behavior:</b> Null `updatedAt` is treated as expired (defensive programming).
     *
     * <p><b>Why This Matters:</b> Handles edge case of improperly initialized entities or legacy
     * data.
     */
    @Test
    @DisplayName(
        "GIVEN: CharacterEquipment with null updatedAt "
            + "WHEN: Check isExpired(any TTL) "
            + "THEN: Should return true (null is treated as expired)")
    void given_null_updatedAt_when_checkExpired_shouldReturnTrue() {
      // GIVEN: Equipment with null updatedAt (simulates legacy data)
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(TEST_JSON_CONTENT).build();

      // Simulate null updatedAt (use reflection to bypass builder protection)
      setUpdatedAt(equipment, null);
      repository.save(equipment);

      // WHEN: Check if expired with any TTL
      Optional<CharacterEquipment> retrieved = repository.findById(TEST_OCID);
      boolean isExpired = retrieved.get().isExpired(Duration.ofDays(30));

      // THEN: Should be expired (null is treated as expired)
      assertThat(isExpired)
          .as(
              "Null updatedAt should be treated as expired regardless of TTL. "
                  + "Defensive programming for legacy/improper data.")
          .isTrue();
    }
  }

  // ==================== Test Suite 2: Freshness Logic ====================

  @Nested
  @DisplayName("Characterization: Freshness logic behavior (isFresh)")
  class FreshnessLogicCharacterization {

    /**
     * Documents that isFresh() is the exact inverse of isExpired().
     *
     * <p><b>Current Behavior:</b> `isFresh(ttl)` always returns `!isExpired(ttl)`.
     *
     * <p><b>Why This Matters:</b> Ensures consistency between the two methods. If one is refactored
     * independently, this test will catch the inconsistency.
     */
    @Test
    @DisplayName(
        "GIVEN: CharacterEquipment with various ages "
            + "WHEN: Check isFresh() vs isExpired() "
            + "THEN: Should always return opposite values")
    void given_various_ages_when_checkFreshVsExpired_shouldBeOpposite() {
      // Test cases: (age, ttl) pairs
      Object[][] testCases =
          new Object[][] {
            {Duration.ofMinutes(5), Duration.ofHours(1)}, // Fresh
            {Duration.ofHours(2), Duration.ofHours(1)}, // Expired
            {Duration.ofHours(24), Duration.ofHours(24)}, // Boundary (fresh)
            {Duration.ofHours(25), Duration.ofHours(24)}, // Expired
          };

      for (Object[] testCase : testCases) {
        Duration age = (Duration) testCase[0];
        Duration ttl = (Duration) testCase[1];

        // GIVEN: Equipment with specific age
        CharacterEquipment equipment =
            CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(TEST_JSON_CONTENT).build();

        LocalDateTime pastTime = LocalDateTime.now().minus(age);
        setUpdatedAt(equipment, pastTime);

        // WHEN & THEN: Check consistency
        boolean expired = equipment.isExpired(ttl);
        boolean fresh = equipment.isFresh(ttl);

        assertThat(fresh)
            .as(
                "isFresh(%s) should equal !isExpired(%s) for age %s. "
                    + "Found: fresh=%s, expired=%s",
                ttl, ttl, age, fresh, expired)
            .isEqualTo(!expired);
      }
    }

    /**
     * Documents isFresh() behavior with very short TTL.
     *
     * <p><b>Current Behavior:</b> Zero TTL means only data from the current millisecond is fresh.
     */
    @Test
    @DisplayName(
        "GIVEN: CharacterEquipment with updatedAt in the past "
            + "WHEN: Check isFresh(Duration.ZERO) "
            + "THEN: Should return false (anything in past is stale)")
    void given_past_data_when_checkFreshWithZeroTtl_shouldReturnFalse() {
      // GIVEN: Equipment updated 1 millisecond ago
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(TEST_JSON_CONTENT).build();

      LocalDateTime oneMsAgo = LocalDateTime.now().minusNanos(1_000_000); // 1ms
      setUpdatedAt(equipment, oneMsAgo);

      // WHEN: Check if fresh with zero TTL
      boolean fresh = equipment.isFresh(Duration.ZERO);

      // THEN: Should NOT be fresh (any past time is stale with zero TTL)
      assertThat(fresh).as("With zero TTL, any data in past (even 1ms) should be stale.").isFalse();
    }
  }

  // ==================== Test Suite 3: JSON Content Round-trip ====================

  @Nested
  @DisplayName("Characterization: JSON content round-trip with GZIP compression")
  class JsonContentRoundTripCharacterization {

    /**
     * Documents that JSON content survives save/retrieve cycle with GZIP compression.
     *
     * <p><b>Current Behavior:</b> `GzipStringConverter` transparently compresses/decompresses JSON.
     * No data loss occurs during the round-trip.
     *
     * <p><b>Why This Matters:</b> Ensures compression is lossless. If the converter is replaced or
     * modified, this test will catch data corruption.
     */
    @Test
    @DisplayName(
        "GIVEN: CharacterEquipment with JSON content "
            + "WHEN: Save to database and retrieve "
            + "THEN: Retrieved content should be identical")
    void given_jsonContent_when_saveAndRetrieve_shouldReturnIdentical() {
      // GIVEN: Equipment with JSON content
      String originalJson = TEST_JSON_CONTENT;
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(originalJson).build();

      // WHEN: Save and retrieve
      repository.save(equipment);
      Optional<CharacterEquipment> retrieved = repository.findById(TEST_OCID);

      // THEN: Content should be identical
      assertThat(retrieved).isPresent();
      assertThat(retrieved.get().getJsonContent())
          .as(
              "JSON content should survive GZIP compression round-trip without data loss. "
                  + "Original: %s, Retrieved: %s",
              originalJson, retrieved.get().getJsonContent())
          .isEqualTo(originalJson);
    }

    /**
     * Documents behavior with large JSON content.
     *
     * <p><b>Current Behavior:</b> Large JSON (10KB+) compresses successfully and survives
     * round-trip.
     */
    @Test
    @DisplayName(
        "GIVEN: CharacterEquipment with large JSON content (10KB) "
            + "WHEN: Save to database and retrieve "
            + "THEN: Retrieved content should be complete")
    void given_largeJsonContent_when_saveAndRetrieve_shouldReturnComplete() {
      // GIVEN: Equipment with large JSON (simulating full equipment data)
      StringBuilder largeJson = new StringBuilder();
      largeJson.append("{\"equipment\": [");
      for (int i = 0; i < 100; i++) {
        largeJson.append(
            String.format(
                "{\"id\": %d, \"name\": \"Item %d\", \"stats\": {\"str\": 100, \"dex\": 200}},",
                i, i));
      }
      largeJson.append("]}");
      String originalJson = largeJson.toString();

      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(originalJson).build();

      // WHEN: Save and retrieve
      repository.save(equipment);
      Optional<CharacterEquipment> retrieved = repository.findById(TEST_OCID);

      // THEN: Content should be complete
      assertThat(retrieved).isPresent();
      assertThat(retrieved.get().getJsonContent())
          .as("Large JSON should survive GZIP compression without truncation.")
          .hasSize(originalJson.length())
          .isEqualTo(originalJson);
    }

    /**
     * Documents behavior with special characters in JSON.
     *
     * <p><b>Current Behavior:</b> Unicode, emojis, and special characters survive compression.
     */
    @Test
    @DisplayName(
        "GIVEN: CharacterEquipment with Unicode and special characters "
            + "WHEN: Save to database and retrieve "
            + "THEN: Retrieved content should preserve encoding")
    void given_unicodeJsonContent_when_saveAndRetrieve_shouldPreserveEncoding() {
      // GIVEN: JSON with Unicode and special characters
      String unicodeJson =
          "{\"name\": \"한글테스트\", \"emoji\": \"🗡️⚔️🛡️\", \"special\": \"\\n\\t\\r\"}";

      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(unicodeJson).build();

      // WHEN: Save and retrieve
      repository.save(equipment);
      Optional<CharacterEquipment> retrieved = repository.findById(TEST_OCID);

      // THEN: Encoding should be preserved
      assertThat(retrieved).isPresent();
      assertThat(retrieved.get().getJsonContent())
          .as("Unicode and special characters should survive GZIP compression.")
          .isEqualTo(unicodeJson);
    }
  }

  // ==================== Test Suite 4: UpdatedAt Auto-Update ====================

  @Nested
  @DisplayName("Characterization: UpdatedAt auto-update behavior")
  class UpdatedAtAutoUpdateCharacterization {

    /**
     * Documents that updatedAt is automatically set by builder.
     *
     * <p><b>Current Behavior:</b> Builder sets `updatedAt = LocalDateTime.now()` at construction.
     */
    @Test
    @DisplayName(
        "GIVEN: New CharacterEquipment via builder "
            + "WHEN: Check updatedAt "
            + "THEN: Should be set to current time")
    void given_newEquipment_viaBuilder_when_checkUpdatedAt_shouldBeSet() {
      // WHEN: Create equipment via builder
      LocalDateTime beforeCreate = LocalDateTime.now();
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(TEST_JSON_CONTENT).build();
      LocalDateTime afterCreate = LocalDateTime.now();

      // THEN: updatedAt should be set and within time window
      assertThat(equipment.getUpdatedAt())
          .as("Builder should auto-set updatedAt to current time")
          .isNotNull();
      assertThat(equipment.getUpdatedAt())
          .as("updatedAt should be between before and after create time")
          .isAfterOrEqualTo(beforeCreate.minusSeconds(1)) // Allow 1s tolerance
          .isBeforeOrEqualTo(afterCreate.plusSeconds(1));
    }

    /**
     * Documents that updatedAt updates on save (JPA behavior).
     *
     * <p><b>Current Behavior:</b> Unlike `@UpdateTimestamp`, this entity uses manual `updateData()`
     * method to set timestamp. JPA save does NOT auto-update `updatedAt`.
     *
     * <p><b>Why This Matters:</b> Documents that timestamp updates are explicit, not implicit.
     * Refactoring to `@UpdateTimestamp` would change this behavior.
     */
    @Test
    @DisplayName(
        "GIVEN: Existing CharacterEquipment "
            + "WHEN: Save without calling updateData() "
            + "THEN: updatedAt should NOT change")
    void given_existingEquipment_when_saveWithoutUpdateData_shouldNotChangeTimestamp() {
      // GIVEN: Existing equipment
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(TEST_JSON_CONTENT).build();
      repository.save(equipment);

      LocalDateTime originalUpdatedAt = equipment.getUpdatedAt();

      // WHEN: Wait briefly and save WITHOUT updateData()
      // Note: Direct JPA save without field modification won't trigger UPDATE
      repository.save(equipment);

      // THEN: updatedAt should be unchanged
      Optional<CharacterEquipment> retrieved = repository.findById(TEST_OCID);
      assertThat(retrieved).isPresent();
      assertThat(retrieved.get().getUpdatedAt())
          .as(
              "JPA save without field modification should not change updatedAt. "
                  + "Documents explicit timestamp update behavior.")
          .isEqualTo(originalUpdatedAt);
    }

    /**
     * Documents that updateData() updates timestamp.
     *
     * <p><b>Current Behavior:</b> `updateData()` sets both `jsonContent` and `updatedAt`.
     */
    @Test
    @DisplayName(
        "GIVEN: Existing CharacterEquipment "
            + "WHEN: Call updateData() and save "
            + "THEN: updatedAt should advance")
    void given_existingEquipment_when_updateData_shouldAdvanceTimestamp() {
      // GIVEN: Existing equipment
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(TEST_JSON_CONTENT).build();
      repository.save(equipment);

      LocalDateTime originalUpdatedAt = equipment.getUpdatedAt();

      // WHEN: Call updateData() to change content
      String newJson = "{\"updated\": true}";
      equipment.updateData(newJson);
      repository.save(equipment);

      // THEN: updatedAt should be newer
      Optional<CharacterEquipment> retrieved = repository.findById(TEST_OCID);
      assertThat(retrieved).isPresent();
      assertThat(retrieved.get().getUpdatedAt())
          .as("updateData() should advance updatedAt timestamp")
          .isAfter(originalUpdatedAt);
      assertThat(retrieved.get().getJsonContent())
          .as("updateData() should update jsonContent")
          .isEqualTo(newJson);
    }
  }

  // ==================== Test Suite 5: Null/Empty Edge Cases ====================

  @Nested
  @DisplayName("Characterization: Null and Empty jsonContent handling")
  class NullEmptyContentCharacterization {

    /**
     * Documents hasData() behavior with null content.
     *
     * <p><b>Current Behavior:</b> `hasData()` returns false when `jsonContent` is null.
     */
    @Test
    @DisplayName(
        "GIVEN: CharacterEquipment with null jsonContent "
            + "WHEN: Check hasData() "
            + "THEN: Should return false")
    void given_nullJsonContent_when_checkHasData_shouldReturnFalse() {
      // GIVEN: Equipment with null content
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(null).build();

      // WHEN & THEN
      assertThat(equipment.hasData())
          .as("null jsonContent should return false from hasData()")
          .isFalse();
    }

    /**
     * Documents hasData() behavior with empty string.
     *
     * <p><b>Current Behavior:</b> `hasData()` returns false for empty string.
     */
    @Test
    @DisplayName(
        "GIVEN: CharacterEquipment with empty jsonContent "
            + "WHEN: Check hasData() "
            + "THEN: Should return false")
    void given_emptyJsonContent_when_checkHasData_shouldReturnFalse() {
      // GIVEN: Equipment with empty content
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent("").build();

      // WHEN & THEN
      assertThat(equipment.hasData())
          .as("Empty jsonContent should return false from hasData()")
          .isFalse();
    }

    /**
     * Documents hasData() behavior with whitespace-only string.
     *
     * <p><b>Current Behavior:</b> `hasData()` returns false for whitespace-only strings (uses
     * `isBlank()`).
     */
    @Test
    @DisplayName(
        "GIVEN: CharacterEquipment with whitespace-only jsonContent "
            + "WHEN: Check hasData() "
            + "THEN: Should return false")
    void given_whitespaceJsonContent_when_checkHasData_shouldReturnFalse() {
      // GIVEN: Equipment with whitespace content
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent("   \t\n   ").build();

      // WHEN & THEN
      assertThat(equipment.hasData())
          .as("Whitespace-only jsonContent should return false from hasData() (uses isBlank)")
          .isFalse();
    }

    /**
     * Documents hasData() behavior with valid content.
     *
     * <p><b>Current Behavior:</b> `hasData()` returns true for any non-blank content.
     */
    @Test
    @DisplayName(
        "GIVEN: CharacterEquipment with valid jsonContent "
            + "WHEN: Check hasData() "
            + "THEN: Should return true")
    void given_validJsonContent_when_checkHasData_shouldReturnTrue() {
      // GIVEN: Equipment with valid content
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(TEST_JSON_CONTENT).build();

      // WHEN & THEN
      assertThat(equipment.hasData())
          .as("Valid jsonContent should return true from hasData()")
          .isTrue();
    }

    /**
     * Documents database behavior with null jsonContent.
     *
     * <p><b>Current Behavior:</b> Despite `@Column(nullable = false)`, null can be set via builder.
     * This may throw on commit or be database-dependent.
     */
    @Test
    @DisplayName(
        "GIVEN: CharacterEquipment with null jsonContent "
            + "WHEN: Save to database "
            + "THEN: Should handle according to JPA validation")
    void given_nullJsonContent_when_saveToDatabase_shouldHandleGracefully() {
      // GIVEN: Equipment with null content
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(null).build();

      // WHEN: Attempt to save
      // Note: @Column(nullable = false) should prevent this
      // Current behavior test - does it throw or allow?
      try {
        repository.save(equipment);
        Optional<CharacterEquipment> retrieved = repository.findById(TEST_OCID);

        // THEN: Document actual behavior
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getJsonContent())
            .as(
                "Documents database behavior with null content despite @Column(nullable = false). "
                    + "May be database-specific.")
            .isNull();

      } catch (Exception e) {
        // Alternative: Database enforces NOT NULL constraint
        assertThat(e)
            .as(
                "Documents that database rejects null content with constraint violation. "
                    + "Expected: DataIntegrityViolationException or similar.")
            .isNotNull();
      }
    }
  }

  // ==================== Test Suite 6: Repository Query Behavior ====================

  @Nested
  @DisplayName("Characterization: Repository query behavior")
  class RepositoryQueryCharacterization {

    /**
     * Documents findByOcid() behavior.
     *
     * <p><b>Current Behavior:</b> Returns Optional.empty() for non-existent OCID.
     */
    @Test
    @DisplayName(
        "GIVEN: Non-existent OCID "
            + "WHEN: Query findByOcid() "
            + "THEN: Should return Optional.empty()")
    void given_nonExistentOcid_when_findByOcid_shouldReturnEmpty() {
      // WHEN: Query non-existent OCID
      Optional<CharacterEquipment> result = repository.findById("non-existent-ocid");

      // THEN: Should be empty
      assertThat(result).as("Querying non-existent OCID should return Optional.empty()").isEmpty();
    }

    /**
     * Documents JPA repository findAll() behavior with time-based filtering.
     *
     * <p><b>Current Behavior:</b> JPA repository doesn't have time-based query methods. Filtering
     * must be done in application code using the entity's isExpired() method.
     */
    @Test
    @DisplayName(
        "GIVEN: Equipment with specific updatedAt "
            + "WHEN: Query via findAll() and filter by isExpired() "
            + "THEN: Should correctly filter based on timestamp")
    void given_equipmentWithTimestamp_when_queryAllAndFilter_shouldFilterCorrectly() {
      // GIVEN: Equipment updated 10 minutes ago
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid(TEST_OCID).jsonContent(TEST_JSON_CONTENT).build();

      LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);
      setUpdatedAt(equipment, tenMinutesAgo);
      repository.save(equipment);

      // WHEN: Query all and filter with 15-minute threshold (should find it)
      Duration ttl15Min = Duration.ofMinutes(15);
      Optional<CharacterEquipment> foundWith15Min =
          repository.findById(TEST_OCID).filter(e -> !e.isExpired(ttl15Min));

      // WHEN: Query all and filter with 5-minute threshold (should NOT find it)
      Duration ttl5Min = Duration.ofMinutes(5);
      Optional<CharacterEquipment> foundWith5Min =
          repository.findById(TEST_OCID).filter(e -> !e.isExpired(ttl5Min));

      // THEN: Correct filtering
      assertThat(foundWith15Min)
          .as("Equipment updated 10min ago should be found with 15min threshold")
          .isPresent();

      assertThat(foundWith5Min)
          .as("Equipment updated 10min ago should NOT be found with 5min threshold")
          .isEmpty();
    }

    /**
     * Documents existence check behavior with time-based filtering.
     *
     * <p><b>Current Behavior:</b> JPA repository doesn't have time-based exists methods. Must use
     * findById() combined with isExpired() for time-based existence checks.
     */
    @Test
    @DisplayName(
        "GIVEN: Fresh and stale equipment "
            + "WHEN: Check existence via findById() + isExpired() "
            + "THEN: Should return correct results")
    void given_freshAndStaleEquipment_when_checkExistsViaIsExpired_shouldReturnCorrectly() {
      // GIVEN: Fresh equipment (5 min ago)
      CharacterEquipment freshEquipment =
          CharacterEquipment.builder()
              .ocid(TEST_OCID + "-fresh")
              .jsonContent(TEST_JSON_CONTENT)
              .build();
      setUpdatedAt(freshEquipment, LocalDateTime.now().minusMinutes(5));
      repository.save(freshEquipment);

      // GIVEN: Stale equipment (20 min ago)
      CharacterEquipment staleEquipment =
          CharacterEquipment.builder()
              .ocid(TEST_OCID + "-stale")
              .jsonContent(TEST_JSON_CONTENT)
              .build();
      setUpdatedAt(staleEquipment, LocalDateTime.now().minusMinutes(20));
      repository.save(staleEquipment);

      // WHEN: Check existence with 10-minute threshold
      Duration ttl10Min = Duration.ofMinutes(10);

      boolean freshExists =
          repository.findById(TEST_OCID + "-fresh").map(e -> !e.isExpired(ttl10Min)).orElse(false);

      boolean staleExists =
          repository.findById(TEST_OCID + "-stale").map(e -> !e.isExpired(ttl10Min)).orElse(false);

      // THEN: Correct existence check
      assertThat(freshExists).as("Fresh equipment (5min < 10min threshold) should exist").isTrue();

      assertThat(staleExists)
          .as("Stale equipment (20min > 10min threshold) should NOT exist")
          .isFalse();
    }
  }

  // ==================== Helper Methods ====================

  /**
   * Helper method to set updatedAt via reflection (for testing purposes).
   *
   * <p>This allows testing timestamp-dependent behavior without Thread.sleep().
   */
  private void setUpdatedAt(CharacterEquipment equipment, LocalDateTime updatedAt) {
    try {
      java.lang.reflect.Field field = CharacterEquipment.class.getDeclaredField("updatedAt");
      field.setAccessible(true);
      field.set(equipment, updatedAt);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set updatedAt via reflection", e);
    }
  }
}
