package maple.expectation.domain.model.like;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Characterization Test for CharacterLike Domain Model.
 *
 * <p>This test captures the ACTUAL behavior of CharacterLike to ensure domain extraction maintains
 * equivalence. Following ADR-017-S1 pattern.
 */
@DisplayName("CharacterLike Domain Model - Characterization Test")
class CharacterLikeCharacterizationTest {

  // ==================== Test Suite 1: Creation & Factory Methods ====================

  @Nested
  @DisplayName("Factory Methods")
  class FactoryMethods {

    @Test
    @DisplayName("create() should create new like with current timestamp")
    void create_shouldInitializeWithCurrentTimestamp() {
      CharacterId targetOcid = new CharacterId("target-ocid-123");
      String likerAccountId = "user-account-456";

      CharacterLike like = CharacterLike.create(targetOcid, likerAccountId);

      assertAll(
          "like",
          () -> assertNull(like.id(), "LikeId should be null for new like"),
          () -> assertEquals(targetOcid.value(), like.targetOcid(), "Target OCID should match"),
          () -> assertEquals(likerAccountId, like.likerAccountId(), "LikerAccountId should match"),
          () -> assertNotNull(like.createdAt(), "CreatedAt should be initialized to current time"));
    }

    @Test
    @DisplayName("restore() should reconstitute like from persistence")
    void restore_shouldReconstituteFromPersistence() {
      String targetOcid = "target-ocid-789";
      String likerAccountId = "user-account-012";
      Long likeId = 42L;
      LocalDateTime past = LocalDateTime.now().minusDays(1);

      CharacterLike like = CharacterLike.restore(likeId, targetOcid, likerAccountId, past);

      assertAll(
          "like",
          () -> assertEquals(likeId, like.id()),
          () -> assertEquals(targetOcid, like.targetOcid()),
          () -> assertEquals(likerAccountId, like.likerAccountId()),
          () -> assertEquals(past, like.createdAt()));
    }

    @Test
    @DisplayName("of() should create new like from strings")
    void of_shouldCreateFromStrings() {
      String targetOcid = "target-ocid-345";
      String likerAccountId = "user-account-678";

      CharacterLike like = CharacterLike.of(targetOcid, likerAccountId);

      assertAll(
          "like",
          () -> assertEquals(targetOcid, like.targetOcid()),
          () -> assertEquals(likerAccountId, like.likerAccountId()),
          () -> assertNotNull(like.createdAt()));
    }
  }

  // ==================== Test Suite 2: Business Logic - Self-Like Detection ====================

  @Nested
  @DisplayName("Self-Like Detection")
  class SelfLikeDetection {

    @Test
    @DisplayName("isSelfLike() should return true when OCID equals account ID")
    void isSelfLike_shouldReturnTrueForSelfLike() {
      String sameId = "same-ocid-account";
      CharacterLike like = CharacterLike.create(sameId, sameId);

      assertTrue(like.isSelfLike(), "Should detect self-like");
    }

    @Test
    @DisplayName("isSelfLike() should return false when OCID differs from account ID")
    void isSelfLike_shouldReturnFalseForNormalLike() {
      CharacterLike like = CharacterLike.create("target-ocid", "user-account");

      assertFalse(like.isSelfLike(), "Should not detect self-like for different IDs");
    }
  }

  // ==================== Test Suite 3: Equality & HashCode ====================

  @Nested
  @DisplayName("Equality & HashCode")
  class EqualityAndHashCode {

    @Test
    @DisplayName("equals() should return true for same (targetOcid, likerAccountId)")
    void equals_shouldReturnTrueForSamePair() {
      String targetOcid = "same-target";
      String likerAccountId = "same-liker";
      CharacterLike like1 = CharacterLike.create(targetOcid, likerAccountId);
      CharacterLike like2 = CharacterLike.create(targetOcid, likerAccountId);

      assertEquals(like1, like2, "Likes with same target and liker should be equal");
      assertEquals(like1.hashCode(), like2.hashCode(), "Hash codes should be equal");
    }

    @Test
    @DisplayName("equals() should return false for different target")
    void equals_shouldReturnFalseForDifferentTarget() {
      String likerAccountId = "same-liker";
      CharacterLike like1 = CharacterLike.create("target-1", likerAccountId);
      CharacterLike like2 = CharacterLike.create("target-2", likerAccountId);

      assertNotEquals(like1, like2, "Likes with different targets should not be equal");
    }

    @Test
    @DisplayName("equals() should return false for different liker")
    void equals_shouldReturnFalseForDifferentLiker() {
      String targetOcid = "same-target";
      CharacterLike like1 = CharacterLike.create(targetOcid, "liker-1");
      CharacterLike like2 = CharacterLike.create(targetOcid, "liker-2");

      assertNotEquals(like1, like2, "Likes with different likers should not be equal");
    }

    @Test
    @DisplayName("equals() should return false for null")
    void equals_shouldReturnFalseForNull() {
      CharacterLike like = CharacterLike.create("target", "liker");

      assertNotEquals(null, like, "Like should not equal null");
    }

    @Test
    @DisplayName("equals() should return false for different type")
    void equals_shouldReturnFalseForDifferentType() {
      CharacterLike like = CharacterLike.create("target", "liker");

      assertNotEquals("target", like, "Like should not equal String");
    }
  }

  // ==================== Test Suite 4: toString ====================

  @Nested
  @DisplayName("String Representation")
  class StringRepresentation {

    @Test
    @DisplayName("toString() should include key fields")
    void toString_shouldIncludeKeyFields() {
      String targetOcid = "target-ocid-123";
      String likerAccountId = "user-account-456";
      CharacterLike like = CharacterLike.create(targetOcid, likerAccountId);

      String result = like.toString();

      assertTrue(result.contains("target-ocid-123"), "Should contain target OCID");
      assertTrue(result.contains("user-account-456"), "Should contain liker account ID");
      assertTrue(result.contains("createdAt="), "Should contain createdAt");
      assertTrue(result.contains("isSelfLike=false"), "Should contain self-like status");
    }
  }

  // ==================== Test Suite 5: Value Object Validation ====================

  @Nested
  @DisplayName("LikeId Value Object")
  class LikeIdValueObject {

    @Test
    @DisplayName("LikeId should accept positive ID")
    void LikeId_shouldAcceptPositiveId() {
      LikeId likeId = new LikeId(1L);

      assertEquals(1L, likeId.value());
    }

    @Test
    @DisplayName("LikeId should throw for null ID")
    void LikeId_shouldThrowForNullId() {
      assertThrows(
          IllegalArgumentException.class, () -> new LikeId(null), "LikeId should throw for null");
    }

    @Test
    @DisplayName("LikeId should throw for zero ID")
    void LikeId_shouldThrowForZeroId() {
      assertThrows(
          IllegalArgumentException.class, () -> new LikeId(0L), "LikeId should throw for zero");
    }

    @Test
    @DisplayName("LikeId should throw for negative ID")
    void LikeId_shouldThrowForNegativeId() {
      assertThrows(
          IllegalArgumentException.class,
          () -> new LikeId(-1L),
          "LikeId should throw for negative");
    }
  }
}
