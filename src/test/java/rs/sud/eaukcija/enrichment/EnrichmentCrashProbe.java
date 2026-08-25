package rs.sud.eaukcija.enrichment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Test-only child process that dies after durably starting one enrichment item. */
public final class EnrichmentCrashProbe {

    private EnrichmentCrashProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 9) {
            throw new IllegalArgumentException("expected nine non-secret work arguments");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                requiredEnvironment("ENRICHMENT_CRASH_DB_URL"),
                requiredEnvironment("ENRICHMENT_CRASH_DB_USER"),
                requiredEnvironment("ENRICHMENT_CRASH_DB_PASSWORD"));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EnrichmentRunRepository repository = new EnrichmentRunRepository(
                new JdbcTemplate(dataSource), null, objectMapper);
        UUID runId = UUID.fromString(args[0]);
        long auctionId = Long.parseLong(args[1]);
        EnrichmentVersions versions = new EnrichmentVersions(args[2], args[3], args[4]);
        EnrichmentWorkItem item = new EnrichmentWorkItem(
                auctionId,
                UUID.fromString(args[5]),
                args[6],
                args[7],
                versions.workKey(auctionId, args[6], args[7]),
                objectMapper.readTree(new String(
                        Base64.getUrlDecoder().decode(args[8]), StandardCharsets.UTF_8)));
        repository.startItem(
                runId,
                2,
                new EnrichmentCandidate(item, Instant.now(), false),
                versions);
        System.out.println("ENRICHMENT_ITEM_DURABLY_STARTED");
        System.out.flush();
        Runtime.getRuntime().halt(29);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
