package maple.expectation.domain.v2;

import lombok.Getter;
import jakarta.persistence.*;
import lombok.Setter;
import lombok.ToString;

@Entity
@Getter @Setter
@ToString
public class GameCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String userIgn;

    @Column(nullable = false, unique = true)
    private String ocid;

    @Version
    private Long version;

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

/*    @OneToMany(mappedBy = "gameCharacter")
    private List<ItemEquipment> equipments;*/
}
