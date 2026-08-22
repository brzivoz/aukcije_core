package rs.sud.eaukcija.testsupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import rs.sud.eaukcija.SudAukcijeApplication;

/** Starts the real application against a caller-owned PostgreSQL database. */
public final class PostgisApplication {

    private PostgisApplication() {
    }

    public static ConfigurableApplicationContext start(String jdbcUrl, String username, String password,
                                                        String... additionalArguments) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password));
        arguments.addAll(Arrays.asList(additionalArguments));

        return new SpringApplicationBuilder(SudAukcijeApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run(arguments.toArray(String[]::new));
    }
}
