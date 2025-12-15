package maple.expectation.repository.v2;

import maple.expectation.domain.v2.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * [Guest 포인트 차감]
     * 핵심: WHERE 조건에 'm.point >= :amount'를 추가하여
     * 잔액이 부족하면 업데이트가 아예 실행되지 않도록(반환값 0) 막습니다.
     * -> 락 없이도 정합성 보장!
     */
    @Modifying(clearAutomatically = true) // 쿼리 실행 후 영속성 컨텍스트 비움 (중요)
    @Query("UPDATE Member m SET m.point = m.point - :amount " +
            "WHERE m.uuid = :uuid AND m.point >= :amount")
    int decreasePoint(@Param("uuid") String uuid, @Param("amount") Long amount);

    /**
     * [Developer 포인트 증가]
     * 단순 증가이므로 조건 없이 더해줍니다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Member m SET m.point = m.point + :amount WHERE m.id = :id")
    int increasePoint(@Param("id") Long id, @Param("amount") Long amount);

    Optional<Member> findByUuid(String uuid);
}