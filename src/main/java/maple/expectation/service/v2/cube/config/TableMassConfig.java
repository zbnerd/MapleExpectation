package maple.expectation.service.v2.cube.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 테이블 질량 검증 설정
 *
 * <p>확률 테이블의 Σp=1 검증 정책을 외부 설정으로 제어합니다.</p>
 *
 * <h3>설정 예시 (application.yml)</h3>
 * <pre>
 * cube:
 *   table-mass:
 *     policy: STRICT  # 또는 LENIENT
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "cube.table-mass")
@Getter
@Setter
public class TableMassConfig {

    /**
     * 테이블 질량 검증 정책
     */
    public enum TableMassPolicy {
        /**
         * 엄격 모드: Σ≠1 시 즉시 예외 (금융급 기본값)
         */
        STRICT,

        /**
         * 관대 모드: 정규화 후 진행 + 경고 로그
         */
        LENIENT
    }

    /**
     * 테이블 질량 검증 정책
     * 기본값: STRICT (금융급)
     */
    private TableMassPolicy policy = TableMassPolicy.STRICT;
}
