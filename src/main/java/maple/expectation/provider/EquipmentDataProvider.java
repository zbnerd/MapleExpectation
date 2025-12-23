package maple.expectation.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.exception.EquipmentDataProcessingException;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.util.GzipUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture; // 추가

@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentDataProvider {

    private final NexonApiClient nexonApiClient; // @Primary인 ResilientNexonApiClient 주입
    private final ObjectMapper objectMapper;

    @Value("${app.optimization.use-compression:true}")
    private boolean USE_COMPRESSION;

    /**
     * [V3 API용] Raw Data 제공 (비동기 처리)
     * 💡 반환 타입을 CompletableFuture<byte[]>로 변경
     */
    public CompletableFuture<byte[]> getRawEquipmentData(String ocid) {
        // 1. 비동기로 클라이언트 데이터 호출
        // 2. thenApply를 통해 결과가 오면 직렬화(Serialization) 수행
        return nexonApiClient.getItemDataByOcid(ocid)
                .thenApply(this::serializeResponse);
    }

    /**
     * [V2 API용] 객체 제공 (비동기 처리)
     * 💡 반환 타입을 CompletableFuture<EquipmentResponse>로 변경
     */
    public CompletableFuture<EquipmentResponse> getEquipmentResponse(String ocid) {
        return nexonApiClient.getItemDataByOcid(ocid);
    }

    /**
     * EquipmentResponse 객체를 byte[]로 변환 (압축 로직 포함)
     */
    private byte[] serializeResponse(EquipmentResponse response) {
        try {
            String jsonString = objectMapper.writeValueAsString(response);

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