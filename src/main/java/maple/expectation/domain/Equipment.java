package maple.expectation.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Entity
@Getter
@Setter
@ToString
public class Equipment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long equipmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ocid")
    private GameCharacter gameCharacter;

    private String characterClass;
    private int presetNo;
}