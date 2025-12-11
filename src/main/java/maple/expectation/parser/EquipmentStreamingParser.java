package maple.expectation.parser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.exception.MapleDataProcessingException;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.util.StatParser;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

@Slf4j
@Component
@TraceLog
@RequiredArgsConstructor
public class EquipmentStreamingParser {

    private final JsonFactory factory = new JsonFactory();
    private final ObjectMapper objectMapper;

    private final Map<JsonField, FieldMapper> fieldMappers = new EnumMap<>(JsonField.class);

    @FunctionalInterface
    private interface FieldMapper {
        void map(JsonParser parser, CubeCalculationInput item) throws IOException;
    }

    // JSON 필드명 매핑 Enum
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

        JsonField(String fieldName) {
            this.fieldName = fieldName;
        }

        public static JsonField from(String name) {
            if (name == null) return UNKNOWN;
            for (JsonField field : values()) {
                if (field.fieldName.equals(name)) {
                    return field;
                }
            }
            return UNKNOWN;
        }
    }

    @PostConstruct
    public void initMappers() {
        fieldMappers.put(JsonField.SLOT, (p, item) -> item.setPart(p.getText()));
        fieldMappers.put(JsonField.GRADE, (p, item) -> item.setGrade(p.getText()));
        fieldMappers.put(JsonField.NAME, (p, item) -> item.setItemName(p.getText()));

        // 복잡한 로직은 메서드 참조로 깔끔하게!
        fieldMappers.put(JsonField.LEVEL, this::parseLevel);

        // 중복되는 로직(잠재 1,2,3)은 하나의 메서드로 재사용
        FieldMapper potentialMapper = this::parsePotential;
        fieldMappers.put(JsonField.POTENTIAL_1, potentialMapper);
        fieldMappers.put(JsonField.POTENTIAL_2, potentialMapper);
        fieldMappers.put(JsonField.POTENTIAL_3, potentialMapper);
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
            throw new MapleDataProcessingException("큐브 계산 입력값 파싱 실패", e);
        }
    }

    public void writeToStream(EquipmentResponse response, OutputStream outputStream) {
        try {
            JsonGenerator jsonGenerator = factory.createGenerator(outputStream);
            objectMapper.writeValue(jsonGenerator, response);
            jsonGenerator.flush();
        } catch (IOException e) {
            throw new MapleDataProcessingException("JSON 스트리밍 직렬화 실패", e);
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

            // Java 14+ Enhanced Switch (화살표 문법) 사용 -> break 불필요, 괄호 최소화
            switch (token) {
                case START_OBJECT -> {
                    depth++;
                    if (depth == 1) currentItem = new CubeCalculationInput();
                }
                case END_OBJECT -> {
                    if (depth == 1) {
                        // 유효한 아이템이면 리스트에 추가
                        if (currentItem != null && currentItem.isReady()) {
                            resultList.add(currentItem);
                        }
                        currentItem = null;
                    }
                    depth--;
                }
                case FIELD_NAME -> mapField(parser, currentItem); // ★ 로직 분리로 깔끔해짐
                default -> { /* 그 외 토큰 무시 */ }
            }
        }
    }

    /**
     * 🔹 추출된 메서드: 필드 매핑 로직
     * - 메인 루프의 들여쓰기를 줄여줌
     * - '어떤 필드인지 확인해서 매핑한다'는 하나의 책임만 가짐
     */
    private void mapField(JsonParser parser, CubeCalculationInput item) throws IOException {
        // 1. 방어 로직: 아이템 객체 내부가 아니거나, 아직 생성 안 됐으면 패스
        if (item == null) return;

        // 2. Enum 변환 및 유효성 체크
        JsonField field = JsonField.from(parser.currentName());
        if (field == JsonField.UNKNOWN) return;

        // 3. 값 읽기 (nextToken)
        parser.nextToken();

        // 4. Map에 등록된 매퍼 실행 (있을 경우만)
        // computeIfPresent 등을 쓸 수도 있지만, 가독성을 위해 단순 get 권장
        FieldMapper mapper = fieldMappers.get(field);
        if (mapper != null) {
            mapper.map(parser, item);
        }
    }

    private void parseLevel(JsonParser parser, CubeCalculationInput item) throws IOException {
        int levelVal = 0;
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            levelVal = parser.getIntValue();
        } else {
            levelVal = StatParser.parseNum(parser.getText());
        }
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