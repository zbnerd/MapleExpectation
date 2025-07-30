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

    @OneToMany(mappedBy = "gameCharacter")
    private List<ItemEquipment> equipments;
}
