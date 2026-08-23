package rs.sud.eaukcija.coarselocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import rs.sud.eaukcija.SudAukcijeApplication;

/** Operator entrypoint for one transactional coarse-location population pass. */
public final class CoarseLocationResolutionCli {

    private CoarseLocationResolutionCli() {
    }

    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SudAukcijeApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            CoarseLocationResolutionService.RunResult result =
                    context.getBean(CoarseLocationResolutionService.class).run();
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        }
    }
}
