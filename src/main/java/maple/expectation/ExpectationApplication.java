package maple.expectation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ExpectationApplication {

	public static void main(String[] args) {
		SpringApplication.run(ExpectationApplication.class, args);
	}

}
