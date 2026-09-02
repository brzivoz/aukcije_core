package rs.sud.eaukcija.komatching;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import rs.sud.eaukcija.SudAukcijeApplication;

/** Operator entrypoint for transactional structured plus extracted KO matching. */
public final class ExtractedKoMatchCli {

    private ExtractedKoMatchCli() {
    }

    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SudAukcijeApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            ExtractedKoMatchService.RunResult result = context.getBean(ExtractedKoMatchService.class).run();
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        }
    }
}
