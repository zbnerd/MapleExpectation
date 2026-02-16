package maple.expectation.domain.v2;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CubeType {
  BLACK("블랙큐브"),
  RED("레드큐브"),
  ADDITIONAL("에디셔널큐브");

  private final String description;

  /** Convert to core CubeType */
  public maple.expectation.core.domain.model.CubeType toCore() {
    return switch (this) {
      case BLACK -> maple.expectation.core.domain.model.CubeType.BLACK;
      case RED -> maple.expectation.core.domain.model.CubeType.RED;
      case ADDITIONAL -> maple.expectation.core.domain.model.CubeType.ADDITIONAL;
    };
  }
}
