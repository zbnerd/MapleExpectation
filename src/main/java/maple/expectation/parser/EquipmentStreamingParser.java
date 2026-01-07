package maple.expectation.parser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import maple.expectation.util.StatParser;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * 장비 스트리밍 파서 (Resource-Try까지 박멸한 100% 평탄화 버전)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentStreamingParser {

    private final JsonFactory factory = new JsonFactory();
    private final LogicExecutor executor;
    private final StatParser statParser;
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
        private static final Map<String, JsonField> FIELD_LOOKUP;

        static {
            Map<String, JsonField> map = new HashMap<>();
            for (JsonField field : values()) map.put(field.fieldName, field);
            FIELD_LOOKUP = Collections.unmodifiableMap(map);
        }

        JsonField(String fieldName) { this.fieldName = fieldName; }
        public static JsonField from(String name) {
            return name == null ? UNKNOWN : FIELD_LOOKUP.getOrDefault(name, UNKNOWN);
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

    /**
     * ✅ P0: 최상위 파이프라인 (비즈니스 의도만 노출)
     */
    public List<CubeCalculationInput> parseCubeInputs(byte[] rawJsonData) {
        if (rawJsonData == null || rawJsonData.length == 0) return new ArrayList<>();

        TaskContext context = TaskContext.of("Parser", "StreamingParse");

        // [패턴 6] 예외 세탁 및 실행
        return executor.executeWithTranslation(
                () -> this.executeParsingProcess(rawJsonData, context),
                ExceptionTranslator.forMaple(),
                context
        );
    }

    /**
     * ✅ P0: 자원 생명주기 관리 (try-with-resources 대체)
     */
    private List<CubeCalculationInput> executeParsingProcess(byte[] rawJsonData, TaskContext context) throws IOException {
        InputStream inputStream = createInputStream(rawJsonData);
        JsonParser parser = factory.createParser(inputStream);

        // [패턴 1] executeWithFinally를 통한 자원 해제 보장
        return executor.executeWithFinally(
                () -> this.doStreamParse(parser),
                () -> this.closeResources(inputStream, parser),
                context
        );
    }

    /**
     * 실제 스트리밍 파싱 로직
     */
    private List<CubeCalculationInput> doStreamParse(JsonParser parser) throws IOException {
        List<CubeCalculationInput> resultList = new ArrayList<>();
        findStartArray(parser);

        if (parser.currentToken() == JsonToken.START_ARRAY) {
            parseItemArray(parser, resultList);
        }
        return resultList;
    }

    private void findStartArray(JsonParser parser) throws IOException {
        while (parser.nextToken() != null) {
            if ("item_equipment".equals(parser.currentName())) {
                parser.nextToken();
                break;
            }
        }
    }

    private void parseItemArray(JsonParser parser, List<CubeCalculationInput> resultList) throws IOException {
        int depth = 0;
        CubeCalculationInput currentItem = null;

        while (parser.nextToken() != JsonToken.END_ARRAY) {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.START_OBJECT) {
                if (++depth == 1) currentItem = new CubeCalculationInput();
            } else if (token == JsonToken.END_OBJECT) {
                if (depth-- == 1 && currentItem != null && currentItem.isReady()) resultList.add(currentItem);
            } else if (token == JsonToken.FIELD_NAME) {
                mapField(parser, currentItem);
            }
        }
    }

    private void mapField(JsonParser parser, CubeCalculationInput item) throws IOException {
        if (item == null) return;
        JsonField field = JsonField.from(parser.currentName());
        if (field == JsonField.UNKNOWN) return;

        parser.nextToken();
        FieldMapper mapper = fieldMappers.get(field);
        if (mapper != null) mapper.map(parser, item);
    }

    private void parseLevel(JsonParser parser, CubeCalculationInput item) throws IOException {
        int levelVal = (parser.currentToken() == JsonToken.VALUE_NUMBER_INT)
                ? parser.getIntValue()
                : statParser.parseNum(parser.getText()); // Bean 주입 버전 사용

        if (levelVal > 0) item.setLevel(levelVal);
    }

    private void parsePotential(JsonParser parser, CubeCalculationInput item) throws IOException {
        String val = parser.getText();
        if (val != null && !val.trim().isEmpty()) {
            item.getOptions().add(val);
        }
    }

    private InputStream createInputStream(byte[] data) throws IOException {
        InputStream is = new ByteArrayInputStream(data);
        if (data.length > 2 && data[0] == (byte) 0x1F && data[1] == (byte) 0x8B) {
            return new GZIPInputStream(is);
        }
        return is;
    }

    /**
     * ✅ 박멸: close() 시 발생하는 IOException 노이즈 제거
     */
    private void closeResources(InputStream is, JsonParser parser) {
        executor.executeVoid(() -> {
            if (parser != null) parser.close();
            if (is != null) is.close();
        }, TaskContext.of("Parser", "CloseResources"));
    }
}