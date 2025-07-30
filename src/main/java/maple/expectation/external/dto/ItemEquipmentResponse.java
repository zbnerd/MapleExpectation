package maple.expectation.external.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.ToString;

@ToString
public class ItemEquipmentResponse {

    @JsonProperty("item_equipment_part")
    private String itemEquipmentPart;

    @JsonProperty("item_name")
    private String itemName;

    @JsonProperty("item_icon")
    private String itemIcon;

    @JsonProperty("item_shape_name")
    private String itemShapeName;

    @JsonProperty("item_shape_icon")
    private String itemShapeIcon;

    @JsonProperty("potential_option_grade")
    private String potentialGrade;

    @JsonProperty("additional_potential_option_grade")
    private String additionalPotentialGrade;

    @JsonProperty("potential_option_1")
    private String potential1;

    @JsonProperty("potential_option_2")
    private String potential2;

    @JsonProperty("potential_option_3")
    private String potential3;

    @JsonProperty("additional_potential_option_1")
    private String addPotential1;

    @JsonProperty("additional_potential_option_2")
    private String addPotential2;

    @JsonProperty("additional_potential_option_3")
    private String addPotential3;

    @JsonProperty("starforce")
    private Integer starForce;

    @JsonProperty("golden_hammer_flag")
    private String goldenHammerFlag;

    @JsonProperty("scroll_upgrade")
    private Integer scrollUpgrade;

    @JsonProperty("item_etc_option")
    private ItemScrollOptionResponse itemScrollOptionResponse;

}
