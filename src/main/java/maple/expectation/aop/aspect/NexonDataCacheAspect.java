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
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

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

    @Around("@annotation(maple.expectation.aop.annotation.NexonDataCache) && args(ocid, ..)")
    public Object handleNexonCache(ProceedingJoinPoint joinPoint, String ocid) throws Throwable {

        // MethodSignature는 반환 타입 처리를 위해 유지합니다.
        Class<?> returnType = ((MethodSignature) joinPoint.getSignature()).getReturnType();

        // 1. DB 유효 캐시 확인 (Fast Path) - 인자로 받은 ocid 사용
        Optional<CharacterEquipment> cache = equipmentRepository.findById(ocid);
        if (cache.isPresent() && isValid(cache.get())) {
            log.info("🎯 [AOP Cache Hit] DB에서 데이터를 반환합니다: {}", ocid);
            EquipmentResponse response = convertToResponse(cache.get());

            return returnType.equals(CompletableFuture.class)
                    ? CompletableFuture.completedFuture(response)
                    : response;
        }

        // 2. 캐시 없거나 만료됨 -> 락 잡고 진행 (Slow Path)
        return lockStrategy.executeWithLock(ocid, () -> {
            try {
                // Double Check (인자로 받은 ocid 사용)
                Optional<CharacterEquipment> latest = equipmentRepository.findById(ocid);
                if (latest.isPresent() && isValid(latest.get())) {
                    EquipmentResponse response = convertToResponse(latest.get());
                    return returnType.equals(CompletableFuture.class)
                            ? CompletableFuture.completedFuture(response)
                            : response;
                }

                log.info("🔄 [AOP Cache Miss] API를 호출하고 DB를 갱신합니다: {}", ocid);

                // proceed() 시 인자를 전달하지 않아도 바인딩된 원본 인자로 자동 실행됩니다.
                Object result = joinPoint.proceed();

                if (result instanceof CompletableFuture<?> future) {
                    return future.thenApply(res -> {
                        saveToDb(ocid, (EquipmentResponse) res);
                        return res;
                    });
                }

                saveToDb(ocid, (EquipmentResponse) result);
                return result;

            } catch (Throwable e) {
                throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
            }
        });
    }

    private boolean isValid(CharacterEquipment e) {
        return e != null && e.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(15));
    }

    private EquipmentResponse convertToResponse(CharacterEquipment entity) {
        try {
            return objectMapper.readValue(entity.getJsonContent(), EquipmentResponse.class);
        } catch (Exception e) {
            log.error("캐시 역직렬화 실패: ocid={}", entity.getOcid(), e);
            throw new EquipmentDataProcessingException("캐시 데이터 파싱 실패 (AOP)");
        }
    }

    private void saveToDb(String ocid, EquipmentResponse res) {
        try {
            String json = objectMapper.writeValueAsString(res);

            CharacterEquipment entity = equipmentRepository.findById(ocid)
                    .orElseGet(() -> CharacterEquipment.builder()
                            .ocid(ocid)
                            .jsonContent(json)
                            .build());

            entity.updateData(json);
            equipmentRepository.saveAndFlush(entity);

        } catch (JsonProcessingException e) {
            throw new EquipmentDataProcessingException("데이터 직렬화 실패 (AOP)");
        }
    }
}