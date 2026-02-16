package maple.expectation.infrastructure.persistence.repository;

import java.util.Optional;
import maple.expectation.domain.v2.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

  /**
   * [Guest 포인트 차감] 핵심: WHERE 조건에 'm.point >= :amount'를 추가하여 잔액이 부족하면 업데이트가 아예 실행되지 않도록(반환값 0) 막습니다.
   * -> 락 없이도 정합성 보장!
   */
  @Modifying(clearAutomatically = true) // 쿼리 실행 후 영속성 컨텍스트 비움 (중요)
  @Query(
      "UPDATE Member m SET m.point = m.point - :amount "
          + "WHERE m.uuid = :uuid AND m.point >= :amount")
  int decreasePoint(@Param("uuid") String uuid, @Param("amount") Long amount);

  /** [Developer 포인트 증가] 단순 증가이므로 조건 없이 더해줍니다. */
  @Modifying(clearAutomatically = true)
  @Query("UPDATE Member m SET m.point = m.point + :amount WHERE m.id = :id")
  int increasePoint(@Param("id") Long id, @Param("amount") Long amount);

  /**
   * [Admin 포인트 증가 - UUID 기반] Admin에게 커피(후원)를 보낼 때 사용합니다. Admin의 fingerprint가 Member.uuid로 저장되어 있어야
   * 합니다.
   *
   * @param uuid Admin의 fingerprint (Member.uuid로 사용)
   * @param amount 증가할 포인트
   * @return 영향받은 행 수 (0이면 해당 Admin Member가 없음)
   */
  @Modifying(clearAutomatically = true)
  @Query("UPDATE Member m SET m.point = m.point + :amount WHERE m.uuid = :uuid")
  int increasePointByUuid(@Param("uuid") String uuid, @Param("amount") Long amount);

  Optional<Member> findByUuid(String uuid);
}
