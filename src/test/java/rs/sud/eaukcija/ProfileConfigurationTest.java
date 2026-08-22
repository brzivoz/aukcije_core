package rs.sud.eaukcija;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.io.ClassPathResource;

class ProfileConfigurationTest {

    @Test
    void postgresProfilesShareTheFailClosedSchemaPolicy() throws IOException {
        Properties common = properties("application.properties");
        Properties test = properties("application-test.properties");

        assertThat(common.getProperty("spring.profiles.default")).isNull();
        assertThat(common.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(common.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(common.getProperty("spring.h2.console.enabled")).isEqualTo("false");
        assertThat(test.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(test.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(test.getProperty("spring.h2.console.enabled")).isEqualTo("false");
    }

    @Test
    void devAndProdDoNotEmbedDatabasePasswords() throws IOException {
        Properties dev = properties("application-dev.properties");
        Properties prod = properties("application-prod.properties");

        assertThat(dev.getProperty("spring.datasource.password")).isEqualTo("${AUKCIJE_DB_PASSWORD}");
        assertThat(prod.getProperty("spring.datasource.password")).isEqualTo("${AUKCIJE_DB_PASSWORD}");
        assertThat(prod.getProperty("spring.datasource.url")).isEqualTo("${AUKCIJE_DB_URL}");
        assertThat(prod.getProperty("spring.datasource.username")).isEqualTo("${AUKCIJE_DB_USER}");
    }

    @Test
    void automaticDdlAndH2ConsoleExistOnlyInTheExplicitLegacyProfile() throws IOException {
        Properties h2 = properties("application-local-h2.properties");

        assertThat(h2.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(h2.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
        assertThat(h2.getProperty("spring.h2.console.enabled")).isEqualTo("true");
    }

    @Test
    void startupWithoutAnExplicitDatabaseProfileFailsBeforeDatasourceCreation() {
        assertThatThrownBy(() ->
                new SpringApplicationBuilder(SudAukcijeApplication.class)
                        .web(WebApplicationType.NONE)
                        .run("--spring.main.banner-mode=off"))
                .hasStackTraceContaining("Exactly one database profile must be active")
                .hasStackTraceContaining("selected=[]");
    }

    private static Properties properties(String resource) throws IOException {
        Properties properties = new Properties();
        try (var stream = new ClassPathResource(resource).getInputStream()) {
            properties.load(stream);
        }
        return properties;
    }
}
