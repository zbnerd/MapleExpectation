package maple.expectation.external.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

@Getter
public class EquipmentResponse {

    @JsonProperty("character_gender")
    private String characterGender;

    @JsonProperty("character_class")
    private String characterClass;

    @JsonProperty("preset_no")
    private Integer presetNo;

    @JsonProperty("item_equipment")
    private List<ItemResponse> itemEquipment;

}
