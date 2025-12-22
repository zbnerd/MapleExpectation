package maple.expectation.external.proxy;

import maple.expectation.external.NexonApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DependencyChainTest {
    @Autowired
    private NexonApiClient nexonApiClient;

    @Test
    @DisplayName("의존성 주입 체인 확인: 최상단 프록시는 ResilientNexonApiClient여야 한다")
    void checkDependencyChain() {
        // 주입된 빈이 우리가 만든 Resilient 프록시인지 확인
        assertThat(nexonApiClient).isInstanceOf(ResilientNexonApiClient.class);
    }
}