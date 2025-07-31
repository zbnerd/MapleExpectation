package maple.expectation.domain;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Entity
@Getter
@Setter
@ToString
@EqualsAndHashCode
public class Item {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long itemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "equipment_id")
    private Equipment equipment;

    private String itemEquipmentPart;
    private String itemName;
    private String itemIcon;
    private String itemShapeName;
    private String itemShapeIcon;

    private String potentialOptionGrade;
    private String additionalPotentialOptionGrade;

    private String potentialOption1;
    private String potentialOption2;
    private String potentialOption3;

    private String additionalPotentialOption1;
    private String additionalPotentialOption2;
    private String additionalPotentialOption3;

    private int starforce;
    private String goldenHammerFlag;
    private int scrollUpgrade;
}