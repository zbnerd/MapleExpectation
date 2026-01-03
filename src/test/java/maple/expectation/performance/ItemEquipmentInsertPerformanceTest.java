package maple.expectation.performance;

import org.junit.jupiter.api.Disabled;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v1.ItemEquipment;
import maple.expectation.repository.v1.ItemEquipmentRepository;
import maple.expectation.support.EnableTimeLogging;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Slf4j
@Transactional
@EnableTimeLogging
@SpringBootTest
@ActiveProfiles("test")
class ItemEquipmentInsertPerformanceTest {

    @Autowired
    private ItemEquipmentRepository itemEquipmentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 1만 건으로 테스트 (10만 건 이상은 시간이 너무 걸릴 수 있음)
    private static final int DATA_SIZE = 10000;

    @Test
    @DisplayName("1. JPA saveAll() 성능 측정")
    @Disabled
    void testJpaSaveAll() {
        // 1. 데이터 생성
        List<ItemEquipment> items = new ArrayList<>();
        for (int i = 0; i < DATA_SIZE; i++) {
            ItemEquipment item = new ItemEquipment();
            // Entity의 Setter를 사용
            item.setItemName("JPA_ITEM_" + i);
            item.setStarForce(new Random().nextInt(25));
            item.setPart("Gloves");
            item.setPotentialGrade("RARE");
            item.setPresetNo(1);
            item.setGoldenHammer(true);

            // 연관관계(gameCharacter)는 null로 둠 (단순 insert 속도 측정용)
            items.add(item);

        }

        // 2. 측정 및 실행
        itemEquipmentRepository.saveAll(items);
    }

    @Test
    @DisplayName("🚀 2. JdbcTemplate Batch 성능 측정")
    void testJdbcBatchUpdate() {
        // 1. SQL 준비 (DB 컬럼명 주의: item_name)
        String sql = "INSERT INTO item_equipment " +
                "(item_name, star_force, part, potential_grade, preset_no, golden_hammer) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

        // 2. 데이터 생성
        List<Object[]> batchArgs = new ArrayList<>();
        Random random = new Random();

        for (int i = 0; i < DATA_SIZE; i++) {
            batchArgs.add(new Object[]{
                    "JDBC_ITEM_" + i,      // item_name
                    random.nextInt(25),    // star_force
                    "Gloves",              // part
                    "RARE",                // potential_grade
                    1,                     // preset_no
                    true                   // golden_hammer
            });
        }

        log.info("🚀 JDBC Batch Insert 시작...");
        jdbcTemplate.batchUpdate(sql, batchArgs);
    }

}