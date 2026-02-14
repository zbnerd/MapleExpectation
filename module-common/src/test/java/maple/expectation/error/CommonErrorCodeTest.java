package maple.expectation.error;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class CommonErrorCodeTest {

  @Test
  void shouldHaveEVENT_HANDLER_ERROR() {
    // This constant exists in CommonErrorCode enum
    assertTrue(
        CommonErrorCode.EVENT_HANDLER_ERROR != null
            && CommonErrorCode.EVENT_HANDLER_ERROR.getCode().equals("E001"));
  }

  @Test
  void shouldHaveEVENT_CONSUMER_ERROR() {
    // This constant exists in CommonErrorCode enum
    assertTrue(
        CommonErrorCode.EVENT_CONSUMER_ERROR != null
            && CommonErrorCode.EVENT_CONSUMER_ERROR.getCode().equals("E002"));
  }
}
