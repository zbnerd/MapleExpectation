package maple.expectation.parser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.util.StatParser;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentStreamingParser {

    private final JsonFactory factory = new JsonFactory();

    /**
     * Raw Byte Data(GZIP 또는 Plain)를 입력받아 CubeCalculationInput 리스트로 변환
     */
    public List<CubeCalculationInput> parseCubeInputs(byte[] rawJsonData) throws IOException {
        if (rawJsonData == null || rawJsonData.length == 0) return new ArrayList<>();

        List<CubeCalculationInput> resultList = new ArrayList<>();

        InputStream inputStream = createInputStream(rawJsonData);
        JsonParser parser = factory.createParser(inputStream);

        // 1. "item_equipment" 필드를 찾을 때까지 스킵
        while (parser.nextToken() != null) {
            if ("item_equipment".equals(parser.currentName())) {
                parser.nextToken(); // [ (Start Array) 로 이동
                break;
            }
        }

        // 2. 배열 내부 순회
        if (parser.currentToken() == JsonToken.START_ARRAY) {
            parseItemArray(parser, resultList);
        }

        return resultList;
    }

    private InputStream createInputStream(byte[] data) throws IOException {
        InputStream is = new ByteArrayInputStream(data);
        // GZIP 매직 넘버 체크 (0x1F, 0x8B)
        if (data.length > 2 && data[0] == (byte) 0x1F && data[1] == (byte) 0x8B) {
            return new GZIPInputStream(is);
        }
        return is;
    }

    private void parseItemArray(JsonParser parser, List<CubeCalculationInput> resultList) throws IOException {
        CubeCalculationInput currentItem = new CubeCalculationInput();

        while (parser.nextToken() != JsonToken.END_ARRAY) {
            String fieldName = parser.currentName();
            if (fieldName == null) continue;

            switch (fieldName) {
                case "item_equipment_slot":
                    parser.nextToken();
                    if (currentItem.isReady()) {
                        resultList.add(currentItem);
                        currentItem = new CubeCalculationInput();
                    }
                    currentItem.setPart(parser.getText());
                    break;

                case "potential_option_grade":
                    parser.nextToken();
                    currentItem.setGrade(parser.getText());
                    break;

                case "base_equipment_level":
                    parser.nextToken();
                    if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                        currentItem.setLevel(parser.getIntValue());
                    } else if (parser.currentToken() == JsonToken.VALUE_STRING) {
                        currentItem.setLevel(StatParser.parseNum(parser.getText()));
                    }
                    break;

                case "item_name":
                    parser.nextToken();
                    currentItem.setItemName(parser.getText());
                    break;

                case "potential_option_1":
                case "potential_option_2":
                case "potential_option_3":
                    parser.nextToken();
                    String val = parser.getText();
                    if (val != null && !val.trim().isEmpty()) {
                        currentItem.getOptions().add(val);
                    }
                    break;
            }
        }
        // 마지막 아이템 추가
        if (currentItem.isReady()) {
            resultList.add(currentItem);
        }


    }

}