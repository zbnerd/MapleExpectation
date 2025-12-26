package maple.expectation.parser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.global.error.exception.MapleDataProcessingException;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.util.StatParser;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.zip.GZIPInputStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentStreamingParser {

    private final JsonFactory factory = new JsonFactory();
    private final ObjectMapper objectMapper;
    private final Map<JsonField, FieldMapper> fieldMappers = new EnumMap<>(JsonField.class);

    @FunctionalInterface
    private interface FieldMapper {
        void map(JsonParser parser, CubeCalculationInput item) throws IOException;
    }

    private enum JsonField {
        SLOT("item_equipment_slot"),
        GRADE("potential_option_grade"),
        LEVEL("base_equipment_level"),
        NAME("item_name"),
        POTENTIAL_1("potential_option_1"),
        POTENTIAL_2("potential_option_2"),
        POTENTIAL_3("potential_option_3"),
        UNKNOWN("");

        private final String fieldName;

        // 정적 캐시 맵: O(1) 조회를 위해 도입
        private static final Map<String, JsonField> FIELD_LOOKUP;

        static {
            Map<String, JsonField> map = new HashMap<>();
            for (JsonField field : values()) {
                map.put(field.fieldName, field);
            }
            FIELD_LOOKUP = Collections.unmodifiableMap(map);
        }

        JsonField(String fieldName) { this.fieldName = fieldName; }

        /**
         * 기존 O(N) 루프를 제거하고 캐시 맵을 사용하여 O(1)로 조회합니다.
         */
        public static JsonField from(String name) {
            if (name == null) return UNKNOWN;
            return FIELD_LOOKUP.getOrDefault(name, UNKNOWN);
        }
    }



    @PostConstruct
    public void initMappers() {
        fieldMappers.put(JsonField.SLOT, (p, item) -> item.setPart(p.getText()));
        fieldMappers.put(JsonField.GRADE, (p, item) -> item.setGrade(p.getText()));
        fieldMappers.put(JsonField.NAME, (p, item) -> item.setItemName(p.getText()));
        fieldMappers.put(JsonField.LEVEL, this::parseLevel);
        fieldMappers.put(JsonField.POTENTIAL_1, this::parsePotential);
        fieldMappers.put(JsonField.POTENTIAL_2, this::parsePotential);
        fieldMappers.put(JsonField.POTENTIAL_3, this::parsePotential);
    }

    public List<CubeCalculationInput> parseCubeInputs(byte[] rawJsonData) {
        if (rawJsonData == null || rawJsonData.length == 0) return new ArrayList<>();

        try (InputStream inputStream = createInputStream(rawJsonData);
             JsonParser parser = factory.createParser(inputStream)) {

            List<CubeCalculationInput> resultList = new ArrayList<>();
            while (parser.nextToken() != null) {
                if ("item_equipment".equals(parser.currentName())) {
                    parser.nextToken();
                    break;
                }
            }
            if (parser.currentToken() == JsonToken.START_ARRAY) {
                parseItemArray(parser, resultList);
            }
            return resultList;

        } catch (IOException e) {
            throw new MapleDataProcessingException("메이플 데이터 파싱 중 기술적 오류 발생: " + e.getMessage());
        }
    }

    public void writeToStream(EquipmentResponse response, OutputStream outputStream) {
        try {
            JsonGenerator jsonGenerator = factory.createGenerator(outputStream);
            objectMapper.writeValue(jsonGenerator, response);
            jsonGenerator.flush();
        } catch (IOException e) {
            throw new MapleDataProcessingException("JSON 스트리밍 직렬화 실패: " + e.getMessage());
        }
    }

    private InputStream createInputStream(byte[] data) throws IOException {
        InputStream is = new ByteArrayInputStream(data);
        if (data.length > 2 && data[0] == (byte) 0x1F && data[1] == (byte) 0x8B) {
            return new GZIPInputStream(is);
        }
        return is;
    }

    private void parseItemArray(JsonParser parser, List<CubeCalculationInput> resultList) throws IOException {
        CubeCalculationInput currentItem = null;
        int depth = 0;

        while (parser.nextToken() != JsonToken.END_ARRAY) {
            JsonToken token = parser.currentToken();
            switch (token) {
                case START_OBJECT -> {
                    depth++;
                    if (depth == 1) currentItem = new CubeCalculationInput();
                }
                case END_OBJECT -> {
                    if (depth == 1 && currentItem != null && currentItem.isReady()) {
                        resultList.add(currentItem);
                    }
                    depth--;
                }
                case FIELD_NAME -> mapField(parser, currentItem);
                default -> { }
            }
        }
    }

    private void mapField(JsonParser parser, CubeCalculationInput item) throws IOException {
        if (item == null) return;
        JsonField field = JsonField.from(parser.currentName());
        if (field == JsonField.UNKNOWN) return;

        parser.nextToken();
        FieldMapper mapper = fieldMappers.get(field);
        if (mapper != null) {
            mapper.map(parser, item);
        }
    }

    private void parseLevel(JsonParser parser, CubeCalculationInput item) throws IOException {
        int levelVal = (parser.currentToken() == JsonToken.VALUE_NUMBER_INT)
                ? parser.getIntValue()
                : StatParser.parseNum(parser.getText());

        if (levelVal > 0) {
            item.setLevel(levelVal);
        }
    }

    private void parsePotential(JsonParser parser, CubeCalculationInput item) throws IOException {
        String val = parser.getText();
        if (val != null && !val.trim().isEmpty()) {
            item.getOptions().add(val);
        }
    }
}