package rs.sud.eaukcija;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Proves automatic H2 DDL is reachable only through the explicit transition profile. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-h2")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:local-h2-profile;DB_CLOSE_DELAY=-1")
class LocalH2ProfileContextTest {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Test
    void h2AndAutomaticDdlRequireTheExplicitProfile() {
        assertThat(environment.getActiveProfiles()).containsExactly("local-h2");
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
        assertThat(environment.getProperty("spring.h2.console.enabled")).isEqualTo("true");

        String product = new JdbcTemplate(dataSource).queryForObject("SELECT h2version()", String.class);
        assertThat(product).isNotBlank();
    }
}
