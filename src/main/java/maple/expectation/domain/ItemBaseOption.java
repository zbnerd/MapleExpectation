package maple.expectation.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Entity
@Getter
@Setter
@ToString
public class ItemBaseOption {
    @Id
    private Long itemId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;

    private int str;
    private int dex;
    private int int_;
    private int luk;
    private int maxHp;
    private int maxMp;
    private int attackPower;
    private int magicPower;
    private int armor;
    private int speed;
    private int jump;
    private int bossDamage;
    private int ignoreMonsterArmor;
    private int allStat;
    private int maxHpRate;
    private int maxMpRate;
    private int baseEquipmentLevel;
}