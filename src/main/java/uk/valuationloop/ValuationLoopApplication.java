package uk.valuationloop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ValuationLoopApplication {

    public static void main(String[] args) {
        SpringApplication.run(ValuationLoopApplication.class, args);
    }
}
