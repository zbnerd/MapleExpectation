package maple.expectation.service.v2.mapper;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import maple.expectation.core.domain.stat.StatParser;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import org.springframework.stereotype.Component;

/** 장비 데이터 매퍼 (LogicExecutor 환경 대응 및 평탄화 완료) */
@Component
@RequiredArgsConstructor // ✅ StatParser 주입을 위해 추가
public class EquipmentMapper {

  private final StatParser statParser; // ✅ Bean 주입 (static 호출 제거)

  public CubeCalculationInput toCubeInput(EquipmentResponse.ItemEquipment item) {
    // [평탄화] if문 나열 대신 Stream을 사용하여 기술적 노이즈 제거
    List<String> options =
        Stream.of(
                item.getPotentialOption1(), item.getPotentialOption2(), item.getPotentialOption3())
            .filter(Objects::nonNull)
            .toList();

    // ✅ StatParser를 인스턴스(statParser)로 호출하여 컴파일 오류 해결
    int level =
        (item.getBaseOption() != null)
            ? statParser.parseNum(item.getBaseOption().getBaseEquipmentLevel())
            : 0;

    return CubeCalculationInput.builder()
        .itemName(item.getItemName())
        .level(level)
        .part(item.getItemEquipmentSlot())
        .grade(item.getPotentialOptionGrade())
        .options(options)
        .build();
  }

  public TotalExpectationResponse.ItemExpectation toItemExpectation(
      CubeCalculationInput input, long cost, long count) {
    return TotalExpectationResponse.ItemExpectation.builder()
        .part(input.getPart())
        .itemName(input.getItemName())
        .potential(String.join(" | ", input.getOptions()))
        .expectedCost(cost)
        .expectedCostText(String.format("%,d 메소", cost))
        .expectedCount(count)
        .build();
  }

  public TotalExpectationResponse toTotalResponse(
      String userIgn, long totalCost, List<TotalExpectationResponse.ItemExpectation> items) {
    return TotalExpectationResponse.builder()
        .userIgn(userIgn)
        .totalCost(totalCost)
        .totalCostText(String.format("%,d 메소", totalCost))
        .items(items)
        .build();
  }
}
