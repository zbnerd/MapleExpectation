package maple.expectation;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * Integration Test Configuration for module-infra
 *
 * <p>Provides minimal Spring Boot configuration for infrastructure integration tests. Only enables
 * JPA-related components for @DataJpaTest support.
 */
@TestConfiguration
@SpringBootApplication(scanBasePackages = "maple.expectation")
public class InfraTestConfiguration {}
