package maple.expectation.global.error;

import maple.expectation.controller.GameCharacterControllerV1;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.global.executor.DefaultLogicExecutor;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import maple.expectation.service.v2.EquipmentService;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {
        GameCharacterControllerV1.class,
        GlobalExceptionHandler.class
})
// 🚀 [해결] 실제 로직 실행기를 가져옵니다.
@Import({DefaultLogicExecutor.class})
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    // 1. 컨트롤러가 의존하는 서비스들 (여전히 Mock으로 두어 예외 상황 조작)
    @MockitoBean private GameCharacterFacade gameCharacterFacade;
    @MockitoBean private GameCharacterService gameCharacterService;
    @MockitoBean private EquipmentService equipmentService;

    // 2. [추가] 실제 DefaultLogicExecutor가 작동하기 위해 필요한 부품들
    @MockitoBean private ExceptionTranslator exceptionTranslator;
    @MockitoBean private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Test
    @DisplayName("존재하지 않는 캐릭터 조회 시 404 에러를 반환한다")
    void handleCharacterNotFoundException() throws Exception {
        String nonExistIgn = "유령캐릭터";

        given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
                .willThrow(new CharacterNotFoundException(nonExistIgn));

        // 🔍 [중요] 실제 브라우저에서 /api/v1으로 확인하셨으므로 URL을 v1으로 맞춥니다.
        mockMvc.perform(get("/api/v1/characters/" + nonExistIgn))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("C002"));
    }

    @Test
    @DisplayName("예측하지 못한 서버 내부 오류 발생 시 500 에러를 반환한다")
    void handleUnexpectedException() throws Exception {
        given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
                .willThrow(new RuntimeException("알 수 없는 서버 오류"));

        mockMvc.perform(get("/api/v1/characters/anyIgn"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("S001"));
    }
}