package maple.expectation.external.dto.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.ToString;

@ToString
public class ItemAddOptionResponse {

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

    @JsonProperty("armor")
    private Integer armor;

    @JsonProperty("speed")
    private Integer speed;

    @JsonProperty("jump")
    private Integer jump;

    @JsonProperty("boss_damage")
    private Integer boss_damage;

    @JsonProperty("damage")
    private Integer damage;

    @JsonProperty("all_stat")
    private Integer all_stat;

    @JsonProperty("equipment_level_decrease")
    private Integer equipment_level_decrease;
}
