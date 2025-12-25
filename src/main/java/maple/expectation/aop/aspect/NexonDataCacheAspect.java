package maple.expectation.aop.aspect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.EquipmentDataProcessingException;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.util.GzipUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class NexonDataCacheAspect {

    private final CharacterEquipmentRepository equipmentRepository;
    private final LockStrategy lockStrategy;
    private final ObjectMapper objectMapper;

    @Value("${app.optimization.use-compression:true}")
    private boolean USE_COMPRESSION;

    @Around("@annotation(maple.expectation.aop.annotation.NexonDataCache)")
    public Object handleNexonCache(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 첫 번째 인자를 OCID로 간주
        String ocid = (String) joinPoint.getArgs()[0];

        // 대상 메서드의 반환 타입 확인 (비동기 여부 판단용)
        Class<?> returnType = ((MethodSignature) joinPoint.getSignature()).getReturnType();

        // 2. DB 유효 캐시 확인 (Fast Path)
        Optional<CharacterEquipment> cache = equipmentRepository.findById(ocid);
        if (cache.isPresent() && isValid(cache.get())) {
            log.info("🎯 [AOP Cache Hit] DB에서 데이터를 반환합니다: {}", ocid);
            EquipmentResponse response = convertToResponse(cache.get());

            // 리턴 타입이 CompletableFuture면 감싸서 반환
            return returnType.equals(CompletableFuture.class)
                    ? CompletableFuture.completedFuture(response)
                    : response;
        }

        // 3. 캐시 없거나 만료됨 -> 락 잡고 진행 (Slow Path)
        return lockStrategy.executeWithLock(ocid, () -> {
            try {
                // Double Check (락 획득 후 재확인)
                Optional<CharacterEquipment> latest = equipmentRepository.findById(ocid);
                if (latest.isPresent() && isValid(latest.get())) {
                    return returnType.equals(CompletableFuture.class)
                            ? CompletableFuture.completedFuture(convertToResponse(latest.get()))
                            : convertToResponse(latest.get());
                }

                log.info("🔄 [AOP Cache Miss] API를 호출하고 DB를 갱신합니다: {}", ocid);

                Object result = joinPoint.proceed(); // 실제 메서드(RealClient) 실행

                // 비동기 처리 분기
                if (result instanceof CompletableFuture<?> future) {
                    return future.thenApply(res -> {
                        saveToDb(ocid, (EquipmentResponse) res);
                        return res;
                    });
                }

                saveToDb(ocid, (EquipmentResponse) result);
                return result;

            } catch (Throwable e) {
                // 람다 내부에서 발생한 Throwable을 RuntimeException으로 래핑
                throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
            }
        });
    }

    private boolean isValid(CharacterEquipment e) {
        return e != null && e.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(15));
    }

    private EquipmentResponse convertToResponse(CharacterEquipment entity) {
        try {
            byte[] data = entity.getRawData();
            String json = (data.length > 2 && data[0] == (byte) 0x1F)
                    ? GzipUtils.decompress(data)
                    : new String(data, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, EquipmentResponse.class);
        } catch (Exception e) {
            throw new EquipmentDataProcessingException("캐시 데이터 파싱 실패 (AOP)");
        }
    }

    private void saveToDb(String ocid, EquipmentResponse res) {
        try {
            String json = objectMapper.writeValueAsString(res);
            byte[] data = USE_COMPRESSION ? GzipUtils.compress(json) : json.getBytes(StandardCharsets.UTF_8);

            CharacterEquipment entity = equipmentRepository.findById(ocid)
                    .orElse(new CharacterEquipment(ocid, data));
            entity.updateData(data);

            equipmentRepository.saveAndFlush(entity);
        } catch (JsonProcessingException e) {
            throw new EquipmentDataProcessingException("데이터 직렬화 실패 (AOP)");
        }
    }
}