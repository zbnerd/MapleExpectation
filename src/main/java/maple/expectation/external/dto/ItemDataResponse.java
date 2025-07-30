package maple.expectation.external.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import maple.expectation.domain.ItemEquipment;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class ItemDataResponse {
    @JsonProperty("date")
    private LocalDateTime date;

    @JsonProperty("character_gender")
    private String characterGender;

    @JsonProperty("character_class")
    private String characterClass;

    @JsonProperty("preset_no")
    private Integer presetNo;

    @JsonProperty("item_equipment")
    private List<ItemEquipmentResponse> itemEquipment;

}
