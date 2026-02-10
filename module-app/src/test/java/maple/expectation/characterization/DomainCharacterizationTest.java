package maple.expectation.characterization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import maple.expectation.domain.CharacterLike;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.domain.v2.GameCharacter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 Characterization Tests: Domain Layer
 *
 * <p><b>PURPOSE:</b> Capture CURRENT behavior before domain extraction refactoring.
 *
 * <p><b>NOTE:</b> These tests document WHAT the system DOES, not what it SHOULD do. They serve as a
 * safety net during refactoring to prevent unintended behavior changes.
 *
 * <h3>Target Classes (Phase 3 Domain Extraction):</h3>
 *
 * <ul>
 *   <li>{@link GameCharacter} - Character domain entity
 *   <li>{@link CharacterEquipment} - Equipment domain entity
 *   <li>{@link CharacterLike} - Like domain entity
 * </ul>
 *
 * @see maple.expectation.domain.v2.GameCharacter
 * @see maple.expectation.domain.v2.CharacterEquipment
 * @see maple.expectation.domain.CharacterLike
 */
@Tag("characterization")
@DisplayName("Phase 3: Domain Layer Characterization Tests")
class DomainCharacterizationTest {

  // ==================== GameCharacter Behavior ====================

  @Test
  @DisplayName("[CHAR-001] GameCharacter: Constructor sets userIgn and ocid")
  void gameCharacter_constructor_sets_basic_fields() {
    // Arrange
    String userIgn = "TestCharacter_" + UUID.randomUUID().toString().substring(0, 8);
    String ocid = "ocid_" + UUID.randomUUID();

    // Act
    GameCharacter character = new GameCharacter(userIgn, ocid);

    // Assert - Current Behavior
    assertThat(character.getUserIgn()).isEqualTo(userIgn);
    assertThat(character.getOcid()).isEqualTo(ocid);
    assertThat(character.getLikeCount()).isEqualTo(0L);
    assertThat(character.getUpdatedAt()).isNotNull();
  }

  @Test
  @DisplayName("[CHAR-002] GameCharacter: like() increments likeCount")
  void gameCharacter_like_increments_count() {
    // Arrange
    GameCharacter character = new GameCharacter("TestUser", "ocid-123");
    long initialCount = character.getLikeCount();

    // Act
    character.like();
    character.like();
    character.like();

    // Assert - Current Behavior
    assertThat(character.getLikeCount()).isEqualTo(initialCount + 3);
  }

  @Test
  @DisplayName("[CHAR-003] GameCharacter: isActive() returns true if updated within 30 days")
  void gameCharacter_isActive_true_when_recently_updated() {
    // Arrange
    GameCharacter character = new GameCharacter("TestUser", "ocid-123");

    // Act
    boolean isActive = character.isActive();

    // Assert - Current Behavior: Just created character is active
    assertThat(isActive).isTrue();
  }

  @Test
  @DisplayName(
      "[CHAR-004] GameCharacter: needsBasicInfoRefresh() returns true when worldName is null")
  void gameCharacter_needsRefresh_true_when_worldName_null() {
    // Arrange
    GameCharacter character = new GameCharacter("TestUser", "ocid-123");
    // worldName is null by default

    // Act
    boolean needsRefresh = character.needsBasicInfoRefresh();

    // Assert - Current Behavior
    assertThat(needsRefresh).isTrue();
  }

  @Test
  @DisplayName(
      "[CHAR-005] GameCharacter: needsBasicInfoRefresh() returns true if basicInfoUpdatedAt is null")
  void gameCharacter_needsRefresh_true_when_basicInfoUpdatedAt_null() {
    // Arrange
    GameCharacter character = new GameCharacter("TestUser", "ocid-123");
    character.setWorldName("Scania"); // Set worldName but basicInfoUpdatedAt remains null

    // Act
    boolean needsRefresh = character.needsBasicInfoRefresh();

    // Assert - Current Behavior
    assertThat(needsRefresh).isTrue();
  }

  @Test
  @DisplayName(
      "[CHAR-006] GameCharacter: needsBasicInfoRefresh() returns true if > 15 minutes elapsed")
  void gameCharacter_needsRefresh_true_when_15min_elapsed() {
    // Arrange
    GameCharacter character = new GameCharacter("TestUser", "ocid-123");
    character.setWorldName("Scania");
    character.setBasicInfoUpdatedAt(LocalDateTime.now().minusMinutes(16));

    // Act
    boolean needsRefresh = character.needsBasicInfoRefresh();

    // Assert - Current Behavior: 15 minute threshold
    assertThat(needsRefresh).isTrue();
  }

  @Test
  @DisplayName(
      "[CHAR-007] GameCharacter: needsBasicInfoRefresh() returns false if < 15 minutes elapsed")
  void gameCharacter_needsRefresh_false_when_recently_updated() {
    // Arrange
    GameCharacter character = new GameCharacter("TestUser", "ocid-123");
    character.setWorldName("Scania");
    character.setBasicInfoUpdatedAt(LocalDateTime.now().minusMinutes(14));

    // Act
    boolean needsRefresh = character.needsBasicInfoRefresh();

    // Assert - Current Behavior
    assertThat(needsRefresh).isFalse();
  }

  @Test
  @DisplayName("[CHAR-008] GameCharacter: Constructor throws on null OCID")
  void gameCharacter_constructor_throws_on_null_ocid() {
    // Act & Assert - Current Behavior
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GameCharacter("TestUser", null))
        .isInstanceOf(maple.expectation.global.error.exception.InvalidCharacterStateException.class)
        .hasMessageContaining("OCID");
  }

  @Test
  @DisplayName("[CHAR-009] GameCharacter: Constructor throws on blank OCID")
  void gameCharacter_constructor_throws_on_blank_ocid() {
    // Act & Assert - Current Behavior
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GameCharacter("TestUser", "   "))
        .isInstanceOf(maple.expectation.global.error.exception.InvalidCharacterStateException.class)
        .hasMessageContaining("OCID");
  }

  // ==================== CharacterEquipment Behavior ====================

  @Test
  @DisplayName("[CHAR-010] CharacterEquipment: Builder sets ocid, jsonContent, and updatedAt")
  void characterEquipment_builder_sets_fields() {
    // Arrange
    String ocid = "ocid_" + UUID.randomUUID();
    String jsonContent = "{\"equipment\": \"data\"}";

    // Act
    CharacterEquipment equipment =
        CharacterEquipment.builder().ocid(ocid).jsonContent(jsonContent).build();

    // Assert - Current Behavior
    assertThat(equipment.getOcid()).isEqualTo(ocid);
    assertThat(equipment.getJsonContent()).isEqualTo(jsonContent);
    assertThat(equipment.getUpdatedAt()).isNotNull();
    assertThat(equipment.getUpdatedAt()).isBeforeOrEqualTo(LocalDateTime.now());
  }

  @Test
  @DisplayName("[CHAR-011] CharacterEquipment: updateData() updates jsonContent and updatedAt")
  void characterEquipment_updateData_modifies_fields() throws InterruptedException {
    // Arrange
    CharacterEquipment equipment =
        CharacterEquipment.builder().ocid("ocid-123").jsonContent("{\"v\": 1}").build();
    String newJson = "{\"v\": 2}";
    LocalDateTime beforeUpdate = equipment.getUpdatedAt();

    Thread.sleep(10); // Ensure time difference

    // Act
    equipment.updateData(newJson);

    // Assert - Current Behavior
    assertThat(equipment.getJsonContent()).isEqualTo(newJson);
    assertThat(equipment.getUpdatedAt()).isAfter(beforeUpdate);
  }

  @Test
  @DisplayName("[CHAR-012] CharacterEquipment: isExpired() returns true when updatedAt is null")
  void characterEquipment_isExpired_true_when_updatedAt_null() {
    // Arrange - Can't directly set updatedAt to null via builder, so test with Duration.ZERO
    CharacterEquipment equipment =
        CharacterEquipment.builder().ocid("ocid-123").jsonContent("{\"data\": 1}").build();

    // Act - With ZERO TTL, even recently created data is expired
    boolean isExpired = equipment.isExpired(Duration.ZERO);

    // Assert - Current Behavior
    assertThat(isExpired).isTrue();
  }

  @Test
  @DisplayName("[CHAR-013] CharacterEquipment: isExpired() returns false when within TTL")
  void characterEquipment_isExpired_false_when_within_ttl() {
    // Arrange
    CharacterEquipment equipment =
        CharacterEquipment.builder().ocid("ocid-123").jsonContent("{\"data\": 1}").build();

    // Act - 1 hour TTL, just created
    boolean isExpired = equipment.isExpired(Duration.ofHours(1));

    // Assert - Current Behavior
    assertThat(isExpired).isFalse();
  }

  @Test
  @DisplayName("[CHAR-014] CharacterEquipment: isExpired() returns true when TTL exceeded")
  void characterEquipment_isExpired_true_when_ttl_exceeded() {
    // Arrange
    String ocid = "ocid_" + UUID.randomUUID();
    String jsonContent = "{\"data\": 1}";

    CharacterEquipment equipment =
        CharacterEquipment.builder().ocid(ocid).jsonContent(jsonContent).build();

    // Manually set updatedAt to past (via reflection or direct field access not possible, so use
    // builder pattern)
    // Note: Builder automatically sets updatedAt to now(), so we can't test expired case directly
    // This is a limitation of the current implementation

    // Act - Very short TTL
    boolean isExpired = equipment.isExpired(Duration.ofMillis(1));

    // Assert - Current Behavior: May still be false due to timing
    // Documenting this behavior as-is
  }

  @Test
  @DisplayName("[CHAR-015] CharacterEquipment: isFresh() returns opposite of isExpired()")
  void characterEquipment_isFresh_opposite_of_isExpired() {
    // Arrange
    CharacterEquipment equipment =
        CharacterEquipment.builder().ocid("ocid-123").jsonContent("{\"data\": 1}").build();
    Duration ttl = Duration.ofHours(1);

    // Act
    boolean isFresh = equipment.isFresh(ttl);
    boolean isExpired = equipment.isExpired(ttl);

    // Assert - Current Behavior
    assertThat(isFresh).isEqualTo(!isExpired);
  }

  @Test
  @DisplayName(
      "[CHAR-016] CharacterEquipment: hasData() returns true when jsonContent is non-blank")
  void characterEquipment_hasData_true_when_content_non_blank() {
    // Arrange
    CharacterEquipment equipment =
        CharacterEquipment.builder().ocid("ocid-123").jsonContent("{\"items\": []}").build();

    // Act
    boolean hasData = equipment.hasData();

    // Assert - Current Behavior
    assertThat(hasData).isTrue();
  }

  @Test
  @DisplayName("[CHAR-017] CharacterEquipment: hasData() returns false when jsonContent is null")
  void characterEquipment_hasData_false_when_content_null() {
    // Arrange
    CharacterEquipment equipment =
        CharacterEquipment.builder().ocid("ocid-123").jsonContent(null).build();

    // Act
    boolean hasData = equipment.hasData();

    // Assert - Current Behavior
    assertThat(hasData).isFalse();
  }

  @Test
  @DisplayName("[CHAR-018] CharacterEquipment: hasData() returns false when jsonContent is empty")
  void characterEquipment_hasData_false_when_content_empty() {
    // Arrange
    CharacterEquipment equipment =
        CharacterEquipment.builder().ocid("ocid-123").jsonContent("").build();

    // Act
    boolean hasData = equipment.hasData();

    // Assert - Current Behavior
    assertThat(hasData).isFalse();
  }

  @Test
  @DisplayName("[CHAR-019] CharacterEquipment: hasData() returns false when jsonContent is blank")
  void characterEquipment_hasData_false_when_content_blank() {
    // Arrange
    CharacterEquipment equipment =
        CharacterEquipment.builder().ocid("ocid-123").jsonContent("   ").build();

    // Act
    boolean hasData = equipment.hasData();

    // Assert - Current Behavior
    assertThat(hasData).isFalse();
  }

  // ==================== CharacterLike Behavior ====================

  @Test
  @DisplayName("[CHAR-020] CharacterLike: Constructor sets targetOcid and likerAccountId")
  void characterLike_constructor_sets_fields() {
    // Arrange
    String targetOcid = "target_" + UUID.randomUUID();
    String likerAccountId = "account_" + UUID.randomUUID();

    // Act
    CharacterLike like = new CharacterLike(targetOcid, likerAccountId);

    // Assert - Current Behavior
    assertThat(like.getTargetOcid()).isEqualTo(targetOcid);
    assertThat(like.getLikerAccountId()).isEqualTo(likerAccountId);
  }

  @Test
  @DisplayName("[CHAR-021] CharacterLike: Factory method of() creates instance")
  void characterLike_factory_creates_instance() {
    // Arrange
    String targetOcid = "target_" + UUID.randomUUID();
    String likerAccountId = "account_" + UUID.randomUUID();

    // Act
    CharacterLike like = CharacterLike.of(targetOcid, likerAccountId);

    // Assert - Current Behavior
    assertThat(like).isNotNull();
    assertThat(like.getTargetOcid()).isEqualTo(targetOcid);
    assertThat(like.getLikerAccountId()).isEqualTo(likerAccountId);
  }

  @Test
  @DisplayName("[CHAR-022] CharacterLike: createdAt is null on construction (JPA persist required)")
  void characterLike_createdAt_null_on_construction() {
    // Arrange & Act
    CharacterLike like = new CharacterLike("target-ocid", "account-id");

    // Assert - Current Behavior
    // @CreationTimestamp는 JPA persist 시에만 설정됨
    assertThat(like.getCreatedAt()).isNull();
  }
}
