package rs.sud.eaukcija.komatching;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime inputs and bounded review-candidate settings for issue #37. */
@Component
@ConfigurationProperties(prefix = "ko.structured-match")
public class StructuredKoMatchProperties {

    private Path dictionaryDirectory = Path.of("data", "address-registry-ko-dictionary");

    public Path getDictionaryDirectory() {
        return dictionaryDirectory;
    }

    public void setDictionaryDirectory(Path dictionaryDirectory) {
        this.dictionaryDirectory = dictionaryDirectory;
    }

    void validate() {
        if (dictionaryDirectory == null) {
            throw new KoStructuredMatchException(
                    "INVALID_CONFIGURATION", "ko.structured-match.dictionary-directory is required");
        }
    }
}
