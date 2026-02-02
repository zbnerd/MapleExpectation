package maple.expectation.service.v2.flame;

/**
 * 환생의 불꽃 추가옵션 종류
 */
public enum FlameOptionType {
    STR, DEX, INT, LUK,
    STR_DEX, STR_INT, STR_LUK, DEX_INT, DEX_LUK, INT_LUK,
    MAX_HP, MAX_MP,
    LEVEL_REDUCE,
    DEF,
    ATT, MAG,
    BOSS_DMG_PCT,    // weapon only
    DMG_PCT,         // weapon only
    ALLSTAT_PCT,
    SPEED,           // armor only
    JUMP;            // armor only

    // Weapon option pool (19 types)
    public static final FlameOptionType[] WEAPON_OPTIONS = {
        STR, DEX, INT, LUK,
        STR_DEX, STR_INT, STR_LUK, DEX_INT, DEX_LUK, INT_LUK,
        MAX_HP, MAX_MP, LEVEL_REDUCE, DEF,
        ATT, MAG, BOSS_DMG_PCT, DMG_PCT, ALLSTAT_PCT
    };

    // Armor+Accessory option pool (19 types)
    public static final FlameOptionType[] ARMOR_OPTIONS = {
        STR, DEX, INT, LUK,
        STR_DEX, STR_INT, STR_LUK, DEX_INT, DEX_LUK, INT_LUK,
        MAX_HP, MAX_MP, LEVEL_REDUCE, DEF,
        ATT, MAG, SPEED, JUMP, ALLSTAT_PCT
    };

    public boolean isCompositeStat() {
        return this == STR_DEX || this == STR_INT || this == STR_LUK
            || this == DEX_INT || this == DEX_LUK || this == INT_LUK;
    }
}
