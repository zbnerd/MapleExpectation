package maple.expectation.domain.v2;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import maple.expectation.util.converter.GzipStringConverter;

/**
 * CharacterEquipment 엔티티 (Rich Domain Model)
 *
 * <p>Issue #120: 장비 데이터 만료 여부 판단 로직 캡슐화
 */
@Entity
@Table(
    name = "character_equipment",
    indexes = @Index(name = "idx_character_equipment_updated_at", columnList = "updated_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterEquipment {

  @Id
  @Column(length = 100)
  private String ocid;

  @Convert(converter = GzipStringConverter.class)
  @Lob
  @Column(columnDefinition = "LONGBLOB", nullable = false)
  private String jsonContent;

  private LocalDateTime updatedAt;

  @Builder
  public CharacterEquipment(String ocid, String jsonContent) {
    this.ocid = ocid;
    this.jsonContent = jsonContent;
    this.updatedAt = LocalDateTime.now();
  }

  public void updateData(String newJsonContent) {
    this.jsonContent = newJsonContent;
    this.updatedAt = LocalDateTime.now();
  }

  // ==================== Business Logic (Issue #120) ====================

  /**
   * 데이터 만료 여부 확인
   *
   * @param ttl Time-To-Live 기간
   * @return 만료되었으면 true
   */
  public boolean isExpired(Duration ttl) {
    return this.updatedAt == null || this.updatedAt.isBefore(LocalDateTime.now().minus(ttl));
  }

  /**
   * 데이터 유효 여부 확인 (isExpired의 반대)
   *
   * @param ttl Time-To-Live 기간
   * @return 유효하면 true
   */
  public boolean isFresh(Duration ttl) {
    return !isExpired(ttl);
  }

  /**
   * 데이터 존재 여부 확인
   *
   * @return jsonContent가 존재하면 true
   */
  public boolean hasData() {
    return this.jsonContent != null && !this.jsonContent.isBlank();
  }
}
