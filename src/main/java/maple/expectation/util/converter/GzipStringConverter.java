package maple.expectation.util.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import maple.expectation.util.GzipUtils;

@Converter
public class GzipStringConverter implements AttributeConverter<String, byte[]> {

    @Override
    public byte[] convertToDatabaseColumn(String attribute) {
        return GzipUtils.compress(attribute);
    }

    @Override
    public String convertToEntityAttribute(byte[] dbData) {
        return GzipUtils.decompress(dbData);
    }
}