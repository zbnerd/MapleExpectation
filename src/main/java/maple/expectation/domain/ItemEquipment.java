package maple.expectation.domain;

import lombok.Builder;
import lombok.Getter;
import jakarta.persistence.*;
import lombok.ToString;
import maple.expectation.domain.dto.ItemEquipmentDto;

@Entity
@Getter
@ToString
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

    public void setItemInfo(ItemEquipmentDto dto) {
        this.gameCharacter = dto.getGameCharacter();
        this.presetNo = dto.getPresetNo();
        this.part = dto.getPart();
        this.itemName = dto.getItemName();
        this.itemIcon = dto.getItemIcon();
        this.itemShapeName = dto.getItemShapeName();
        this.itemShapeIcon = dto.getItemShapeIcon();
        this.potentialGrade = dto.getPotentialGrade();
        this.additionalPotentialGrade = dto.getAdditionalPotentialGrade();
        this.potential1 = dto.getPotential1();
        this.potential2 = dto.getPotential2();
        this.potential3 = dto.getPotential3();
        this.addPotential1 = dto.getAddPotential1();
        this.addPotential2 = dto.getAddPotential2();
        this.addPotential3 = dto.getAddPotential3();
        this.starForce = dto.getStarForce();
        this.goldenHammer = dto.isGoldenHammer();
    }
    
}


