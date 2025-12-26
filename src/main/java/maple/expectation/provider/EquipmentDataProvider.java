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

import java.io.IOException;
import java.io.OutputStream;
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

    public CompletableFuture<byte[]> getRawEquipmentData(String ocid) {
        // 1. 비동기로 클라이언트 데이터 호출
        // 2. thenApply를 통해 결과가 오면 직렬화(Serialization) 수행
        return nexonApiClient.getItemDataByOcid(ocid)
                .thenApply(this::serializeResponse);
    }

    public CompletableFuture<EquipmentResponse> getEquipmentResponse(String ocid) {
        return nexonApiClient.getItemDataByOcid(ocid);
    }

    public void streamAndDecompress(String ocid, OutputStream os) {
        byte[] rawData = getRawEquipmentData(ocid).join();

        try {
            os.write(GzipUtils.decompress(rawData).getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            throw new EquipmentDataProcessingException("데이터 스트리밍 중 기술적 에러: " + e.getMessage());
        }
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