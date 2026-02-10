package maple.expectation.global.shutdown.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ShutdownData DTO 테스트 */
@DisplayName("ShutdownData DTO 테스트")
class ShutdownDataTest {

  @Test
  @DisplayName("빈 ShutdownData 생성 테스트")
  void testEmptyShutdownData() {
    // given
    String instanceId = "test-server-01";

    // when
    ShutdownData data = ShutdownData.empty(instanceId);

    // then
    assertThat(data).isNotNull();
    assertThat(data.instanceId()).isEqualTo(instanceId);
    assertThat(data.timestamp()).isNotNull();
    assertThat(data.likeBuffer()).isEmpty();
    assertThat(data.equipmentPending()).isEmpty();
    assertThat(data.isEmpty()).isTrue();
    assertThat(data.getTotalItems()).isZero();
  }

  @Test
  @DisplayName("Like 버퍼만 있는 ShutdownData 테스트")
  void testShutdownDataWithLikeBufferOnly() {
    // given
    String instanceId = "test-server-01";
    Map<String, Long> likeBuffer =
        Map.of(
            "user1", 10L,
            "user2", 20L,
            "user3", 5L);

    // when
    ShutdownData data = new ShutdownData(LocalDateTime.now(), instanceId, likeBuffer, List.of());

    // then
    assertThat(data.isEmpty()).isFalse();
    assertThat(data.likeBuffer()).hasSize(3);
    assertThat(data.likeBuffer().get("user1")).isEqualTo(10L);
    assertThat(data.equipmentPending()).isEmpty();
    assertThat(data.getTotalItems()).isEqualTo(3);
  }

  @Test
  @DisplayName("Equipment pending만 있는 ShutdownData 테스트")
  void testShutdownDataWithEquipmentOnly() {
    // given
    String instanceId = "test-server-01";
    List<String> equipmentPending = List.of("ocid1", "ocid2", "ocid3", "ocid4");

    // when
    ShutdownData data =
        new ShutdownData(LocalDateTime.now(), instanceId, Map.of(), equipmentPending);

    // then
    assertThat(data.isEmpty()).isFalse();
    assertThat(data.likeBuffer()).isEmpty();
    assertThat(data.equipmentPending()).hasSize(4);
    assertThat(data.equipmentPending()).contains("ocid1", "ocid2", "ocid3", "ocid4");
    assertThat(data.getTotalItems()).isEqualTo(4);
  }

  @Test
  @DisplayName("Like 버퍼와 Equipment pending이 모두 있는 ShutdownData 테스트")
  void testShutdownDataWithBoth() {
    // given
    String instanceId = "test-server-01";
    Map<String, Long> likeBuffer = Map.of("user1", 10L, "user2", 20L);
    List<String> equipmentPending = List.of("ocid1", "ocid2", "ocid3");

    // when
    ShutdownData data =
        new ShutdownData(LocalDateTime.now(), instanceId, likeBuffer, equipmentPending);

    // then
    assertThat(data.isEmpty()).isFalse();
    assertThat(data.likeBuffer()).hasSize(2);
    assertThat(data.equipmentPending()).hasSize(3);
    assertThat(data.getTotalItems()).isEqualTo(5); // 2 + 3
  }

  @Test
  @DisplayName("null 필드가 있는 경우 isEmpty 테스트")
  void testIsEmptyWithNullFields() {
    // given
    ShutdownData dataWithNullLikeBuffer =
        new ShutdownData(LocalDateTime.now(), "test-server", null, List.of());

    ShutdownData dataWithNullEquipment =
        new ShutdownData(LocalDateTime.now(), "test-server", Map.of(), null);

    ShutdownData dataWithBothNull =
        new ShutdownData(LocalDateTime.now(), "test-server", null, null);

    // then
    assertThat(dataWithNullLikeBuffer.isEmpty()).isTrue();
    assertThat(dataWithNullEquipment.isEmpty()).isTrue();
    assertThat(dataWithBothNull.isEmpty()).isTrue();
  }

  @Test
  @DisplayName("getTotalItems null safe 테스트")
  void testGetTotalItemsNullSafe() {
    // given
    ShutdownData data = new ShutdownData(LocalDateTime.now(), "test-server", null, null);

    // then
    assertThat(data.getTotalItems()).isZero();
  }

  @Test
  @DisplayName("record equality 테스트")
  void testRecordEquality() {
    // given
    LocalDateTime timestamp = LocalDateTime.now();
    Map<String, Long> likeBuffer = Map.of("user1", 10L);
    List<String> equipmentPending = List.of("ocid1");

    ShutdownData data1 = new ShutdownData(timestamp, "server1", likeBuffer, equipmentPending);
    ShutdownData data2 = new ShutdownData(timestamp, "server1", likeBuffer, equipmentPending);
    ShutdownData data3 = new ShutdownData(timestamp, "server2", likeBuffer, equipmentPending);

    // then
    assertThat(data1).isEqualTo(data2);
    assertThat(data1).isNotEqualTo(data3);
    assertThat(data1.hashCode()).isEqualTo(data2.hashCode());
  }
}
