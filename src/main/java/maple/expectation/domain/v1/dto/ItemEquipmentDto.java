package maple.expectation.domain.v1.dto;

import lombok.Builder;
import lombok.Data;
import maple.expectation.domain.v2.GameCharacter;

@Data
@Builder
public class ItemEquipmentDto {
    private Long id;
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