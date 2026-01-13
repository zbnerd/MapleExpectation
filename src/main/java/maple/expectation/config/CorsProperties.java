package maple.expectation.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * CORS 설정 프로퍼티 (환경별 분리)
 *
 * <p>Issue #172: CORS 와일드카드 제거 - 환경별 명시적 오리진 설정</p>
 *
 * <h4>5-Agent Council Round 2 결정</h4>
 * <ul>
 *   <li><b>Red Agent</b>: fail-fast 원칙 - 빈 리스트 시 앱 시작 실패</li>
 *   <li><b>Blue Agent</b>: @ConfigurationProperties + @Validated 패턴</li>
 *   <li><b>Purple Agent</b>: 와일드카드 + credentials 조합은 보안 취약점</li>
 * </ul>
 *
 * <h4>환경별 설정 예시</h4>
 * <ul>
 *   <li><b>local</b>: localhost 개발 오리진 허용</li>
 *   <li><b>prod</b>: 명시적 프로덕션 도메인만 허용 (환경변수 필수)</li>
 * </ul>
 *
 * <p>CLAUDE.md 섹션 18 준수: Spring Security 6.x CORS Best Practice</p>
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {

    /**
     * 허용할 오리진 목록 (필수)
     *
     * <p>보안 규칙:
     * <ul>
     *   <li>와일드카드(*) 사용 금지 - CSRF 공격 벡터</li>
     *   <li>빈 리스트 시 앱 시작 실패 (fail-fast)</li>
     *   <li>프로덕션에서는 환경변수로 주입 권장</li>
     * </ul>
     * </p>
     */
    @NotEmpty(message = "CORS 허용 오리진 목록은 필수입니다. cors.allowed-origins 설정을 확인하세요.")
    private List<String> allowedOrigins;

    /**
     * credentials 허용 여부 (기본: true)
     *
     * <p>true일 경우 와일드카드 오리진 사용 불가 (Spring Security 규칙)</p>
     */
    @NotNull
    private Boolean allowCredentials = true;

    /**
     * preflight 캐시 시간 (초) (기본: 3600 = 1시간)
     *
     * <p>OPTIONS 요청 결과를 브라우저가 캐시하는 시간</p>
     */
    @Min(0)
    private Long maxAge = 3600L;
}
