package maple.expectation.global.error;

import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean; // Spring Boot 3.4+ 기준
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameCharacterFacade gameCharacterFacade;

    @Test
    @DisplayName("존재하지 않는 캐릭터 조회 시 404 에러와 동적 메시지를 반환한다")
    void handleCharacterNotFoundException() throws Exception {
        // given
        String nonExistIgn = "유령캐릭터";
        // Mock이 직접 예외를 던지도록 설정 (서킷 브레이커를 거치지 않음)
        given(gameCharacterFacade.findCharacterByUserIgn(nonExistIgn))
                .willThrow(new CharacterNotFoundException(nonExistIgn));

        // when & then
        mockMvc.perform(get("/api/v1/characters/" + nonExistIgn))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("C002"))
                .andExpect(jsonPath("$.message").value("존재하지 않는 캐릭터입니다 (IGN: " + nonExistIgn + ")"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("예측하지 못한 서버 내부 오류 발생 시 500 에러와 공통 메시지를 반환한다")
    void handleUnexpectedException() throws Exception {
        // given
        String ign = "anyIgn";
        // 의도적으로 런타임 예외 발생 시뮬레이션
        given(gameCharacterFacade.findCharacterByUserIgn(ign))
                .willThrow(new RuntimeException("알 수 없는 서버 오류"));

        // when & then
        mockMvc.perform(get("/api/v1/characters/" + ign))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("S001"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}