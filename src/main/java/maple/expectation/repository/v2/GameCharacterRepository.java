package maple.expectation.repository.v2;

import jakarta.persistence.LockModeType;
import maple.expectation.domain.v2.GameCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

// 1. 인터페이스로 변경하고 JpaRepository 상속 (Entity, PK타입)
public interface GameCharacterRepository extends JpaRepository<GameCharacter, Long> {

    // 2. 일반 조회 (기존 findOptionalByUserIgn 대체)
    // Spring Data JPA가 메서드 이름을 분석해 자동으로 쿼리 생성
    Optional<GameCharacter> findByUserIgn(String userIgn);

    // 3. 비관적 락 조회 (기존 findByUserIgnWithPessimisticLock 대체)
    // @Lock 어노테이션 하나로 해결!
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM GameCharacter c WHERE c.userIgn = :userIgn")
    Optional<GameCharacter> findByUserIgnWithPessimisticLock(@Param("userIgn") String userIgn);

    // 4. [New] Caffeine Cache 전략을 위한 벌크 업데이트 쿼리
    // 스케줄러가 모아둔 좋아요 수를 한방에 DB에 반영할 때 사용
    @Modifying(clearAutomatically = true) // 쿼리 실행 후 영속성 컨텍스트 초기화
    @Query("UPDATE GameCharacter c SET c.likeCount = c.likeCount + :count WHERE c.userIgn = :userIgn")
    void incrementLikeCount(@Param("userIgn") String userIgn, @Param("count") Long count);
}