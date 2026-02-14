package maple.expectation.repository.v2;

import java.util.Optional;
import maple.expectation.domain.model.like.CharacterLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * CharacterLike JPA Repository
 *
 * <p>중복 좋아요 검사 및 통계 조회 기능 제공
 */
@Repository
public interface CharacterLikeRepository extends JpaRepository<CharacterLike, Long> {

  /**
   * 특정 캐릭터에 대해 특정 계정이 이미 좋아요를 눌렀는지 확인
   *
   * @param targetOcid 대상 캐릭터 OCID
   * @param likerAccountId 좋아요를 누른 넥슨 계정 식별자
   * @return 좋아요 존재 여부
   */
  boolean existsByTargetOcidAndLikerAccountId(String targetOcid, String likerAccountId);

  /**
   * 특정 캐릭터에 대한 특정 계정의 좋아요 조회
   *
   * @param targetOcid 대상 캐릭터 OCID
   * @param likerAccountId 좋아요를 누른 넥슨 계정 식별자
   * @return 좋아요 (Optional)
   */
  Optional<CharacterLike> findByTargetOcidAndLikerAccountId(
      String targetOcid, String likerAccountId);

  /**
   * 특정 캐릭터의 총 좋아요 수
   *
   * @param targetOcid 대상 캐릭터 OCID
   * @return 좋아요 수
   */
  long countByTargetOcid(String targetOcid);

  /**
   * 특정 계정이 누른 총 좋아요 수
   *
   * @param likerAccountId 넥슨 계정 식별자
   * @return 좋아요 수
   */
  long countByLikerAccountId(String likerAccountId);

  /**
   * 특정 캐릭터에 대한 특정 계정의 좋아요 삭제
   *
   * @param targetOcid 대상 캐릭터 OCID
   * @param likerAccountId 좋아요를 누른 넥슨 계정 식별자
   * @return 삭제된 행 수
   */
  @Modifying
  @Transactional
  long deleteByTargetOcidAndLikerAccountId(String targetOcid, String likerAccountId);
}
