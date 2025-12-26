package maple.expectation.domain.v2;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import maple.expectation.util.converter.GzipStringConverter; // 💡 컨버터 임포트

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterEquipment {

    @Id
    @Column(length = 100)
    private String ocid;

    // 💡 핵심: 필드 타입을 String으로 변경하고 컨버터 장착!
    @Convert(converter = GzipStringConverter.class)
    @Lob
    @Column(columnDefinition = "LONGBLOB", nullable = false)
    private String jsonContent; // 💡 이름도 의미에 맞게 rawData -> jsonContent로 변경

    private LocalDateTime updatedAt;

    @Builder
    public CharacterEquipment(String ocid, String jsonContent) { // 💡 String을 받음
        this.ocid = ocid;
        this.jsonContent = jsonContent;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateData(String newJsonContent) { // 💡 String으로 업데이트
        this.jsonContent = newJsonContent;
        this.updatedAt = LocalDateTime.now();
    }
}