package maple.expectation.external.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.ToString;

@ToString
public class ItemExceptionalOptionResponse {

    @JsonProperty("str")
    private Integer str;

    @JsonProperty("dex")
    private Integer dex;

    @JsonProperty("int")
    private Integer int_;

    @JsonProperty("luk")
    private Integer luk;

    @JsonProperty("max_hp")
    private Integer maxHp;

    @JsonProperty("max_mp")
    private Integer maxMp;

    @JsonProperty("attack_power")
    private Integer attackPower;

    @JsonProperty("magic_power")
    private Integer magicPower;

    @JsonProperty("exceptional_upgrade")
    private Integer exceptionalUpgrade;
}
