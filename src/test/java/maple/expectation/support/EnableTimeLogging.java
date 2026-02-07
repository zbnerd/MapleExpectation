package maple.expectation.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to enable time logging for test execution.
 *
 * <p>When applied to a test class, this annotation enables timing information
 * to be logged for each test method. Useful for performance monitoring and
 * identifying slow tests.
 *
 * <p>Usage:
 * <pre>
 * &#64;EnableTimeLogging
 * &#64;SpringBootTest
 * class MyTest { }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EnableTimeLogging {
  /**
   * Whether to log time in milliseconds (true) or seconds (false).
   *
   * @return true for milliseconds, false for seconds
   */
  boolean milliseconds() default true;
}
