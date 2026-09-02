package rs.sud.eaukcija.propertyreference;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Frozen held-out evidence attached to every production extraction run. */
@Component
public final class PropertyReferenceQualityProfile {

    public static final String RESOURCE = "property-reference/parser-v1-quality.json";
    public static final BigDecimal MIN_PRECISION = new BigDecimal("0.95");
    public static final BigDecimal MIN_RECALL = new BigDecimal("0.88");

    private final Profile profile;

    public PropertyReferenceQualityProfile(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            profile = objectMapper.readValue(input, Profile.class);
        } catch (IOException invalid) {
            throw new IllegalStateException("property-reference quality profile is unavailable", invalid);
        }
        validate(profile);
    }

    public Profile profile() {
        return profile;
    }

    private static void validate(Profile value) {
        if (!"property-reference-parser-quality-v1".equals(value.schemaVersion())
                || !PropertyReferenceParser.VERSION.equals(value.parserVersion())
                || value.corpusVersion() == null || value.corpusVersion().isBlank()
                || value.metricsSha256() == null
                || !value.metricsSha256().matches("[0-9a-f]{64}")
                || value.heldOutPrecision() == null
                || value.heldOutPrecision().compareTo(MIN_PRECISION) < 0
                || value.heldOutRecall() == null
                || value.heldOutRecall().compareTo(MIN_RECALL) < 0
                || value.heldOutNegativeFalsePositives() != 0) {
            throw new IllegalStateException("property-reference quality profile does not pass issue #19");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Profile(
            String schemaVersion,
            String parserVersion,
            String corpusVersion,
            String metricsSha256,
            BigDecimal heldOutPrecision,
            BigDecimal heldOutRecall,
            int heldOutNegativeFalsePositives) {
    }
}
