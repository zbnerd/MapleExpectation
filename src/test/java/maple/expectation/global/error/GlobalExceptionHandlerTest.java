package maple.expectation.global.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("존재하지 않는 캐릭터 조회 시 404 에러와 동적 메시지를 반환한다")
    void handleCharacterNotFoundException() throws Exception {
        // given: 존재하지 않는 캐릭터 이름
        String nonExistIgn = "유령캐릭터1234567891234565675688945";

        // when & then: 
        // 1순위 가치인 '명확한 피드백'이 전달되는지 확인합니다. [cite: 14]
        mockMvc.perform(get("/api/v1/characters/" + nonExistIgn))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("C002"))
                .andExpect(jsonPath("$.message").value("존재하지 않는 캐릭터입니다 (IGN: " + nonExistIgn + ")"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("예측하지 못한 서버 내부 오류 발생 시 500 에러와 공통 메시지를 반환한다")
    void handleUnexpectedException() throws Exception {
        // given: 서버 내부에서 오타나 로직 오류가 발생한 상황을 가정 [cite: 35, 37]
        // (테스트용 엔드포인트에서 의도적으로 RuntimeException을 던지도록 설정 필요)

        // when & then: 
        // 재앙이 발생해도 시스템 전체 마비를 방지하고 규격화된 응답을 주는지 확인합니다. [cite: 32, 41]
        mockMvc.perform(get("/api/v1/test/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("S001"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}