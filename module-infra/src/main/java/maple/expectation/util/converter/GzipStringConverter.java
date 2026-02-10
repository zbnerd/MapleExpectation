package maple.expectation.util.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import maple.expectation.global.error.exception.CompressionException;
import maple.expectation.util.GzipUtils;

import java.io.IOException;

@Converter
public class GzipStringConverter implements AttributeConverter<String, byte[]> {

  @Override
  public byte[] convertToDatabaseColumn(String attribute) {
    if (attribute == null) return null;
    try {
      return GzipUtils.compress(attribute);
    } catch (IOException e) {
      throw new CompressionException("GZIP 압축 오류: " + e.getMessage(), e);
    }
  }

  @Override
  public String convertToEntityAttribute(byte[] dbData) {
    if (dbData == null) return null;
    try {
      return GzipUtils.decompress(dbData);
    } catch (IOException e) {
      throw new CompressionException("GZIP 압축 해제 오류: " + e.getMessage(), e);
    }
  }
}
