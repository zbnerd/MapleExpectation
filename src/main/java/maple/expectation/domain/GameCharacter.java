package maple.expectation.domain;

import lombok.Getter;
import jakarta.persistence.*;
import lombok.Setter;

import java.util.List;

@Entity
@Getter @Setter
public class GameCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userIgn;
    private String ocid;

    // 👍 핵심: 좋아요 카운트 (기본값 0)
    private Long likeCount = 0L;

    public GameCharacter(){
        this(null);
    };
    public GameCharacter(String userIgn) {
        this.userIgn = userIgn;
        this.likeCount = 0L;
    }

    // 비즈니스 로직: 좋아요 1 증가
    public void like() {
        this.likeCount++;
    }

    @OneToMany(mappedBy = "gameCharacter")
    private List<ItemEquipment> equipments;
}
