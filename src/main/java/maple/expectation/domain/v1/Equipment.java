package maple.expectation.domain.v1;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import maple.expectation.domain.v2.GameCharacter;

@Entity
@Getter
@Setter
@ToString
@EqualsAndHashCode
public class Equipment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long equipmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ocid")
    private GameCharacter gameCharacter;

    private String characterClass;
    private int presetNo;
    private String equipmentHash;
}