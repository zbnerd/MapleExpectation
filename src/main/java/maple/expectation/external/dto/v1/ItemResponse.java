package maple.expectation.external.dto.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.ToString;

@ToString
public class ItemResponse {

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

    @JsonProperty("item_base_option") // 베이스 옵션
    private ItemBaseOptionResponse itemBaseOptionResponse;

    @JsonProperty("item_etc_option") // 작 옵션
    private ItemScrollOptionResponse itemScrollOptionResponse;

    @JsonProperty("item_add_option") // 추가 옵션
    private ItemAddOptionResponse itemAddOptionResponse;

    @JsonProperty("item_starforce_option") // 스타포스 옵션
    private ItemStarforceOptionResponse itemStarforceOptionResponse;

}
