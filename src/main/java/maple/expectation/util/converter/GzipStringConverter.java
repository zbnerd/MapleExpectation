package maple.expectation.util.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import maple.expectation.util.GzipUtils;

@Converter
public class GzipStringConverter implements AttributeConverter<String, byte[]> {

    @Override
    public byte[] convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        return GzipUtils.compress(attribute);
    }

    @Override
    public String convertToEntityAttribute(byte[] dbData) {
        if (dbData == null) return null;
        return GzipUtils.decompress(dbData);
    }
}