package rs.sud.eaukcija.komatching;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import rs.sud.eaukcija.SudAukcijeApplication;

/** Operator entrypoint for a transactional structured-KO population match. */
public final class StructuredKoMatchCli {

    private StructuredKoMatchCli() {
    }

    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SudAukcijeApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            StructuredKoMatchService.RunResult result = context.getBean(StructuredKoMatchService.class).run();
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        }
    }
}
