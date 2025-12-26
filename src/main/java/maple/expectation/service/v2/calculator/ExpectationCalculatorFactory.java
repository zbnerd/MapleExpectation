package maple.expectation.service.v2.calculator;

import lombok.RequiredArgsConstructor;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.calculator.impl.BaseItem;
import maple.expectation.service.v2.calculator.impl.BlackCubeDecorator;
import maple.expectation.service.v2.policy.CubeCostPolicy;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExpectationCalculatorFactory {

    private final CubeTrialsProvider trialsProvider;
    private final CubeCostPolicy costPolicy;

    public ExpectationCalculator createBlackCubeCalculator(CubeCalculationInput input) {
        ExpectationCalculator calculator = new BaseItem(input.getItemName());
        // 💡 향후 레드큐브나 에디셔널 장식자가 추가되어도 여기서만 로직을 변경하면 됩니다.
        return new BlackCubeDecorator(calculator, trialsProvider, costPolicy, input);
    }
}