package maple.expectation.domain.repository;

import java.util.List;
import maple.expectation.domain.v2.CubeProbability;
import maple.expectation.domain.v2.CubeType;

/**
 * 큐브 확률 데이터 저장소 인터페이스
 *
 * <p>CSV 파일에서 로드된 큐브 확률 데이터를 메모리에 캐싱하고 조회합니다.
 *
 * <p>구현체:
 *
 * <ul>
 *   <li>{@link maple.expectation.repository.v2.CubeProbabilityRepositoryImpl} - CSV 기반 인메모리 구현
 * </ul>
 */
public interface CubeProbabilityRepository {

  /**
   * 조건에 맞는 확률 데이터 조회
   *
   * @param type 큐브 종류 (BLACK, RED, ADDITIONAL)
   * @param level 장비 레벨
   * @param part 장비 부위
   * @param grade 잠재능력 등급
   * @param slot 슬롯 번호 (1, 2, 3)
   * @return 확률 데이터 리스트
   */
  List<CubeProbability> findProbabilities(
      CubeType type, int level, String part, String grade, int slot);

  /**
   * 전체 확률 데이터 조회
   *
   * @return 모든 확률 데이터
   */
  List<CubeProbability> findAll();

  /**
   * 테이블 버전 포함 확률 데이터 조회 (TOCTOU 방지)
   *
   * @param type 큐브 종류
   * @param level 장비 레벨
   * @param part 장비 부위
   * @param grade 잠재능력 등급
   * @param slot 슬롯 번호
   * @param tableVersion 테이블 버전
   * @return 확률 데이터 리스트
   */
  List<CubeProbability> findProbabilitiesByVersion(
      CubeType type, int level, String part, String grade, int slot, String tableVersion);

  /**
   * 현재 활성 테이블 버전 반환
   *
   * @return 테이블 버전 (예: "csv-v1.0")
   */
  String getCurrentTableVersion();
}
