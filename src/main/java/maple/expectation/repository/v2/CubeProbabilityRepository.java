package maple.expectation.repository.v2;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.CubeProbability;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.global.error.exception.CubeDataInitializationException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Slf4j
@Repository("cubeProbabilityRepositoryV1")
public class CubeProbabilityRepository {

    // 🔑 캐시 키에 CubeType이 포함되어야 합니다. (예: BLACK_200_모자_레전드리_1)
    private final Map<String, List<CubeProbability>> probabilityCache = new HashMap<>();

    @PostConstruct
    public void init() {
        log.info("[v1] CSV 큐브 확률 데이터 로딩 시작... (CubeType 구분 적용)");

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

                // 💡 핵심: 큐브 종류(Black, Red 등)까지 포함하여 키 생성
                String key = generateKey(p.getCubeType(), p.getLevel(), p.getPart(), p.getGrade(), p.getSlot());

                probabilityCache.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
                count++;
            }

            if (count == 0) {
                throw new CubeDataInitializationException("CSV 파일 데이터가 비어있습니다.");
            }

            log.info("[v1] 로딩 완료! 총 {}건의 데이터를 적재했습니다. (Key 개수: {})", count, probabilityCache.size());

        } catch (IOException e) {
            log.error("확률 데이터 로딩 중 치명적 오류 발생", e);
            throw new CubeDataInitializationException("확률 데이터 파싱 중 I/O 오류 발생");
        }
    }

    /**
     * ✅ 수정: 큐브 종류(type)를 파라미터로 받아 정확한 확률 리스트를 반환합니다.
     */
    public List<CubeProbability> findProbabilities(CubeType type, int level, String part, String grade, int slot) {
        String key = generateKey(type, level, part, grade, slot);
        return probabilityCache.getOrDefault(key, Collections.emptyList());
    }

    /**
     * ✅ 수정: Key 생성 로직에 type.name() 추가
     */
    private String generateKey(CubeType type, int level, String part, String grade, int slot) {
        // type이 null일 경우를 대비해 기본값 처리를 하거나 예외를 던질 수 있습니다.
        String typeName = (type != null) ? type.name() : "BLACK";
        return typeName + "_" + level + "_" + part + "_" + grade + "_" + slot;
    }

    public List<CubeProbability> findAll() {
        return probabilityCache.values().stream()
                .flatMap(List::stream)
                .toList();
    }
}