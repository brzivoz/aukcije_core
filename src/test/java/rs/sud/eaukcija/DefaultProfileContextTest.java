package rs.sud.eaukcija;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import rs.sud.eaukcija.client.EAukcijaClient;
import rs.sud.eaukcija.service.SyncService;

/**
 * The application still ships on H2, and adding Flyway must not change that.
 *
 * <p>Only the datasource URL is redirected, to keep the test off the developer's
 * real {@code ./data/aukcije} file. Everything asserted here — Flyway disabled,
 * {@code ddl-auto=update}, the bean graph — comes from the real
 * {@code application.properties}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:default-profile-smoke;DB_CLOSE_DELAY=-1")
class DefaultProfileContextTest {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EAukcijaClient client;

    @Autowired
    private SyncService syncService;

    @Test
    void theApplicationStillStartsOnH2WithFlywayDisabled() {
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");

        String product = new JdbcTemplate(dataSource)
                .queryForObject("SELECT h2version()", String.class);
        assertThat(product).isNotBlank();
    }

    @Test
    void theFlywayHistoryTableIsAbsentBecauseNoMigrationRan() {
        // The PostgreSQL/PostGIS migrations must not be attempted against H2.
        Integer tables = new JdbcTemplate(dataSource).queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE upper(table_name) = 'FLYWAY_SCHEMA_HISTORY'
                """, Integer.class);
        assertThat(tables).isZero();
    }

    @Test
    void theIngestBeansAreStillWired() {
        // Guards the constructor-injection change #16 made to EAukcijaClient:
        // an ambiguous constructor set breaks startup, not compilation.
        assertThat(client).isNotNull();
        assertThat(syncService).isNotNull();
    }
}
