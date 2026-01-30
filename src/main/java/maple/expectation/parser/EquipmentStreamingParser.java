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

    /**
     * JSON 필드 매핑 Enum (#240 V4: 13개 필드 확장)
     *
     * <h3>기본 장비 정보</h3>
     * <ul>
     *   <li>SLOT: item_equipment_slot (장비 슬롯)</li>
     *   <li>PART: item_equipment_part (보조무기 세부 분류)</li>
     *   <li>NAME: item_name (장비 이름)</li>
     *   <li>ICON: item_icon (아이콘 URL)</li>
     *   <li>LEVEL: base_equipment_level (아이템 레벨)</li>
     * </ul>
     *
     * <h3>잠재능력</h3>
     * <ul>
     *   <li>GRADE: potential_option_grade</li>
     *   <li>POTENTIAL_1/2/3: potential_option_1/2/3</li>
     * </ul>
     *
     * <h3>에디셔널 잠재능력</h3>
     * <ul>
     *   <li>ADDITIONAL_GRADE: additional_potential_option_grade</li>
     *   <li>ADDITIONAL_1/2/3: additional_potential_option_1/2/3</li>
     * </ul>
     *
     * <h3>스타포스</h3>
     * <ul>
     *   <li>STARFORCE: starforce (현재 스타포스 수치)</li>
     *   <li>STARFORCE_SCROLL_FLAG: starforce_scroll_flag (놀장 여부)</li>
     * </ul>
     */
    private enum JsonField {
        // 기본 정보
        SLOT("item_equipment_slot"),
        PART("item_equipment_part"),
        NAME("item_name"),
        ICON("item_icon"),
        LEVEL("base_equipment_level"),

        // 잠재능력 (윗잠)
        GRADE("potential_option_grade"),
        POTENTIAL_1("potential_option_1"),
        POTENTIAL_2("potential_option_2"),
        POTENTIAL_3("potential_option_3"),

        // 에디셔널 잠재능력 (아랫잠)
        ADDITIONAL_GRADE("additional_potential_option_grade"),
        ADDITIONAL_1("additional_potential_option_1"),
        ADDITIONAL_2("additional_potential_option_2"),
        ADDITIONAL_3("additional_potential_option_3"),

        // 스타포스
        STARFORCE("starforce"),
        STARFORCE_SCROLL_FLAG("starforce_scroll_flag"),

        // 특수 스킬 반지
        SPECIAL_RING_LEVEL("special_ring_level"),

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

    /**
     * 필드 매퍼 초기화 (#240 V4: 13개 필드 매핑)
     */
    @PostConstruct
    public void initMappers() {
        // 기본 정보
        fieldMappers.put(JsonField.SLOT, (p, item) -> item.setPart(p.getText()));
        fieldMappers.put(JsonField.PART, (p, item) -> item.setItemEquipmentPart(p.getText()));
        fieldMappers.put(JsonField.NAME, (p, item) -> item.setItemName(p.getText()));
        fieldMappers.put(JsonField.ICON, (p, item) -> item.setItemIcon(p.getText()));
        fieldMappers.put(JsonField.LEVEL, this::parseLevel);

        // 잠재능력 (윗잠) - "null" 문자열 필터링
        fieldMappers.put(JsonField.GRADE, (p, item) -> {
            String grade = p.getText();
            // "null" 문자열, 빈 문자열, 실제 null 모두 제외
            if (grade != null && !grade.trim().isEmpty() && !"null".equalsIgnoreCase(grade.trim())) {
                item.setGrade(grade);
            }
        });
        fieldMappers.put(JsonField.POTENTIAL_1, this::parsePotential);
        fieldMappers.put(JsonField.POTENTIAL_2, this::parsePotential);
        fieldMappers.put(JsonField.POTENTIAL_3, this::parsePotential);

        // 에디셔널 잠재능력 (아랫잠) - "null" 문자열 필터링
        fieldMappers.put(JsonField.ADDITIONAL_GRADE, (p, item) -> {
            String grade = p.getText();
            // "null" 문자열, 빈 문자열, 실제 null 모두 제외
            if (grade != null && !grade.trim().isEmpty() && !"null".equalsIgnoreCase(grade.trim())) {
                item.setAdditionalGrade(grade);
            }
        });
        fieldMappers.put(JsonField.ADDITIONAL_1, this::parseAdditionalPotential);
        fieldMappers.put(JsonField.ADDITIONAL_2, this::parseAdditionalPotential);
        fieldMappers.put(JsonField.ADDITIONAL_3, this::parseAdditionalPotential);

        // 스타포스
        fieldMappers.put(JsonField.STARFORCE, this::parseStarforce);
        fieldMappers.put(JsonField.STARFORCE_SCROLL_FLAG, (p, item) -> item.setStarforceScrollFlag(p.getText()));

        // 특수 스킬 반지
        fieldMappers.put(JsonField.SPECIAL_RING_LEVEL, this::parseSpecialRingLevel);
    }

    /**
     * ✅ P0: 최상위 파이프라인 (비즈니스 의도만 노출)
     * <p>기본 item_equipment 배열 파싱</p>
     */
    public List<CubeCalculationInput> parseCubeInputs(byte[] rawJsonData) {
        return parseCubeInputsForPreset(rawJsonData, 0); // 0 = item_equipment (현재 장착)
    }

    /**
     * 프리셋별 장비 데이터 파싱 (#240 V4)
     *
     * @param rawJsonData 장비 JSON 데이터
     * @param presetNo 프리셋 번호 (0=현재장착, 1=프리셋1, 2=프리셋2, 3=프리셋3)
     * @return 파싱된 큐브 계산 입력 목록
     */
    public List<CubeCalculationInput> parseCubeInputsForPreset(byte[] rawJsonData, int presetNo) {
        if (rawJsonData == null || rawJsonData.length == 0) return new ArrayList<>();

        String targetField = resolvePresetFieldName(presetNo);
        TaskContext context = TaskContext.of("Parser", "StreamingParse", "preset" + presetNo);

        // [패턴 6] 예외 세탁 및 실행
        return executor.executeWithTranslation(
                () -> this.executeParsingProcessForField(rawJsonData, targetField, context),
                ExceptionTranslator.forMaple(),
                context
        );
    }

    /**
     * 프리셋 번호에 해당하는 JSON 필드명 반환
     */
    private String resolvePresetFieldName(int presetNo) {
        return switch (presetNo) {
            case 1 -> "item_equipment_preset_1";
            case 2 -> "item_equipment_preset_2";
            case 3 -> "item_equipment_preset_3";
            default -> "item_equipment"; // 0 또는 기타 = 현재 장착
        };
    }

    /**
     * ✅ P0: 자원 생명주기 관리 (try-with-resources 대체)
     */
    private List<CubeCalculationInput> executeParsingProcess(byte[] rawJsonData, TaskContext context) throws IOException {
        return executeParsingProcessForField(rawJsonData, "item_equipment", context);
    }

    /**
     * 특정 필드명으로 파싱 (프리셋 지원)
     */
    private List<CubeCalculationInput> executeParsingProcessForField(byte[] rawJsonData, String fieldName, TaskContext context) throws IOException {
        InputStream inputStream = createInputStream(rawJsonData);
        JsonParser parser = factory.createParser(inputStream);

        // [패턴 1] executeWithFinally를 통한 자원 해제 보장
        return executor.executeWithFinally(
                () -> this.doStreamParseForField(parser, fieldName),
                () -> this.closeResources(inputStream, parser),
                context
        );
    }

    /**
     * 실제 스트리밍 파싱 로직
     */
    private List<CubeCalculationInput> doStreamParse(JsonParser parser) throws IOException {
        return doStreamParseForField(parser, "item_equipment");
    }

    /**
     * 특정 필드명으로 스트리밍 파싱 (#240 V4)
     */
    private List<CubeCalculationInput> doStreamParseForField(JsonParser parser, String fieldName) throws IOException {
        List<CubeCalculationInput> resultList = new ArrayList<>();
        findStartArrayForField(parser, fieldName);

        if (parser.currentToken() == JsonToken.START_ARRAY) {
            parseItemArray(parser, resultList);
        }
        return resultList;
    }

    private void findStartArray(JsonParser parser) throws IOException {
        findStartArrayForField(parser, "item_equipment");
    }

    /**
     * 지정된 필드명의 배열 시작 위치 탐색 (#240 V4)
     */
    private void findStartArrayForField(JsonParser parser, String fieldName) throws IOException {
        while (parser.nextToken() != null) {
            if (fieldName.equals(parser.currentName())) {
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
                // hasBasicInfo(): 잠재능력 없는 장비도 포함 (특수스킬반지 등)
                if (depth-- == 1 && currentItem != null && currentItem.hasBasicInfo()) resultList.add(currentItem);
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

    /**
     * 에디셔널 잠재능력 파싱 (#240 V4)
     */
    private void parseAdditionalPotential(JsonParser parser, CubeCalculationInput item) throws IOException {
        String val = parser.getText();
        if (val != null && !val.trim().isEmpty()) {
            item.getAdditionalOptions().add(val);
        }
    }

    /**
     * 스타포스 파싱 (#240 V4)
     * <p>문자열 "22" → int 22 변환</p>
     */
    private void parseStarforce(JsonParser parser, CubeCalculationInput item) throws IOException {
        int starVal = (parser.currentToken() == JsonToken.VALUE_NUMBER_INT)
                ? parser.getIntValue()
                : statParser.parseNum(parser.getText());

        if (starVal >= 0) {
            item.setStarforce(starVal);
        }
    }

    /**
     * 특수 스킬 반지 레벨 파싱
     * <p>리스트레인트링, 컨티뉴어스링 등 (0~5)</p>
     */
    private void parseSpecialRingLevel(JsonParser parser, CubeCalculationInput item) throws IOException {
        int level = (parser.currentToken() == JsonToken.VALUE_NUMBER_INT)
                ? parser.getIntValue()
                : statParser.parseNum(parser.getText());

        if (level >= 0) {
            item.setSpecialRingLevel(level);
        }
    }

    /**
     * GZIP 압축 여부를 확인하고 필요 시 해제 (P1-6: 3중 해제 방지)
     *
     * <p>GZIP 매직 넘버(0x1F 0x8B)를 확인하여 압축된 경우 해제합니다.
     * 이미 해제된 데이터는 그대로 반환합니다.</p>
     *
     * @param data 원본 바이트 배열 (GZIP 또는 plain)
     * @return 해제된 바이트 배열
     */
    public byte[] decompressIfNeeded(byte[] data) {
        if (data == null || data.length < 2) {
            return data;
        }
        if (data[0] != (byte) 0x1F || data[1] != (byte) 0x8B) {
            return data; // plain data
        }
        TaskContext context = TaskContext.of("Parser", "DecompressIfNeeded");
        return executor.executeWithTranslation(
                () -> {
                    InputStream is = new GZIPInputStream(new ByteArrayInputStream(data));
                    return is.readAllBytes();
                },
                ExceptionTranslator.forMaple(),
                context
        );
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