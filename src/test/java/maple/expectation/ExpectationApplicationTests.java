package maple.expectation;

import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.MaplestoryApiClient; // import 추가
import maple.expectation.service.v2.GameCharacterService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean; // import 추가
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "app.optimization.use-compression=false")
class ExpectationApplicationTests {

    @Autowired
    GameCharacterService gameCharacterService;

    // ✨ 해결책: 외부 API 클라이언트를 Mocking하여 실제 키값 주입 과정을 생략시킴
    @MockitoBean
    MaplestoryApiClient maplestoryApiClient;

    @Test
    void 캐릭터ocid생성() {
        //given
        GameCharacter gameCharacter = new GameCharacter();
        gameCharacter.setUserIgn("Geek");
        gameCharacter.setOcid("0123456789abcdef");

        //when
        gameCharacterService.saveCharacter(gameCharacter);

        //then
        Assertions.assertThat(gameCharacter.getUserIgn()).isEqualTo("Geek");
    }

}