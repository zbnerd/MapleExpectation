package maple.expectation.domain;

import lombok.Getter;
import jakarta.persistence.*;

@Entity
@Getter
public class ItemEquipment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)

    @Column(name = "item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ocid")
    private GameCharacter gameCharacter;
    private int presetNo;
    private String part;
    private String itemName;
    private String itemIcon;
    private String itemShapeName;
    private String itemShapeIcon;
    private String potentialGrade;
    private String additionalPotentialGrade;

    private String potential1;
    private String potential2;
    private String potential3;

    private String addPotential1;
    private String addPotential2;
    private String addPotential3;

    private int starForce;
    private boolean goldenHammer;
}


