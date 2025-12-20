package maple.expectation.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.exception.EquipmentDataProcessingException;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.util.GzipUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentDataProvider {

    private final NexonApiClient nexonApiClient; // @Primary인 Proxy 주입
    private final ObjectMapper objectMapper;

    @Value("${app.optimization.use-compression:true}")
    private boolean USE_COMPRESSION;

    /**
     * [V3 API용] Raw Data 제공 (Streaming + Optional GZIP)
     */
    public byte[] getRawEquipmentData(String ocid) {
        // 1. 프록시를 통해 최적화된(캐시된) 데이터 획득
        EquipmentResponse response = nexonApiClient.getItemDataByOcid(ocid);

        // 2. 스트리밍을 위한 직렬화 수행
        return serializeResponse(response);
    }

    /**
     * [V2 API용] 객체 제공
     */
    public EquipmentResponse getEquipmentResponse(String ocid) {
        return nexonApiClient.getItemDataByOcid(ocid);
    }

    /**
     * EquipmentResponse 객체를 byte[]로 변환 (압축 로직 포함)
     */
    private byte[] serializeResponse(EquipmentResponse response) {
        try {
            // 객체를 JSON 문자열로 변환
            String jsonString = objectMapper.writeValueAsString(response);

            // 설정에 따라 GZIP 압축 여부 결정
            if (USE_COMPRESSION) {
                return GzipUtils.compress(jsonString);
            }
            return jsonString.getBytes(StandardCharsets.UTF_8);

        } catch (JsonProcessingException e) {
            log.error("직렬화 중 오류 발생: {}", response.getDate(), e);
            throw new EquipmentDataProcessingException("데이터 직렬화 실패");
        }
    }
}