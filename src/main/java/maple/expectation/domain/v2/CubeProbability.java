package maple.expectation.domain.v2;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor // 💡 모든 필드를 받는 생성자 활용
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

    // 💡 @Data 대신 필요한 메서드만 직접 구현하거나 @Getter만 사용
}