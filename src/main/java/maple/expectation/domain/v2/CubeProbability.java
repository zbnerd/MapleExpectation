package maple.expectation.domain.v2;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CubeProbability {


    @JsonProperty("cube_type")
    private CubeType cubeType;

    @JsonProperty("option")
    private String optionName;

    @JsonProperty("rate")
    private double rate;

    @JsonProperty("slot")
    private int slot;

    @JsonProperty("potential_option_grade")
    private String grade;

    @JsonProperty("base_equipment_level")
    private int level;

    @JsonProperty("item_equipment_slot")
    private String part;
}