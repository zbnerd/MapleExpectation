package maple.expectation.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson JSON 파싱 보안 설정 (#266 P1-4: DoS 방어)
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Purple (Auditor): JSON Bomb 공격 방어 - 깊이/크기 제한</li>
 *   <li>Red (SRE): 메모리 소진 공격 방지</li>
 *   <li>Blue (Architect): Jackson StreamReadConstraints 활용</li>
 * </ul>
 *
 * <h3>보안 위협 대응</h3>
 * <ul>
 *   <li><b>Deeply Nested JSON:</b> 50레벨 이상 중첩 시 거부</li>
 *   <li><b>Large String:</b> 100KB 이상 문자열 필드 거부</li>
 *   <li><b>Long Property Name:</b> 256자 이상 속성명 거부</li>
 * </ul>
 *
 * <h3>Context7 Best Practice</h3>
 * <p>Jackson 2.15+ StreamReadConstraints 기반 DoS 방어</p>
 *
 * @see <a href="https://github.com/FasterXML/jackson-core/wiki/Jackson-2.15-Release-Notes">Jackson 2.15 Release Notes</a>
 */
@Configuration
public class JacksonConfig {

    /**
     * 최대 JSON 중첩 깊이
     *
     * <h4>Purple Agent: JSON Bomb 방어</h4>
     * <p>50레벨 이상 중첩된 JSON은 정상적인 요청이 아닌 DoS 공격 의심</p>
     *
     * <p>예: {"a":{"b":{"c":...}}} 50레벨 이상 거부</p>
     */
    private static final int MAX_DEPTH = 50;

    /**
     * 최대 문자열 필드 길이 (100KB)
     *
     * <h4>Red Agent: 메모리 소진 방지</h4>
     * <p>100KB 이상의 문자열 필드는 메모리 소진 공격 의심</p>
     *
     * <p>정상 장비 데이터는 최대 수 KB 수준</p>
     */
    private static final int MAX_STRING_LENGTH = 100_000;

    /**
     * 최대 속성명 길이 (256자)
     *
     * <h4>Purple Agent: 비정상 요청 필터링</h4>
     * <p>256자 이상의 속성명은 정상적인 JSON 스키마에서 사용되지 않음</p>
     */
    private static final int MAX_NAME_LENGTH = 256;

    /**
     * Jackson ObjectMapper 커스터마이저
     *
     * <h4>Spring Boot 통합</h4>
     * <p>Jackson2ObjectMapperBuilderCustomizer를 통해
     * Spring Boot 자동 설정 ObjectMapper에 보안 제약 적용</p>
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> builder.postConfigurer(objectMapper -> {
            objectMapper.getFactory().setStreamReadConstraints(
                    StreamReadConstraints.builder()
                            .maxNestingDepth(MAX_DEPTH)
                            .maxStringLength(MAX_STRING_LENGTH)
                            .maxNameLength(MAX_NAME_LENGTH)
                            .build()
            );
        });
    }
}
