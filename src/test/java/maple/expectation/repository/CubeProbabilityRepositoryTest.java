package maple.expectation.repository;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CubeProbability;
import maple.expectation.domain.v2.CubeType; // 👈 추가
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
        repository = new CubeProbabilityRepository();
        repository.init();
    }

    @Test
    @DisplayName("기대값 계산: STR 3줄(12%, 9%, 9%)이 뜰 확률 계산")
    void calculate_triple_stat_probability_test() {
        // given: 200제 모자, 레전드리 기준
        int level = 200;
        String part = "모자";
        String grade = "레전드리";
        CubeType type = CubeType.BLACK; // 👈 윗잠재는 블랙큐브로 설정

        // when: findProbabilities 호출 시 CubeType.BLACK(type)을 첫 번째 인자로 전달
        CubeProbability line1 = repository.findProbabilities(type, level, part, grade, 1).stream()
                .filter(p -> p.getOptionName().startsWith("STR"))
                .findFirst().orElseThrow();

        CubeProbability line2 = repository.findProbabilities(type, level, part, grade, 2).stream()
                .filter(p -> p.getOptionName().startsWith("STR"))
                .findFirst().orElseThrow();

        CubeProbability line3 = repository.findProbabilities(type, level, part, grade, 3).stream()
                .filter(p -> p.getOptionName().startsWith("STR"))
                .findFirst().orElseThrow();

        // then: 로그 확인 및 검증 로직은 동일
        log.info("큐브 종류: {}", type.getDescription());
        log.info("1번째 줄: {} (확률: {})", line1.getOptionName(), line1.getRate());
        log.info("2번째 줄: {} (확률: {})", line2.getOptionName(), line2.getRate());
        log.info("3번째 줄: {} (확률: {})", line3.getOptionName(), line3.getRate());

        assertThat(line1.getOptionName()).contains("12%");

        double prob1 = line1.getRate();
        double prob2 = line2.getRate();
        double prob3 = line3.getRate();

        double totalProbability = prob1 * prob2 * prob3;
        double oneInN = 1.0 / totalProbability;

        log.info("==========================================");
        log.info("STR 30%(12+9+9) 저격 성공 확률: {}%", String.format("%.10f", totalProbability * 100));
        log.info("기대 재설정 횟수: 약 {}개", String.format("%,.0f", oneInN));
        log.info("==========================================");

        assertThat(totalProbability).isGreaterThan(0);
    }
}