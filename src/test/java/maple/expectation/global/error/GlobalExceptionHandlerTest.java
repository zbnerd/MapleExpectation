package maple.expectation.global.error;

import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 🚀 [Optimization] @WebMvcTest를 사용하여 스프링 컨테이너/인프라 없이
 * MVC 계층만 빠르게 테스트합니다.
 */
@WebMvcTest(controllers = {maple.expectation.controller.GameCharacterControllerV1.class})
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private GameCharacterFacade gameCharacterFacade;
    @MockitoBean private maple.expectation.service.v2.GameCharacterService gameCharacterService;

    @Test
    @DisplayName("존재하지 않는 캐릭터 조회 시 404 에러와 동적 메시지를 반환한다")
    void handleCharacterNotFoundException() throws Exception {
        String nonExistIgn = "유령캐릭터";
        given(gameCharacterFacade.findCharacterByUserIgn(nonExistIgn))
                .willThrow(new CharacterNotFoundException(nonExistIgn));

        mockMvc.perform(get("/api/v1/characters/" + nonExistIgn))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("C002"))
                .andExpect(jsonPath("$.message").value("존재하지 않는 캐릭터입니다 (IGN: " + nonExistIgn + ")"));
    }

    @Test
    @DisplayName("예측하지 못한 서버 내부 오류 발생 시 500 에러와 공통 메시지를 반환한다")
    void handleUnexpectedException() throws Exception {
        String ign = "anyIgn";
        given(gameCharacterFacade.findCharacterByUserIgn(ign))
                .willThrow(new RuntimeException("알 수 없는 서버 오류"));

        mockMvc.perform(get("/api/v1/characters/" + ign))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("S001"));
    }
}