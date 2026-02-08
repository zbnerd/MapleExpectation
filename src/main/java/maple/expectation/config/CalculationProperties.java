package maple.expectation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Calculation engine configuration properties.
 *
 * <p>Externalizes calculation logic versioning to support OCP (Open/Closed Principle). Version
 * bumps can be done via configuration without code modification.
 *
 * @see maple.expectation.service.v2.EquipmentService
 */
@Component
@ConfigurationProperties(prefix = "calculation")
public class CalculationProperties {

  /** Calculation logic version (used in cache keys) */
  private int logicVersion = 3;

  /** Probability table version (update when cube_tables change) */
  private String tableVersion = "2024.01.15";

  public int getLogicVersion() {
    return logicVersion;
  }

  public void setLogicVersion(int logicVersion) {
    this.logicVersion = logicVersion;
  }

  public String getTableVersion() {
    return tableVersion;
  }

  public void setTableVersion(String tableVersion) {
    this.tableVersion = tableVersion;
  }
}
