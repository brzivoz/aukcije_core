package rs.sud.eaukcija;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fails context refresh, before the web connector opens, if PostGIS is absent. */
@Component
@Profile("!local-h2")
@DependsOnDatabaseInitialization
public class PostgisDatabasePreflight implements InitializingBean {

    private final JdbcTemplate jdbc;

    public PostgisDatabasePreflight(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        Boolean installed = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'postgis')",
                Boolean.class);
        if (!Boolean.TRUE.equals(installed)) {
            throw new IllegalStateException(
                    "PostGIS extension is missing; database startup is refused until it is restored");
        }

        String version = jdbc.queryForObject("SELECT PostGIS_Version()", String.class);
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("PostGIS functions are unavailable");
        }
    }
}
