package rs.sud.eaukcija.komatching;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sud.eaukcija.addressregistry.KoDictionaryPublisherTestBridge;

class KoDictionaryPublisherCompatibilityTest {

    @TempDir
    Path tempDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void loaderAndMatcherConsumeRealPublisherDuplicateAndAliasOutput() throws Exception {
        Path artifact = KoDictionaryPublisherTestBridge.publishDuplicateNameArtifact(
                tempDirectory.resolve("duplicates"), objectMapper);
        KoDictionarySnapshot dictionary = new KoDictionarySnapshotLoader(objectMapper).load(artifact);
        StructuredKoMatcher matcher = new StructuredKoMatcher(
                dictionary, StructuredKoMatcher.DEFAULT_FUZZY_CANDIDATE_LIMIT);

        StructuredKoMatcher.Match disambiguated = matcher.match(
                new StructuredKoMatcher.Input(1, "Димитровград", "Златибор", "Čajetina-grad"));
        StructuredKoMatcher.Match alias = matcher.match(
                new StructuredKoMatcher.Input(2, "Цариброд", "Димитровград", "Димитровград"));

        assertThat(disambiguated.status()).isEqualTo(StructuredKoMatcher.Status.MATCHED);
        assertThat(disambiguated.method()).isEqualTo(StructuredKoMatcher.Method.MUNICIPALITY_CONTEXT);
        assertThat(disambiguated.matchedKoCode()).isEqualTo("746312");
        assertThat(disambiguated.candidates()).extracting(StructuredKoMatcher.Candidate::koCode)
                .containsExactly("702013", "746312");
        assertThat(disambiguated.candidates()).filteredOn(StructuredKoMatcher.Candidate::municipalityContextMatch)
                .singleElement().satisfies(candidate ->
                        assertThat(candidate.municipalityAliasReviews()).singleElement()
                                .satisfies(review -> assertThat(review.id()).isEqualTo("portal-cajetina-grad")));
        assertThat(alias.status()).isEqualTo(StructuredKoMatcher.Status.MATCHED);
        assertThat(alias.method()).isEqualTo(StructuredKoMatcher.Method.REVIEWED_ALIAS);
        assertThat(alias.candidates().get(0).aliasReviews()).singleElement()
                .satisfies(review -> assertThat(review.id()).isEqualTo("historical-caribrod"));
    }

    @Test
    void loaderPreservesPublisherGeneratedMultiParentRelationships() throws Exception {
        Path artifact = KoDictionaryPublisherTestBridge.publishMultiParentArtifact(
                tempDirectory.resolve("multi-parent"), objectMapper);
        KoDictionarySnapshot dictionary = new KoDictionarySnapshotLoader(objectMapper).load(artifact);

        KoDictionarySnapshot.KoEntry entry = dictionary.entriesByCode().get("702013");
        assertThat(entry).isNotNull();
        assertThat(entry.municipalities()).extracting(KoDictionarySnapshot.Municipality::code)
                .containsExactly("70201", "74631");
        assertThat(entry.settlements()).extracting(KoDictionarySnapshot.Settlement::code)
                .containsExactly("704156", "746126");
        assertThat(dictionary.normalizedIndex().get("DIMITROVGRAD").get(0).municipalityCodes())
                .containsExactly("70201", "74631");
    }
}
