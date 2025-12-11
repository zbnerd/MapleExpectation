package maple.expectation.repository.v2;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.CubeProbability;
import maple.expectation.exception.CubeDataInitializationException; // 커스텀 예외 Import
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Slf4j
@TraceLog
@Repository("cubeProbabilityRepositoryV1")
public class CubeProbabilityRepository {

    private final Map<String, List<CubeProbability>> probabilityCache = new HashMap<>();

    @PostConstruct
    public void init() {
        log.info("[v1] CSV 큐브 확률 데이터 로딩 시작... (Map 캐싱 적용)");

        // 데이터 파일은 필수 리소스이므로, 없으면 에러를 내야 함
        ClassPathResource resource = new ClassPathResource("data/cube_probability.csv");
        if (!resource.exists()) {
            throw new CubeDataInitializationException("필수 데이터 파일이 없습니다: data/cube_probability.csv");
        }

        try (InputStream inputStream = resource.getInputStream()) {
            CsvMapper mapper = new CsvMapper();
            CsvSchema schema = CsvSchema.emptySchema().withHeader();

            MappingIterator<CubeProbability> it = mapper.readerFor(CubeProbability.class)
                    .with(schema)
                    .readValues(inputStream);

            int count = 0;
            while (it.hasNext()) {
                CubeProbability p = it.next();
                String key = generateKey(p.getLevel(), p.getPart(), p.getGrade(), p.getSlot());
                probabilityCache.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
                count++;
            }

            // 데이터가 하나도 없어도 문제로 간주 (선택 사항)
            if (count == 0) {
                throw new CubeDataInitializationException("CSV 파일은 존재하나 데이터가 비어있습니다.");
            }

            log.info("[v1] 로딩 완료! 총 {}건의 데이터를 Map에 적재했습니다. (Key 개수: {})", count, probabilityCache.size());

        } catch (IOException e) {
            // 구체적인 에러 메시지와 함께 커스텀 예외 던짐
            log.error("확률 데이터 로딩 중 치명적 오류 발생", e);
            throw new CubeDataInitializationException("확률 데이터(CSV) 파싱 중 I/O 오류 발생", e);
        } catch (Exception e) {
            // 그 외 예상치 못한 파싱 에러 (포맷 불일치 등)
            log.error("확률 데이터 처리 중 알 수 없는 오류 발생", e);
            throw new CubeDataInitializationException("확률 데이터 처리 실패: " + e.getMessage(), e);
        }
    }

    public List<CubeProbability> findProbabilities(int level, String part, String grade, int slot) {
        String key = generateKey(level, part, grade, slot);
        return probabilityCache.getOrDefault(key, Collections.emptyList());
    }

    private String generateKey(int level, String part, String grade, int slot) {
        return level + "_" + part + "_" + grade + "_" + slot;
    }

    public List<CubeProbability> findAll() {
        return probabilityCache.values().stream()
                .flatMap(List::stream)
                .toList();
    }
}