package maple.expectation.service.v2;

import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;

public interface CubeTrialsProvider {
    /**
     * 특정 큐브와 설정값에 따른 목표 옵션 도달 기대 시도 횟수를 반환합니다.
     */
    Long calculateExpectedTrials(CubeCalculationInput input, CubeType type);
}