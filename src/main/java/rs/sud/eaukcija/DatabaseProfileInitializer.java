package rs.sud.eaukcija;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.context.ApplicationContextException;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Requires one explicit database runtime before any datasource or web bean starts. */
public final class DatabaseProfileInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Set<String> DATABASE_PROFILES = Set.of("dev", "test", "prod", "local-h2");

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        List<String> selected = Arrays.stream(context.getEnvironment().getActiveProfiles())
                .filter(DATABASE_PROFILES::contains)
                .toList();
        if (selected.size() != 1) {
            throw new ApplicationContextException(
                    "Exactly one database profile must be active: dev, test, prod, or local-h2; selected="
                            + selected);
        }
    }
}
