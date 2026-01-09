package maple.expectation.service.v2.cube.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 큐브 엔진 Feature Flag 설정
 *
 * <p>v1(순열) ↔ v2(DP) 엔진 전환 및 Shadow Run을 제어합니다.</p>
 *
 * <h3>설정 예시 (application.yml)</h3>
 * <pre>
 * cube:
 *   engine:
 *     dp-enabled: false      # true: v2(DP) 활성, false: v1(순열) 활성
 *     shadow-enabled: false  # true: 비활성 엔진도 병렬 계산하여 비교 로깅
 * </pre>
 *
 * <h3>전환 시나리오</h3>
 * <ol>
 *   <li>dpEnabled=false, shadowEnabled=true: v1 결과 반환, v2 비교 로깅</li>
 *   <li>dpEnabled=true, shadowEnabled=true: v2 결과 반환, v1 drift 모니터링</li>
 *   <li>dpEnabled=true, shadowEnabled=false: v2만 실행 (최종 상태)</li>
 * </ol>
 */
@Component
@ConfigurationProperties(prefix = "cube.engine")
@Getter
@Setter
public class CubeEngineFeatureFlag {

    /**
     * DP 엔진 활성화 여부
     * <ul>
     *   <li>false: v1(순열) 결과 반환, (shadow=true면) v2도 계산하여 비교 로깅</li>
     *   <li>true: v2(DP) 결과 반환, (shadow=true면) v1도 계산하여 drift 모니터링</li>
     * </ul>
     */
    private boolean dpEnabled = false;

    /**
     * Shadow 실행 (비교 로깅)
     * <ul>
     *   <li>true: 비활성 엔진도 병렬 계산하여 결과 비교 로깅</li>
     *   <li>false: 활성 엔진만 계산</li>
     * </ul>
     */
    private boolean shadowEnabled = false;
}
