package rs.sud.eaukcija.enrichment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EnrichmentProperties.class)
public class EnrichmentExecutionConfiguration {
}
