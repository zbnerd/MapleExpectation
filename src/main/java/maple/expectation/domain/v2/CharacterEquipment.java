package maple.expectation.domain.v2;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterEquipment {

    @Id
    @Column(length = 100) // OCID는 PK
    private String ocid;

    @Lob // 대용량 텍스트
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String rawData; // JSON 전체 저장

    private LocalDateTime updatedAt; // 마지막 갱신 시간

    @Builder
    public CharacterEquipment(String ocid, String rawData) {
        this.ocid = ocid;
        this.rawData = rawData;
        this.updatedAt = LocalDateTime.now();
    }

    // 데이터 갱신 메서드 (Dirty Checking용)
    public void updateData(String newJsonData) {
        this.rawData = newJsonData;
        this.updatedAt = LocalDateTime.now();
    }
}