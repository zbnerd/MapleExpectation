package maple.expectation.setup;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.support.SpringBootTestWithTimeLogging;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;


@Slf4j
//@Commit
@SpringBootTestWithTimeLogging
@TestPropertySource(properties = "app.optimization.use-compression=false")
public class DataSeedingTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 목표 데이터 개수 (100만 건)
    private static final int TOTAL_COUNT = 1_000_000;
    // 한 번에 보낼 배치 사이즈 (1만 건씩 끊어서 전송)
    private static final int BATCH_SIZE = 10_000;

    @Test
    @DisplayName("🚀 100만 건 더미 데이터 적재 (Data Seeding)")
    @Disabled
    void insertOneMillionData() {
        log.info(">>> 데이터 적재 시작: 총 {}건", TOTAL_COUNT);

        // DB 컬럼명(snake_case)을 정확히 맞춰야 합니다.
        String sql = "INSERT INTO item_equipment " +
                "(item_name, star_force, part, potential_grade, preset_no, golden_hammer) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

        List<Object[]> batchArgs = new ArrayList<>();
        Random random = new Random();

        for (int i = 1; i <= TOTAL_COUNT; i++) {
            // 더미 데이터 생성 (나중에 검색 튜닝할 때 쓸 수 있게 랜덤값 부여)
            batchArgs.add(new Object[]{
                    "ITEM_" + i,                    // item_name (검색용)
                    random.nextInt(26),             // star_force (0~25성)
                    getRandomPart(random),          // part (장갑, 신발 등)
                    getRandomGrade(random),         // potential_grade (레전드리, 유니크 등)
                    1,                              // preset_no
                    random.nextBoolean()            // golden_hammer
            });

            // 배치 사이즈(1만개)가 찰 때마다 DB로 전송하고 메모리 비움
            if (i % BATCH_SIZE == 0) {
                jdbcTemplate.batchUpdate(sql, batchArgs);
                batchArgs.clear();
                log.info(">>> {}건 저장 완료...", i);
            }
        }

    }

    // --- 랜덤 데이터 생성 헬퍼 메서드 ---

    private String getRandomPart(Random random) {
        String[] parts = {"Gloves", "Shoes", "Cape", "Belt", "Shoulder"};
        return parts[random.nextInt(parts.length)];
    }

    private String getRandomGrade(Random random) {
        int r = random.nextInt(100);
        if (r < 5) return "LEGENDARY"; // 5% 확률
        if (r < 15) return "UNIQUE";   // 10% 확률
        if (r < 40) return "EPIC";     // 25% 확률
        return "RARE";                 // 60% 확률
    }
}
