package maple.expectation.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.springframework.stereotype.Component;

/**
 * 스탯 파싱 컴포넌트 (LogicExecutor 평탄화 적용) * [변경점] 1. static 제거 -> @Component 등록 2. LogicExecutor 주입을 통한
 * 예외 처리 위임 3. TaskContext를 통한 파싱 실패 추적 가능
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatParser {

  private final LogicExecutor executor;

  /**
   * 문자열에서 숫자만 추출 (100% 평탄화) * @param value 파싱할 문자열
   *
   * @return 추출된 숫자 (실패 시 0)
   */
  public int parseNum(String value) {
    if (value == null || value.trim().isEmpty()) {
      return 0;
    }

    // ✅ [패턴 3] executeOrDefault: try-catch를 걷어내고 선언적으로 기본값(0) 지정
    return executor.executeOrDefault(
        () -> {
          String cleanStr = value.replaceAll("[^0-9\\-]", "");
          return cleanStr.isEmpty() ? 0 : Integer.parseInt(cleanStr);
        },
        0, // 예외 발생 시 반환할 기본값
        TaskContext.of("Parser", "StatParse", value) // 어떤 값이 들어왔는지 추적
        );
  }

  /** 퍼센트(%) 옵션인지 확인 */
  public boolean isPercent(String value) {
    return value != null && value.contains("%");
  }
}
