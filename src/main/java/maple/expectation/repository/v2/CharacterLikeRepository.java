package maple.expectation.repository.v2;

import maple.expectation.domain.CharacterLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * CharacterLike JPA Repository
 *
 * <p>중복 좋아요 검사 및 통계 조회 기능 제공</p>
 */
@Repository
public interface CharacterLikeRepository extends JpaRepository<CharacterLike, Long> {

    /**
     * 특정 캐릭터에 대해 특정 계정이 이미 좋아요를 눌렀는지 확인
     *
     * @param targetOcid       대상 캐릭터 OCID
     * @param likerFingerprint 좋아요를 누른 계정의 fingerprint
     * @return 좋아요 존재 여부
     */
    boolean existsByTargetOcidAndLikerFingerprint(String targetOcid, String likerFingerprint);

    /**
     * 특정 캐릭터에 대한 특정 계정의 좋아요 조회
     *
     * @param targetOcid       대상 캐릭터 OCID
     * @param likerFingerprint 좋아요를 누른 계정의 fingerprint
     * @return 좋아요 (Optional)
     */
    Optional<CharacterLike> findByTargetOcidAndLikerFingerprint(String targetOcid, String likerFingerprint);

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
     * @param likerFingerprint 계정의 fingerprint
     * @return 좋아요 수
     */
    long countByLikerFingerprint(String likerFingerprint);
}
