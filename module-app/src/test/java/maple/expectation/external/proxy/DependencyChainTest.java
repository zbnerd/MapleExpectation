package maple.expectation.external.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import maple.expectation.external.NexonApiClient;
import maple.expectation.external.impl.ResilientNexonApiClient;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("의존성 주입 체인 검증")
class DependencyChainTest extends IntegrationTestSupport {

  @Autowired private NexonApiClient nexonApiClient;

  @Test
  @DisplayName("최상단 프록시는 ResilientNexonApiClient여야 한다")
  void checkDependencyChain() {
    assertThat(nexonApiClient).isInstanceOf(ResilientNexonApiClient.class);
  }
}
