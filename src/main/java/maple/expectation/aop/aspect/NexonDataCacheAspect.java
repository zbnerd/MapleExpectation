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

    @Around("@annotation(maple.expectation.aop.annotation.NexonDataCache)")
    public Object handleNexonCache(ProceedingJoinPoint joinPoint) throws Throwable {
        String ocid = (String) joinPoint.getArgs()[0];
        Class<?> returnType = ((MethodSignature) joinPoint.getSignature()).getReturnType();

        // 1. DB 유효 캐시 확인 (Fast Path)
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
                // Double Check
                Optional<CharacterEquipment> latest = equipmentRepository.findById(ocid);
                if (latest.isPresent() && isValid(latest.get())) {
                    return returnType.equals(CompletableFuture.class)
                            ? CompletableFuture.completedFuture(convertToResponse(latest.get()))
                            : convertToResponse(latest.get());
                }

                log.info("🔄 [AOP Cache Miss] API를 호출하고 DB를 갱신합니다: {}", ocid);
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

    /**
     * 💡 리팩토링 포인트: 더 이상 압축 해제를 고민하지 않습니다.
     * Converter가 이미 String으로 다 풀어놨기 때문에 그냥 읽기만 하면 됩니다.
     */
    private EquipmentResponse convertToResponse(CharacterEquipment entity) {
        try {
            return objectMapper.readValue(entity.getJsonContent(), EquipmentResponse.class);
        } catch (Exception e) {
            log.error("캐시 역직렬화 실패: ocid={}", entity.getOcid(), e);
            throw new EquipmentDataProcessingException("캐시 데이터 파싱 실패 (AOP)");
        }
    }

    /**
     * 💡 리팩토링 포인트: 더 이상 수동 압축을 하지 않습니다.
     * 엔티티에 String만 넘겨주면, 저장 시점에 Converter가 알아서 압축해서 DB에 넣습니다.
     */
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