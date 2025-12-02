package maple.expectation;

import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.service.v2.GameCharacterService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ExpectationApplicationTests {

	@Autowired
	GameCharacterService gameCharacterService;

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
