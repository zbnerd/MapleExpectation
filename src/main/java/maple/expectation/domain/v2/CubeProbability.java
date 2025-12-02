package maple.expectation.domain.v2;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor // Jackson은 기본 생성자가 필요합니다
public class CubeProbability {
    
    @JsonProperty("option") // CSV 헤더 이름
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