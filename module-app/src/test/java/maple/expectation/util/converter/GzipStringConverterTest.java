package maple.expectation.util.converter;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GzipStringConverterTest {

  private final GzipStringConverter converter = new GzipStringConverter();

  @Test
  void convertToDatabaseColumnReturnsNullWhenAttributeIsNull() {
    assertNull(converter.convertToDatabaseColumn(null));
  }

  @Test
  void convertToEntityAttributeReturnsNullWhenDbDataIsNull() {
    assertNull(converter.convertToEntityAttribute(null));
  }
}
