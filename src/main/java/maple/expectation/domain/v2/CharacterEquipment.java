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

    @Lob
    @Column(columnDefinition = "LONGBLOB", nullable = false)
    private byte[] rawData;

    private LocalDateTime updatedAt; // 마지막 갱신 시간

    @Builder
    public CharacterEquipment(String ocid, byte[] rawData) { // 생성자도 byte[]로
        this.ocid = ocid;
        this.rawData = rawData;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateData(byte[] newJsonData) { // 업데이트도 byte[]로
        this.rawData = newJsonData;
        this.updatedAt = LocalDateTime.now();
    }
}