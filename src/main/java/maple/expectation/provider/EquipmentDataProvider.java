package maple.expectation.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.executor.LogicExecutor; // ✅ 주입
import maple.expectation.global.executor.TaskContext; // ✅ 관측성
import maple.expectation.global.executor.strategy.ExceptionTranslator; // ✅ 예외 세탁
import maple.expectation.util.GzipUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentDataProvider {

    private final EquipmentFetchProvider fetchProvider;
    private final ObjectMapper objectMapper;
    private final LogicExecutor executor; // ✅ 지능형 실행 엔진 주입

    @Value("${app.optimization.use-compression:true}")
    private boolean USE_COMPRESSION;

    /**
     * ✅ [V3] 원본 데이터 획득 (비동기 및 실행기 통합)
     */
    public CompletableFuture<byte[]> getRawEquipmentData(String ocid) {
        TaskContext context = TaskContext.of("EquipmentProvider", "GetRawData", ocid); //

        // supplyAsync 내부 로직을 executor로 보호하여 예외 및 지표 추적
        return CompletableFuture.supplyAsync(() ->
                executor.execute(() -> fetchProvider.fetchWithCache(ocid), context)
        ).thenApply(response -> serializeResponse(response, context));
    }

    /**
     * ✅ [V2] Response DTO 획득
     */
    public CompletableFuture<EquipmentResponse> getEquipmentResponse(String ocid) {
        return CompletableFuture.completedFuture(
                executor.execute(
                        () -> fetchProvider.fetchWithCache(ocid),
                        TaskContext.of("EquipmentProvider", "GetResponse", ocid)
                )
        );
    }

    /**
     * ✅ 데이터 스트리밍 평탄화
     * try-catch를 제거하고 executeVoid와 ExceptionTranslator 활용
     *
     * <p><b>Issue #195 ADR:</b> .join()은 StreamingResponseBody 컨텍스트에서 허용됨.
     * 이 메서드는 GameCharacterControllerV3에서 StreamingResponseBody 람다 내에서 호출되므로
     * Tomcat 스레드는 이미 즉시 반환되고, Spring의 async 스레드 풀에서 실행됨.
     * OutputStream 쓰기는 본질적으로 블로킹이므로 .join()이 적절함.</p>
     */
    public void streamAndDecompress(String ocid, OutputStream os) {
        TaskContext context = TaskContext.of("EquipmentProvider", "StreamData", ocid);

        executor.executeVoid(() -> {
            // Note: .join() is acceptable here - see ADR in Javadoc above
            byte[] rawData = getRawEquipmentData(ocid).join();

            // 파일/스트림 I/O 전용 번역기를 통한 예외 세탁
            executor.executeWithTranslation(() -> {
                os.write(GzipUtils.decompress(rawData).getBytes(StandardCharsets.UTF_8));
                os.flush();
                return null;
            }, ExceptionTranslator.forFileIO(), context);
        }, context);
    }

    /**
     * ✅  직렬화 및 압축 로직 평탄화
     * JSON 처리 및 기술적 예외 레이어 분리
     */
    private byte[] serializeResponse(EquipmentResponse response, TaskContext context) {
        return executor.executeWithTranslation(() -> { //
            // 1. JSON 직렬화
            String jsonString = objectMapper.writeValueAsString(response);

            // 2. 조건부 GZIP 압축
            if (USE_COMPRESSION) {
                return GzipUtils.compress(jsonString);
            }
            return jsonString.getBytes(StandardCharsets.UTF_8);

        }, ExceptionTranslator.forJson(), context); // JSON 전용 세탁기 적용
    }
}