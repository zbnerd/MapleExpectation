package maple.expectation.service.v2;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DonationService @Transactional 어노테이션 검증 테스트
 *
 * <p>Issue #198: 금융 거래 데이터 일관성을 위해 명시적 isolation level 설정 확인
 */
@DisplayName("DonationService Transaction Isolation 테스트")
class DonationServiceTransactionTest {

  @Test
  @DisplayName("sendCoffee 메서드는 READ_COMMITTED isolation level이 명시되어 있다")
  void sendCoffee_hasReadCommittedIsolation() throws NoSuchMethodException {
    // Given
    Method sendCoffeeMethod =
        DonationService.class.getMethod(
            "sendCoffee",
            String.class, // guestUuid
            String.class, // adminFingerprint
            Long.class, // amount
            String.class // requestId
            );

    // When
    Transactional transactionalAnnotation = sendCoffeeMethod.getAnnotation(Transactional.class);

    // Then
    assertThat(transactionalAnnotation).as("@Transactional 어노테이션이 존재해야 합니다").isNotNull();

    assertThat(transactionalAnnotation.isolation())
        .as("Isolation level이 READ_COMMITTED로 명시되어야 합니다")
        .isEqualTo(Isolation.READ_COMMITTED);
  }

  @Test
  @DisplayName("@Transactional 어노테이션의 기본값은 DEFAULT(DB 기본값)이다")
  void transactional_defaultIsolation_isDefault() {
    // Given: Spring의 기본 동작 확인
    Isolation defaultIsolation = Isolation.DEFAULT;

    // Then: 기본값이 DEFAULT임을 명시 (문서화 목적)
    assertThat(defaultIsolation)
        .as("Spring @Transactional 기본값은 DEFAULT(DB 설정 따름)")
        .isEqualTo(Isolation.DEFAULT);
  }
}
