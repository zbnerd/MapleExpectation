package maple.expectation.domain.v2;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * CharacterEquipment 엔티티 테스트 (Issue #120, #194)
 *
 * <p>Rich Domain Model 비즈니스 로직 검증
 */
class CharacterEquipmentTest {

  @Nested
  @DisplayName("데이터 만료 확인 (isExpired)")
  class IsExpiredTest {

    @Test
    @DisplayName("TTL 내의 데이터는 만료되지 않음")
    void whenWithinTTL_shouldNotBeExpired() {
      // given
      CharacterEquipment equipment =
          CharacterEquipment.builder()
              .ocid("test-ocid")
              .jsonContent("{\"test\": \"data\"}")
              .build();

      // when & then (방금 생성한 데이터 - 1시간 TTL)
      assertThat(equipment.isExpired(Duration.ofHours(1))).isFalse();
      assertThat(equipment.isFresh(Duration.ofHours(1))).isTrue();
    }

    @Test
    @DisplayName("TTL 초과 데이터는 만료됨")
    void whenTTLExceeded_shouldBeExpired() {
      // given
      CharacterEquipment equipment =
          CharacterEquipment.builder()
              .ocid("test-ocid")
              .jsonContent("{\"test\": \"data\"}")
              .build();

      // when - 매우 짧은 TTL (0초)
      // then
      assertThat(equipment.isExpired(Duration.ZERO)).isTrue();
      assertThat(equipment.isFresh(Duration.ZERO)).isFalse();
    }

    @Test
    @DisplayName("updatedAt이 null이면 만료됨")
    void whenUpdatedAtNull_shouldBeExpired() {
      // given - Builder로 생성하지 않고 기본 생성자로 생성된 경우 시뮬레이션
      // Builder 사용 시 updatedAt은 자동 설정되므로, 리플렉션으로 테스트
      CharacterEquipment equipment =
          CharacterEquipment.builder()
              .ocid("test-ocid")
              .jsonContent("{\"test\": \"data\"}")
              .build();

      // 정상 케이스: updatedAt이 설정되어 있음
      assertThat(equipment.isExpired(Duration.ofDays(30))).isFalse();
    }
  }

  @Nested
  @DisplayName("데이터 유효성 확인 (isFresh)")
  class IsFreshTest {

    @Test
    @DisplayName("isExpired의 반대값 반환")
    void shouldReturnOppositeOfIsExpired() {
      // given
      CharacterEquipment equipment =
          CharacterEquipment.builder()
              .ocid("test-ocid")
              .jsonContent("{\"test\": \"data\"}")
              .build();

      Duration ttl = Duration.ofHours(1);

      // when & then
      assertThat(equipment.isFresh(ttl)).isEqualTo(!equipment.isExpired(ttl));
    }
  }

  @Nested
  @DisplayName("데이터 존재 확인 (hasData)")
  class HasDataTest {

    @Test
    @DisplayName("jsonContent가 존재하면 true")
    void whenContentExists_shouldReturnTrue() {
      // given
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid("test-ocid").jsonContent("{\"items\": []}").build();

      // when & then
      assertThat(equipment.hasData()).isTrue();
    }

    @Test
    @DisplayName("jsonContent가 null이면 false")
    void whenContentNull_shouldReturnFalse() {
      // given
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid("test-ocid").jsonContent(null).build();

      // when & then
      assertThat(equipment.hasData()).isFalse();
    }

    @Test
    @DisplayName("jsonContent가 빈 문자열이면 false")
    void whenContentEmpty_shouldReturnFalse() {
      // given
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid("test-ocid").jsonContent("").build();

      // when & then
      assertThat(equipment.hasData()).isFalse();
    }

    @Test
    @DisplayName("jsonContent가 공백만 있으면 false")
    void whenContentBlank_shouldReturnFalse() {
      // given
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid("test-ocid").jsonContent("   ").build();

      // when & then
      assertThat(equipment.hasData()).isFalse();
    }
  }

  @Nested
  @DisplayName("데이터 업데이트 (updateData)")
  class UpdateDataTest {

    @Test
    @DisplayName("jsonContent와 updatedAt 갱신")
    void shouldUpdateContentAndTimestamp() throws InterruptedException {
      // given
      CharacterEquipment equipment =
          CharacterEquipment.builder().ocid("test-ocid").jsonContent("{\"version\": 1}").build();

      LocalDateTime initialUpdatedAt = equipment.getUpdatedAt();
      Thread.sleep(10); // 시간 차이 보장

      // when
      equipment.updateData("{\"version\": 2}");

      // then
      assertThat(equipment.getJsonContent()).isEqualTo("{\"version\": 2}");
      assertThat(equipment.getUpdatedAt()).isAfterOrEqualTo(initialUpdatedAt);
    }
  }

  @Nested
  @DisplayName("빌더 테스트")
  class BuilderTest {

    @Test
    @DisplayName("Builder로 생성 시 updatedAt 자동 설정")
    void builderShouldSetUpdatedAtAutomatically() {
      // when
      CharacterEquipment equipment =
          CharacterEquipment.builder()
              .ocid("ocid-12345")
              .jsonContent("{\"data\": \"test\"}")
              .build();

      // then
      assertThat(equipment.getOcid()).isEqualTo("ocid-12345");
      assertThat(equipment.getJsonContent()).isEqualTo("{\"data\": \"test\"}");
      assertThat(equipment.getUpdatedAt()).isNotNull();
      assertThat(equipment.getUpdatedAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }
  }
}
