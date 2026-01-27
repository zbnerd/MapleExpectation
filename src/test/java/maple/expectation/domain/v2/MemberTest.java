package maple.expectation.domain.v2;

import maple.expectation.global.error.exception.InsufficientPointException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Member 엔티티 테스트 (Issue #120, #194)
 *
 * <p>Rich Domain Model 비즈니스 로직 검증</p>
 */
class MemberTest {

    @Nested
    @DisplayName("포인트 잔액 확인 (hasEnoughPoint)")
    class HasEnoughPointTest {

        @Test
        @DisplayName("잔액이 충분하면 true 반환")
        void whenEnoughBalance_shouldReturnTrue() {
            // given
            Member member = Member.createGuest(1000L);

            // when & then
            assertThat(member.hasEnoughPoint(500L)).isTrue();
            assertThat(member.hasEnoughPoint(1000L)).isTrue();
        }

        @Test
        @DisplayName("잔액이 부족하면 false 반환")
        void whenInsufficientBalance_shouldReturnFalse() {
            // given
            Member member = Member.createGuest(500L);

            // when & then
            assertThat(member.hasEnoughPoint(501L)).isFalse();
            assertThat(member.hasEnoughPoint(1000L)).isFalse();
        }

        @Test
        @DisplayName("잔액이 0이면 모든 양수 금액에 false 반환")
        void whenZeroBalance_shouldReturnFalseForPositiveAmount() {
            // given
            Member member = Member.createGuest(0L);

            // when & then
            assertThat(member.hasEnoughPoint(1L)).isFalse();
            assertThat(member.hasEnoughPoint(0L)).isTrue();
        }
    }

    @Nested
    @DisplayName("포인트 차감 (deductPoints)")
    class DeductPointsTest {

        @Test
        @DisplayName("정상 차감 시 잔액 감소")
        void whenNormalDeduction_shouldDecreaseBalance() {
            // given
            Member member = Member.createGuest(1000L);

            // when
            member.deductPoints(300L);

            // then
            assertThat(member.getPoint()).isEqualTo(700L);
        }

        @Test
        @DisplayName("전액 차감 시 잔액 0")
        void whenFullDeduction_shouldBeZeroBalance() {
            // given
            Member member = Member.createGuest(500L);

            // when
            member.deductPoints(500L);

            // then
            assertThat(member.getPoint()).isZero();
        }

        @Test
        @DisplayName("잔액 부족 시 InsufficientPointException 발생")
        void whenInsufficientBalance_shouldThrowException() {
            // given
            Member member = Member.createGuest(100L);

            // when & then
            assertThatThrownBy(() -> member.deductPoints(200L))
                    .isInstanceOf(InsufficientPointException.class)
                    .hasMessageContaining("100")  // 보유 포인트
                    .hasMessageContaining("200"); // 필요 포인트
        }

        @Test
        @DisplayName("0 이하 금액 차감 시 IllegalArgumentException 발생")
        void whenNonPositiveAmount_shouldThrowException() {
            // given
            Member member = Member.createGuest(1000L);

            // when & then
            assertThatThrownBy(() -> member.deductPoints(0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("양수");

            assertThatThrownBy(() -> member.deductPoints(-100L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("양수");
        }

        @Test
        @DisplayName("null 금액 차감 시 IllegalArgumentException 발생")
        void whenNullAmount_shouldThrowException() {
            // given
            Member member = Member.createGuest(1000L);

            // when & then
            assertThatThrownBy(() -> member.deductPoints(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("팩토리 메서드")
    class FactoryMethodTest {

        @Test
        @DisplayName("createGuest: 랜덤 UUID 생성")
        void createGuest_shouldGenerateRandomUuid() {
            // when
            Member member1 = Member.createGuest(100L);
            Member member2 = Member.createGuest(100L);

            // then
            assertThat(member1.getUuid()).isNotNull();
            assertThat(member2.getUuid()).isNotNull();
            assertThat(member1.getUuid()).isNotEqualTo(member2.getUuid());
            assertThat(member1.getPoint()).isEqualTo(100L);
        }

        @Test
        @DisplayName("createSystemAdmin: 지정된 UUID 사용")
        void createSystemAdmin_shouldUseProvidedUuid() {
            // given
            String adminUuid = "admin-uuid-12345";

            // when
            Member admin = Member.createSystemAdmin(adminUuid, 10000L);

            // then
            assertThat(admin.getUuid()).isEqualTo(adminUuid);
            assertThat(admin.getPoint()).isEqualTo(10000L);
        }
    }
}
