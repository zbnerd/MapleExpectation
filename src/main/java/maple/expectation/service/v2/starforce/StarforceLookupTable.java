package maple.expectation.service.v2.starforce;

import java.math.BigDecimal;

/**
 * Starforce 기대값 Lookup Table 인터페이스 (#240)
 *
 * <h3>5-Agent Council 합의사항</h3>
 * <ul>
 *   <li>🟣 Purple (Auditor): BigDecimal 필수 - 정밀 계산</li>
 *   <li>🔴 Red (SRE): 서버 시작 시 초기화 (ApplicationRunner)</li>
 *   <li>🟢 Green (Performance): 인메모리 캐시로 O(1) 조회</li>
 * </ul>
 *
 * <h3>메이플스토리 스타포스 규칙</h3>
 * <ul>
 *   <li>0~10성: 파괴 없음, 100% 성공 또는 30% 성공</li>
 *   <li>11~14성: 파괴 확률 존재</li>
 *   <li>15~25성: 파괴/하락 확률 존재, 세이프가드 옵션</li>
 * </ul>
 *
 * @see StarforceLookupTableImpl 구현체
 * @see LookupTableInitializer ApplicationRunner 초기화
 */
public interface StarforceLookupTable {

    /**
     * 현재 스타에서 목표 스타까지 기대 비용 계산 (기본 옵션 적용)
     *
     * <p>기본 옵션: 스타캐치 O, 썬데이메이플 O, 30% 할인 O, 파괴방지 X</p>
     *
     * @param currentStar 현재 스타포스 (0~30)
     * @param targetStar 목표 스타포스 (currentStar ~ 30)
     * @param itemLevel 아이템 레벨 (1~300)
     * @return 기대 비용 (메소)
     * @throws IllegalArgumentException 유효하지 않은 스타 범위
     */
    BigDecimal getExpectedCost(int currentStar, int targetStar, int itemLevel);

    /**
     * 레벨별 최대 스타포스 반환
     *
     * <ul>
     *   <li>~94lv: 5성</li>
     *   <li>95~107: 8성</li>
     *   <li>108~117: 10성</li>
     *   <li>118~127: 15성</li>
     *   <li>128~137: 20성</li>
     *   <li>138+: 30성</li>
     * </ul>
     *
     * @param itemLevel 아이템 레벨
     * @return 최대 스타포스
     */
    int getMaxStarForLevel(int itemLevel);

    /**
     * 특정 스타에서 성공 확률 조회
     *
     * @param currentStar 현재 스타포스 (0~24)
     * @return 성공 확률 (0.0 ~ 1.0)
     */
    BigDecimal getSuccessProbability(int currentStar);

    /**
     * 특정 스타에서 파괴 확률 조회
     *
     * @param currentStar 현재 스타포스 (0~24)
     * @return 파괴 확률 (0.0 ~ 1.0), 파괴 없으면 0
     */
    BigDecimal getDestroyProbability(int currentStar);

    /**
     * 단일 스타 강화 비용 조회
     *
     * @param currentStar 현재 스타포스 (0~24)
     * @param itemLevel 아이템 레벨
     * @return 1회 강화 비용 (메소)
     */
    BigDecimal getSingleEnhanceCost(int currentStar, int itemLevel);

    /**
     * 초기화 (서버 시작 시 호출)
     *
     * <p>Pre-compute all starforce expected values for faster lookup.</p>
     */
    void initialize();

    /**
     * 초기화 완료 여부 (Health Check용)
     *
     * @return true if initialized
     */
    boolean isInitialized();
}
