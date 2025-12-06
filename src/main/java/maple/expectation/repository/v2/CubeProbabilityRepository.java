package maple.expectation.repository.v2;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CubeProbability;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Slf4j
@Repository("cubeProbabilityRepositoryV1")
public class CubeProbabilityRepository {

    // 🏆 성능 개선의 핵심: List -> Map 변경
    // Key: "레벨_부위_등급_슬롯", Value: 해당 조건의 확률 리스트
    private final Map<String, List<CubeProbability>> probabilityCache = new HashMap<>();

    @PostConstruct
    public void init() {
        try {
            log.info("[v1] CSV 큐브 확률 데이터 로딩 시작... (Map 캐싱 적용)");
            ClassPathResource resource = new ClassPathResource("data/cube_probability.csv");

            if (!resource.exists()) {
                log.warn("데이터 파일이 없습니다. (data/cube_probability.csv)");
                return;
            }

            CsvMapper mapper = new CsvMapper();
            CsvSchema schema = CsvSchema.emptySchema().withHeader();

            try (InputStream inputStream = resource.getInputStream()) {
                MappingIterator<CubeProbability> it = mapper.readerFor(CubeProbability.class)
                        .with(schema)
                        .readValues(inputStream);

                int count = 0;
                while (it.hasNext()) {
                    CubeProbability p = it.next();

                    // 🔑 검색용 키 생성 (예: "120_모자_레전드리_1")
                    String key = generateKey(p.getLevel(), p.getPart(), p.getGrade(), p.getSlot());

                    // Map에 분류해서 넣기 (이러면 나중에 찾을 때 리스트 전체를 안 뒤져도 됨)
                    probabilityCache.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
                    count++;
                }
                log.info("[v1] 로딩 완료! 총 {}건의 데이터를 Map에 적재했습니다. (Key 개수: {})", count, probabilityCache.size());
            }

        } catch (IOException e) {
            log.error("확률 데이터 로딩 중 에러 발생", e);
            throw new RuntimeException("확률 데이터 초기화 실패", e);
        }
    }

    /**
     * 🚀 O(1) 초고속 조회 메서드
     * 기존 stream().filter()를 제거하고 Map.get()으로 즉시 조회
     */
    public List<CubeProbability> findProbabilities(int level, String part, String grade, int slot) {
        String key = generateKey(level, part, grade, slot);

        // 캐시에서 바로 꺼내옴 (없으면 빈 리스트 반환)
        return probabilityCache.getOrDefault(key, Collections.emptyList());
    }

    // 키 생성 헬퍼 메서드
    private String generateKey(int level, String part, String grade, int slot) {
        return level + "_" + part + "_" + grade + "_" + slot;
    }

    // 테스트용 전체 조회 (필요 시 map values를 리스트로 변환)
    public List<CubeProbability> findAll() {
        return probabilityCache.values().stream()
                .flatMap(List::stream)
                .toList();
    }
}