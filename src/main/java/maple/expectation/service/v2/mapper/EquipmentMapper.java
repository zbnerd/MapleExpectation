package maple.expectation.service.v2.mapper;

import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.util.StatParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class EquipmentMapper {

    public CubeCalculationInput toCubeInput(EquipmentResponse.ItemEquipment item) {
        List<String> options = new ArrayList<>();
        if (item.getPotentialOption1() != null) options.add(item.getPotentialOption1());
        if (item.getPotentialOption2() != null) options.add(item.getPotentialOption2());
        if (item.getPotentialOption3() != null) options.add(item.getPotentialOption3());

        int level = (item.getBaseOption() != null)
                ? StatParser.parseNum(item.getBaseOption().getBaseEquipmentLevel())
                : 0;

        return CubeCalculationInput.builder()
                .itemName(item.getItemName())
                .level(level)
                .part(item.getItemEquipmentSlot())
                .grade(item.getPotentialOptionGrade())
                .options(options)
                .build();
    }

    public TotalExpectationResponse.ItemExpectation toItemExpectation(CubeCalculationInput input, long cost, long count) {
        return TotalExpectationResponse.ItemExpectation.builder()
                .part(input.getPart())
                .itemName(input.getItemName())
                .potential(String.join(" | ", input.getOptions()))
                .expectedCost(cost)
                .expectedCostText(String.format("%,d 메소", cost))
                .expectedCount(count)
                .build();
    }

    public TotalExpectationResponse toTotalResponse(String userIgn, long totalCost, List<TotalExpectationResponse.ItemExpectation> items) {
        return TotalExpectationResponse.builder()
                .userIgn(userIgn)
                .totalCost(totalCost)
                .totalCostText(String.format("%,d 메소", totalCost))
                .items(items)
                .build();
    }
}