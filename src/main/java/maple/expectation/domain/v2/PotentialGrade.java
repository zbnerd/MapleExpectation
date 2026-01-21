package maple.expectation.domain.v2;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import maple.expectation.global.error.exception.InvalidPotentialGradeException;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 잠재능력 등급 Enum
 *
 * <p>큐브 사용 시 입력되는 잠재능력 등급의 유효성을 검증합니다.
 * 잘못된 등급 입력 시 Silent Failure(0원 반환) 대신 명시적 예외를 발생시킵니다.</p>
 *
 * @see InvalidPotentialGradeException
 */
@Getter
@RequiredArgsConstructor
public enum PotentialGrade {
    RARE("레어"),
    EPIC("에픽"),
    UNIQUE("유니크"),
    LEGENDARY("레전드리");

    private final String koreanName;

    private static final Map<String, PotentialGrade> KOREAN_MAP =
            Arrays.stream(values())
                    .collect(Collectors.toMap(g -> g.koreanName, Function.identity()));

    /**
     * 한글 등급명으로 PotentialGrade를 조회합니다.
     *
     * @param korean 한글 등급명 (예: "레어", "에픽", "유니크", "레전드리")
     * @return 매칭되는 PotentialGrade
     * @throws InvalidPotentialGradeException 유효하지 않은 등급명인 경우
     */
    public static PotentialGrade fromKorean(String korean) {
        if (korean == null) {
            throw new InvalidPotentialGradeException("null");
        }
        PotentialGrade grade = KOREAN_MAP.get(korean.trim());
        if (grade == null) {
            throw new InvalidPotentialGradeException(korean);
        }
        return grade;
    }
}
