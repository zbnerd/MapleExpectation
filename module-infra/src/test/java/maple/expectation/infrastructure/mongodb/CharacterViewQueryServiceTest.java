package maple.expectation.infrastructure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;
import maple.expectation.infrastructure.executor.LogicExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("V5: MongoDB Query Service Tests")
class CharacterViewQueryServiceTest {

  @Mock private CharacterValuationRepository mockRepository;

  @Mock private LogicExecutor mockExecutor;

  @Test
  @DisplayName("MongoDB 조회 성공 시 결과 반환")
  void findByUserIgnReturnsView() throws Exception {
    CharacterValuationView view =
        CharacterValuationView.builder().userIgn("testUser").totalExpectedCost(100000).build();

    when(mockRepository.findByUserIgn("testUser")).thenReturn(Optional.of(view));
    when(mockExecutor.executeOrDefault(any(), any(), any()))
        .thenAnswer(
            inv -> {
              var callable = inv.getArgument(0);
              return callable.call();
            });

    CharacterViewQueryService service =
        new CharacterViewQueryService(mockRepository, null, mockExecutor, null);

    var result = service.findByUserIgn("testUser");

    assertThat(result).isPresent();
    assertThat(result.get().getUserIgn()).isEqualTo("testUser");
  }

  @Test
  @DisplayName("MongoDB 장애 시 빈 값 반환")
  void mongoDBFailureReturnsEmpty() throws Exception {
    when(mockExecutor.executeOrDefault(any(), any(), any()))
        .thenAnswer(
            inv -> {
              return inv.getArgument(1); // return default value
            });

    CharacterViewQueryService service =
        new CharacterViewQueryService(mockRepository, null, mockExecutor, null);

    var result = service.findByUserIgn("testUser");

    assertThat(result).isEmpty();
  }
}
