package maple.expectation.external.dto.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

@Getter
@ToString
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
