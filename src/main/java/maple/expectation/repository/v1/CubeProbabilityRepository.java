package maple.expectation.repository.v1;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.CubeProbability;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Repository("cubeProbabilityRepositoryV1")
public class CubeProbabilityRepository {

    private final List<CubeProbability> probabilityList = new ArrayList<>();

    @PostConstruct
    public void init() {
        try {
            log.info("[v1] CSV 큐브 확률 데이터 로딩 시작...");
            ClassPathResource resource = new ClassPathResource("data/cube_probability.csv");

            if (!resource.exists()) {
                log.warn("데이터 파일이 없습니다. (data/cube_probability.csv)");
                return;
            }

            CsvMapper mapper = new CsvMapper();

            // 🌟 withHeader(): 첫 줄(헤더)을 키값으로 사용
            CsvSchema schema = CsvSchema.emptySchema().withHeader();

            try (InputStream inputStream = resource.getInputStream()) {
                MappingIterator<CubeProbability> it = mapper.readerFor(CubeProbability.class)
                        .with(schema)
                        .readValues(inputStream);

                while (it.hasNext()) {
                    probabilityList.add(it.next());
                }
            }

            log.info("[v1] 로딩 완료! 총 {}건의 확률 데이터가 메모리에 적재되었습니다.", probabilityList.size());

        } catch (IOException e) {
            log.error("확률 데이터 로딩 중 에러 발생", e);
            throw new RuntimeException("확률 데이터 초기화 실패", e);
        }
    }

    /**
     * 조건에 맞는 확률 목록 조회
     */
    public List<CubeProbability> findProbabilities(int level, String part, String grade, int slot) {
        return probabilityList.stream()
                .filter(p -> p.getLevel() == level)
                .filter(p -> p.getPart().equals(part))
                .filter(p -> p.getGrade().equals(grade))
                .filter(p -> p.getSlot() == slot)
                .collect(Collectors.toList());
    }

    // 테스트용 전체 조회
    public List<CubeProbability> findAll() {
        return Collections.unmodifiableList(probabilityList);
    }
}