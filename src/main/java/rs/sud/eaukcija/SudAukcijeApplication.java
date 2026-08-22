package rs.sud.eaukcija;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SudAukcijeApplication {
    public static void main(String[] args) {
        LegacyH2Preflight.warnIfPresent();
        SpringApplication.run(SudAukcijeApplication.class, args);
    }
}
