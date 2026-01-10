package maple.expectation.service.v2.cube.component;

import maple.expectation.domain.v2.CubeType;
import org.springframework.stereotype.Component;

/**
 * 큐브 타입별 슬롯 수 결정 컴포넌트
 *
 * <p>큐브 종류에 따라 잠재능력 라인(슬롯) 수를 반환합니다.</p>
 *
 * <h3>슬롯 규칙</h3>
 * <ul>
 *   <li>BLACK, RED, ADDITIONAL: 3줄</li>
 * </ul>
 */
@Component
public class CubeSlotCountResolver {

    private static final int DEFAULT_SLOT_COUNT = 3;

    /**
     * 큐브 타입에 따른 슬롯 수 반환
     *
     * @param cubeType 큐브 종류
     * @return 슬롯 수 (기본 3)
     */
    public int resolve(CubeType cubeType) {
        return switch (cubeType) {
            case BLACK, RED, ADDITIONAL -> DEFAULT_SLOT_COUNT;
        };
    }
}
