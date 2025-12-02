package maple.expectation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.CubeProbability;
import maple.expectation.external.dto.v2.EquipmentResponse.ItemEquipment;
import maple.expectation.repository.v1.CubeProbabilityRepository;
import maple.expectation.util.PermutationUtil;
import maple.expectation.util.StatParser;
import maple.expectation.util.StatType;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CubeService {

    private final CubeProbabilityRepository probabilityRepository;

    // TODO: 나중에 DB나 설정 파일에서 가져오도록 변경. 현재는 에테르넬 큐브 레전드리 재설정 기준
    private static final long CUBE_PRICE = 50_000_000; //

    /**
     * 아이템의 잠재능력(3줄)을 띄우기 위한 기대 비용을 계산합니다.
     */
    public long calculateExpectedCost(ItemEquipment item) {
        int level = StatParser.parseNum(item.getBaseOption().getBaseEquipmentLevel());
        String part = item.getItemEquipmentSlot();
        String grade = item.getPotentialOptionGrade();

        // 1. 타겟 옵션 리스트 생성 (순서 섞기 위해 List로 만듦)
        List<String> targetOptions = Arrays.asList(
                item.getPotentialOption1(),
                item.getPotentialOption2(),
                item.getPotentialOption3()
        );

        // 2. 가능한 모든 순서 조합 생성 (중복 제거됨)
        // 예: [STR 12, STR 9, STR 9] -> 3가지 케이스 나옴
        Set<List<String>> permutations = PermutationUtil.generateUniquePermutations(targetOptions);

        // 3. 전체 확률 합산 (P_total = P_case1 + P_case2 + ...)
        double totalProbability = 0.0;

        for (List<String> caseOptions : permutations) {
            double caseProb = 1.0;

            // 각 줄(1,2,3)에 대해 확률 조회 및 곱하기
            // 1번째 옵션 -> Slot 1에서 찾기
            // 2번째 옵션 -> Slot 2에서 찾기
            // 3번째 옵션 -> Slot 3에서 찾기
            caseProb *= findRateIfValid(level, part, grade, 1, caseOptions.get(0));
            caseProb *= findRateIfValid(level, part, grade, 2, caseOptions.get(1));
            caseProb *= findRateIfValid(level, part, grade, 3, caseOptions.get(2));

            totalProbability += caseProb;
        }

        if (totalProbability == 0) {
            return 0;
        }

        long expectedTryCount = (long) (1.0 / totalProbability);
        long expectedCost = expectedTryCount * CUBE_PRICE;

        log.info("[기대값] {} | 조합 수: {} | 확률: {}% | 비용: {}억",
                item.getItemName(), permutations.size(),
                String.format("%.8f", totalProbability * 100),
                expectedCost / 100_000_000);

        return expectedCost;
    }

    private double findRate(int level, String part, String grade, int slot, String optionName) {
        if (optionName == null || optionName.isBlank()) {
            return 1.0; // 옵션이 없으면 확률에 영향 없음 (x1)
        }

        // CSV 리포지토리에서 조건에 맞는 확률 검색
        // stream().filter()로 이름이 일치하는 것 찾기
        return probabilityRepository.findProbabilities(level, part, grade, slot).stream()
                .filter(p -> p.getOptionName().equals(optionName))
                .findFirst()
                .map(CubeProbability::getRate) // 0.0976 형태
                .orElse(0.0); // 데이터에 없으면 0% (불가능한 옵션)
    }

    // 💡 핵심 로직: 유효하지 않은 옵션(잡옵)이면 확률을 1.0(무시)으로 반환
    private double findRateIfValid(int level, String part, String grade, int slot, String optionName) {
        if (optionName == null || optionName.isBlank()) return 1.0;

        // 1. 유효 옵션인지 확인 (StatType 활용)
        StatType type = StatType.findType(optionName);

        // UNKNOWN(잡옵)이면 계산에서 제외 (확률 100%로 취급해서 곱하나 마나 하게 만듦)
        if (type == StatType.UNKNOWN) {
            // 예: "점프력", "방어력" 등 StatType에 등록 안 된 것들
            log.info("{} 째줄 잡옵", slot);
            return 1.0;
        }

        // 2. 유효 옵션이면 CSV에서 확률 조회
        return probabilityRepository.findProbabilities(level, part, grade, slot).stream()
                .filter(p -> p.getOptionName().equals(optionName))
                .findFirst()
                .map(CubeProbability::getRate)
                .orElse(0.0); // 데이터 오류 시 0
    }
}