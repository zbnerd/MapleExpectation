package maple.expectation.repository;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CubeProbability;
import maple.expectation.repository.v2.CubeProbabilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class CubeProbabilityRepositoryTest {

    private CubeProbabilityRepository repository;

    @BeforeEach
    void setUp() {
        // 스프링이 주입(Autowired) 안 해주니까, 우리가 직접 생성하고 init() 호출
        repository = new CubeProbabilityRepository();
        repository.init(); // @PostConstruct 수동 실행
    }

    @Test
    @DisplayName("기대값 계산: STR 3줄(12%, 9%, 9%)이 뜰 확률 계산")
    void calculate_triple_stat_probability_test() {
        // given: 200제 모자, 레전드리 기준
        int level = 200;
        String part = "모자";
        String grade = "레전드리";

        // when
        CubeProbability line1 = repository.findProbabilities(level, part, grade, 1).stream()
                .filter(p -> p.getOptionName().startsWith("STR"))
                .findFirst().orElseThrow();

        CubeProbability line2 = repository.findProbabilities(level, part, grade, 2).stream()
                .filter(p -> p.getOptionName().startsWith("STR"))
                .findFirst().orElseThrow();

        CubeProbability line3 = repository.findProbabilities(level, part, grade, 3).stream()
                .filter(p -> p.getOptionName().startsWith("STR"))
                .findFirst().orElseThrow();

        // then: 로그 확인 (이제 getRate()로 바로 호출)
        log.info("1번째 줄: {} (확률: {})", line1.getOptionName(), line1.getRate());
        log.info("2번째 줄: {} (확률: {})", line2.getOptionName(), line2.getRate());
        log.info("3번째 줄: {} (확률: {})", line3.getOptionName(), line3.getRate());

        assertThat(line1.getOptionName()).contains("12%");
        
        // 4. 최종 확률 계산 (독립 시행: P(A) * P(B) * P(C))
        // 데이터가 이미 0.0976 형태이므로 100으로 나눌 필요 없음! 🌟
        double prob1 = line1.getRate();
        double prob2 = line2.getRate();
        double prob3 = line3.getRate();

        double totalProbability = prob1 * prob2 * prob3;
        double oneInN = 1.0 / totalProbability;

        log.info("==========================================");
        // %로 표시하기 위해 100을 곱해서 출력
        log.info("STR 30%(12+9+9) 저격 성공 확률: {}%", String.format("%.10f", totalProbability * 100));
        log.info("기대 재설정 횟수: 약 {}개", String.format("%,.0f", oneInN));
        log.info("==========================================");
    }
}