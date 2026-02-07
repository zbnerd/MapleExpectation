package maple.expectation;

import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.impl.RealNexonApiClient;
import maple.expectation.service.v2.GameCharacterService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Tag("integration")
class ExpectationApplicationTests {

  @Autowired GameCharacterService gameCharacterService;

  @MockitoBean RealNexonApiClient nexonApiClient;

  @Test
  void 캐릭터ocid생성() {
    // [Given]
    // 💡 [수정 포인트] 기본 생성자 + Setter 대신, 정의한 2인자 생성자 사용
    // 이제 객체가 태어날 때부터 'Geek'이라는 이름과 'OCID'를 가진 완벽한 상태가 됩니다.
    GameCharacter gameCharacter = new GameCharacter("Geek", "0123456789abcdef");

    // [When]
    gameCharacterService.saveCharacter(gameCharacter);

    // [Then]
    Assertions.assertThat(gameCharacter.getUserIgn()).isEqualTo("Geek");
    Assertions.assertThat(gameCharacter.getOcid()).isEqualTo("0123456789abcdef");
  }
}
