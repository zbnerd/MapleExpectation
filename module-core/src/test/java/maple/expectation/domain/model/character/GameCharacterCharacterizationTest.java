package maple.expectation.domain.model.character;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import maple.expectation.domain.model.equipment.CharacterEquipment;
import maple.expectation.domain.model.equipment.EquipmentData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Characterization Test for GameCharacter Domain Model.
 *
 * <p>This test captures the ACTUAL behavior of GameCharacter to ensure domain extraction maintains
 * equivalence. Following ADR-017-S1 pattern.
 */
@DisplayName("GameCharacter Domain Model - Characterization Test")
class GameCharacterCharacterizationTest {

  // ==================== Test Suite 1: Creation & Factory Methods ====================

  @Nested
  @DisplayName("Factory Methods")
  class FactoryMethods {

    @Test
    @DisplayName("create() should create new character with zero likes and current timestamp")
    void create_shouldInitializeWithDefaults() {
      CharacterId characterId = new CharacterId("test-ocid-123");
      UserIgn userIgn = new UserIgn("TestPlayer");

      GameCharacter character = GameCharacter.create(characterId, userIgn);

      assertAll(
          "character",
          () -> assertNull(character.getId(), "ID should be null for new character"),
          () -> assertEquals(characterId, character.getCharacterId(), "CharacterId should match"),
          () -> assertEquals(userIgn, character.getUserIgn(), "UserIgn should match"),
          () -> assertEquals(0L, character.getLikeCount(), "LikeCount should start at 0"),
          () -> assertNull(character.getVersion(), "Version should be null for new character"),
          () ->
              assertNotNull(
                  character.getUpdatedAt(), "UpdatedAt should be initialized to current time"));
    }

    @Test
    @DisplayName("restore() should reconstitute character from persistence")
    void restore_shouldReconstituteFromPersistence() {
      CharacterId characterId = new CharacterId("test-ocid-456");
      UserIgn userIgn = new UserIgn("TestPlayer2");
      LocalDateTime past = LocalDateTime.now().minusDays(1);

      GameCharacter character =
          GameCharacter.restore(
              1L, characterId, userIgn, null, "Scania", "NightLord", null, past, 5L, 2L, past);

      assertAll(
          "character",
          () -> assertEquals(1L, character.getId()),
          () -> assertEquals(characterId, character.getCharacterId()),
          () -> assertEquals(userIgn, character.getUserIgn()),
          () -> assertEquals("Scania", character.getWorldName()),
          () -> assertEquals("NightLord", character.getCharacterClass()),
          () -> assertEquals(past, character.getBasicInfoUpdatedAt()),
          () -> assertEquals(5L, character.getLikeCount()),
          () -> assertEquals(2L, character.getVersion()),
          () -> assertEquals(past, character.getUpdatedAt()));
    }
  }

  // ==================== Test Suite 2: Business Logic - Activity ====================

  @Nested
  @DisplayName("Activity Status")
  class ActivityStatus {

    @Test
    @DisplayName("isActive() should return true for character updated within 30 days")
    void isActive_shouldReturnTrueForRecentUpdate() {
      GameCharacter character = createCharacterWithUpdatedAt(LocalDateTime.now().minusDays(15));

      assertTrue(character.isActive(), "Character updated 15 days ago should be active");
    }

    @Test
    @DisplayName("isActive() should return true for character updated today")
    void isActive_shouldReturnTrueForToday() {
      GameCharacter character = createCharacterWithUpdatedAt(LocalDateTime.now());

      assertTrue(character.isActive(), "Character updated today should be active");
    }

    @Test
    @DisplayName("isActive() should return false for character updated 31 days ago")
    void isActive_shouldReturnFalseForOldUpdate() {
      GameCharacter character = createCharacterWithUpdatedAt(LocalDateTime.now().minusDays(31));

      assertFalse(character.isActive(), "Character updated 31 days ago should not be active");
    }

    @Test
    @DisplayName("isActive() should return false for character updated exactly 30 days ago")
    void isActive_shouldReturnFalseForThresholdBoundary() {
      GameCharacter character =
          createCharacterWithUpdatedAt(LocalDateTime.now().minusDays(30).minusSeconds(1));

      assertFalse(character.isActive(), "Character updated >30 days ago should not be active");
    }

    @Test
    @DisplayName("isActive() should return true for character updated 29 days ago")
    void isActive_shouldReturnTrueJustInsideThreshold() {
      GameCharacter character = createCharacterWithUpdatedAt(LocalDateTime.now().minusDays(29));

      assertTrue(character.isActive(), "Character updated 29 days ago should be active");
    }
  }

  // ==================== Test Suite 3: Business Logic - Freshness ====================

  @Nested
  @DisplayName("Basic Info Freshness")
  class BasicInfoFreshness {

    @Test
    @DisplayName("needsBasicInfoRefresh() should return true when worldName is null")
    void needsBasicInfoRefresh_shouldReturnTrueWhenWorldNameIsNull() {
      GameCharacter character = createCharacterWithBasicInfo(null, null, LocalDateTime.now());

      assertTrue(character.needsBasicInfoRefresh(), "Null worldName should trigger refresh needed");
    }

    @Test
    @DisplayName("needsBasicInfoRefresh() should return true when basicInfoUpdatedAt is null")
    void needsBasicInfoRefresh_shouldReturnTrueWhenTimestampIsNull() {
      GameCharacter character = createCharacterWithBasicInfo("Scania", "NightLord", null);

      assertTrue(
          character.needsBasicInfoRefresh(), "Null basicInfoUpdatedAt should trigger refresh");
    }

    @Test
    @DisplayName("needsBasicInfoRefresh() should return true when basic info is 16 minutes old")
    void needsBasicInfoRefresh_shouldReturnTrueWhenStale() {
      LocalDateTime oldTimestamp = LocalDateTime.now().minusMinutes(16);
      GameCharacter character = createCharacterWithBasicInfo("Scania", "NightLord", oldTimestamp);

      assertTrue(
          character.needsBasicInfoRefresh(), "Basic info older than 15 min should trigger refresh");
    }

    @Test
    @DisplayName("needsBasicInfoRefresh() should return false when basic info is fresh")
    void needsBasicInfoRefresh_shouldReturnFalseWhenFresh() {
      LocalDateTime freshTimestamp = LocalDateTime.now().minusMinutes(5);
      GameCharacter character = createCharacterWithBasicInfo("Scania", "NightLord", freshTimestamp);

      assertFalse(character.needsBasicInfoRefresh(), "Fresh basic info should not trigger refresh");
    }

    @Test
    @DisplayName("needsBasicInfoRefresh() should return false at 15 minute boundary")
    void needsBasicInfoRefresh_shouldReturnFalseAtBoundary() {
      LocalDateTime boundaryTimestamp = LocalDateTime.now().minusMinutes(15);
      GameCharacter character =
          createCharacterWithBasicInfo("Scania", "NightLord", boundaryTimestamp);

      assertFalse(
          character.needsBasicInfoRefresh(), "Basic info at 15 min boundary should be fresh");
    }
  }

  // ==================== Test Suite 4: State Transitions ====================

  @Nested
  @DisplayName("State Transitions")
  class StateTransitions {

    @Test
    @DisplayName("incrementLikeCount() should increment like count")
    void incrementLikeCount_shouldIncrement() {
      GameCharacter original = createCharacterWithLikeCount(5L);
      GameCharacter updated = original.incrementLikeCount();

      assertEquals(6L, updated.getLikeCount(), "Like count should increment by 1");
    }

    @Test
    @DisplayName("incrementLikeCount() should update timestamp")
    void incrementLikeCount_shouldUpdateTimestamp() {
      LocalDateTime past = LocalDateTime.now().minusMinutes(5);
      GameCharacter original = createCharacterWithUpdatedAt(past);

      Thread.holdsFor(10); // Small delay

      GameCharacter updated = original.incrementLikeCount();

      assertTrue(
          updated.getUpdatedAt().isAfter(original.getUpdatedAt()), "UpdatedAt should be advanced");
    }

    @Test
    @DisplayName("updateBasicInfo() should update all basic info fields")
    void updateBasicInfo_shouldUpdateAllFields() {
      GameCharacter original =
          createCharacterWithBasicInfo("Scania", "NightLord", LocalDateTime.now());

      GameCharacter updated = original.updateBasicInfo("Arcane", "Bishop", "http://new-image.url");

      assertAll(
          "updated character",
          () -> assertEquals("Arcane", updated.getWorldName()),
          () -> assertEquals("Bishop", updated.getCharacterClass()),
          () -> assertEquals("http://new-image.url", updated.getCharacterImage()),
          () -> assertNotNull(updated.getBasicInfoUpdatedAt(), "basicInfoUpdatedAt should be set"),
          () ->
              assertTrue(
                  updated.getUpdatedAt().isAfter(original.getUpdatedAt()),
                  "UpdatedAt should be advanced"));
    }

    @Test
    @DisplayName("updateEquipment() should update equipment")
    void updateEquipment_shouldUpdateEquipment() {
      GameCharacter original = createCharacterWithEquipment(null);
      CharacterEquipment equipment =
          CharacterEquipment.create(
              new CharacterId("test-ocid"), EquipmentData.of("{\"test\": \"data\"}"));

      GameCharacter updated = original.updateEquipment(equipment);

      assertNotNull(updated.getEquipment(), "Equipment should be set");
      assertEquals(equipment, updated.getEquipment());
    }
  }

  // ==================== Test Suite 5: Validation ====================

  @Nested
  @DisplayName("Validation")
  class Validation {

    @Test
    @DisplayName("validateOcid() should pass for valid OCID")
    void validateOcid_shouldPassForValidOcid() {
      GameCharacter character =
          GameCharacter.create(new CharacterId("valid-ocid"), new UserIgn("Player"));

      assertDoesNotThrow(() -> character.validateOcid(), "Valid OCID should not throw");
    }

    @Test
    @DisplayName("validateOcid() should throw for blank OCID")
    void validateOcid_shouldThrowForBlankOcid() {
      CharacterId blankId = new CharacterId("   "); // Will be rejected by CharacterId

      assertThrows(
          IllegalArgumentException.class,
          () -> new CharacterId(""),
          "Blank OCID should throw IllegalArgumentException");
    }
  }

  // ==================== Test Suite 6: Equality & HashCode ====================

  @Nested
  @DisplayName("Equality & HashCode")
  class EqualityAndHashCode {

    @Test
    @DisplayName("equals() should return true for same CharacterId")
    void equals_shouldReturnTrueForSameCharacterId() {
      CharacterId id = new CharacterId("same-ocid");
      GameCharacter c1 = GameCharacter.create(id, new UserIgn("Player1"));
      GameCharacter c2 = GameCharacter.create(id, new UserIgn("Player2"));

      assertEquals(c1, c2, "Characters with same CharacterId should be equal");
      assertEquals(c1.hashCode(), c2.hashCode(), "Hash codes should be equal");
    }

    @Test
    @DisplayName("equals() should return false for different CharacterId")
    void equals_shouldReturnFalseForDifferentCharacterId() {
      GameCharacter c1 = GameCharacter.create(new CharacterId("ocid-1"), new UserIgn("Player1"));
      GameCharacter c2 = GameCharacter.create(new CharacterId("ocid-2"), new UserIgn("Player1"));

      assertNotEquals(c1, c2, "Characters with different CharacterId should not be equal");
    }

    @Test
    @DisplayName("equals() should return false for null")
    void equals_shouldReturnFalseForNull() {
      GameCharacter character =
          GameCharacter.create(new CharacterId("ocid-1"), new UserIgn("Player1"));

      assertNotEquals(null, character, "Character should not equal null");
    }

    @Test
    @DisplayName("equals() should return false for different type")
    void equals_shouldReturnFalseForDifferentType() {
      GameCharacter character =
          GameCharacter.create(new CharacterId("ocid-1"), new UserIgn("Player1"));

      assertNotEquals("ocid-1", character, "Character should not equal String");
    }
  }

  // ==================== Test Suite 7: toString ====================

  @Nested
  @DisplayName("String Representation")
  class StringRepresentation {

    @Test
    @DisplayName("toString() should include key fields")
    void toString_shouldIncludeKeyFields() {
      CharacterId id = new CharacterId("test-ocid");
      UserIgn ign = new UserIgn("TestPlayer");
      GameCharacter character = GameCharacter.create(id, ign);

      String result = character.toString();

      assertAll(
          "toString",
          () -> assertTrue(result.contains("test-ocid"), "Should contain OCID"),
          () -> assertTrue(result.contains("TestPlayer"), "Should contain IGN"),
          () -> assertTrue(result.contains("likeCount=0"), "Should contain likeCount"),
          () -> assertTrue(result.contains("isActive=true"), "Should contain active status"),
          () -> assertTrue(result.contains("needsRefresh=true"), "Should contain refresh status"));
    }
  }

  // ==================== Helper Methods ====================

  private GameCharacter createCharacterWithUpdatedAt(LocalDateTime updatedAt) {
    CharacterId id = new CharacterId("test-ocid");
    UserIgn ign = new UserIgn("TestPlayer");
    return GameCharacter.restore(1L, id, ign, null, null, null, null, null, 0L, 1L, updatedAt);
  }

  private GameCharacter createCharacterWithLikeCount(Long likeCount) {
    CharacterId id = new CharacterId("test-ocid");
    UserIgn ign = new UserIgn("TestPlayer");
    return GameCharacter.restore(
        1L, id, ign, null, null, null, null, null, likeCount, 1L, LocalDateTime.now());
  }

  private GameCharacter createCharacterWithBasicInfo(
      String worldName, String characterClass, LocalDateTime basicInfoUpdatedAt) {
    CharacterId id = new CharacterId("test-ocid");
    UserIgn ign = new UserIgn("TestPlayer");
    return GameCharacter.restore(
        1L,
        id,
        ign,
        null,
        worldName,
        characterClass,
        null,
        basicInfoUpdatedAt,
        0L,
        1L,
        LocalDateTime.now());
  }

  private GameCharacter createCharacterWithEquipment(CharacterEquipment equipment) {
    CharacterId id = new CharacterId("test-ocid");
    UserIgn ign = new UserIgn("TestPlayer");
    return GameCharacter.restore(
        1L, id, ign, equipment, null, null, null, null, 0L, 1L, LocalDateTime.now());
  }
}
