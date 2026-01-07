package maple.expectation.global.error;

import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ✅ GlobalExceptionHandler 스프링 wiring 검증
 * - @ExceptionHandler 라우팅이 실제로 작동하는지만 검증
 * - Facade를 Mock으로 두고 예외만 던져서 핸들러 동작 확인
 * - @SpringBootTest 사용으로 복잡한 의존성 해결
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private GameCharacterFacade gameCharacterFacade;

    @Test
    @DisplayName("CharacterNotFoundException → 404 NOT_FOUND + C002 (스프링 wiring 검증)")
    void handleCharacterNotFoundException_SpringWiring() throws Exception {
        String nonExistIgn = "유령캐릭터";

        given(gameCharacterFacade.findCharacterByUserIgn(nonExistIgn))
                .willThrow(new CharacterNotFoundException(nonExistIgn));

        mockMvc.perform(get("/api/v1/characters/" + nonExistIgn))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("C002"));
    }

    @Test
    @DisplayName("RuntimeException → 500 INTERNAL_SERVER_ERROR + S001 (스프링 wiring 검증)")
    void handleUnexpectedException_SpringWiring() throws Exception {
        given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
                .willThrow(new RuntimeException("알 수 없는 서버 오류"));

        mockMvc.perform(get("/api/v1/characters/anyIgn"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("S001"));
    }
}
