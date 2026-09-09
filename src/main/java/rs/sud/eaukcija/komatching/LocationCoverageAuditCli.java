package rs.sud.eaukcija.komatching;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.sud.eaukcija.propertyreference.ParsedPropertyReference;
import rs.sud.eaukcija.propertyreference.PropertyReferenceExtractionRepository;
import rs.sud.eaukcija.propertyreference.PropertyReferenceParser;
import rs.sud.eaukcija.propertyreference.PropertyReferenceType;

/** Offline comparison of the same private snapshot frame against one pinned dictionary.
 * This measures pipeline coverage, NOT precision/recall against independent ground truth.
 * No Spring context, database writes, source clients, or description export. */
public final class LocationCoverageAuditCli {
    private LocationCoverageAuditCli() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("usage: <canonical-input.ndjson> <dictionary-directory> <report.json>");
        audit(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
    }

    public static void audit(Path input, Path dictionaryPath, Path output) throws Exception {
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("report cannot overwrite source evidence");
        }
        ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        KoDictionarySnapshot dictionary = new KoDictionarySnapshotLoader(mapper).load(dictionaryPath);
        Map<String, Totals> totals = new TreeMap<>();
        List<Map<String, Object>> unresolved = new ArrayList<>();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int population = 0;
        try (var lines = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            for (String line; (line = lines.readLine()) != null;) {
                if (line.isBlank()) continue;
                if (++population > 10_000 || line.length() > 1_000_000) throw new IllegalArgumentException("audit frame limit exceeded");
                digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
                JsonNode canonical = mapper.readTree(line);
                for (PropertyReferenceParser parser : List.of(PropertyReferenceParser.legacyV1(),
                        PropertyReferenceParser.legacyV2(), new PropertyReferenceParser())) {
                    var parsed = parser.parse(canonical);
                    Totals count = totals.computeIfAbsent(parsed.parserVersion(), ignored -> new Totals());
                    long auction = canonical.path("auctionId").asLong();
                    String cadastral = text(canonical, "cadastral"), place = text(canonical, "placeName"), municipality = text(canonical, "municipality");
                    var structured = new StructuredKoMatcher(dictionary, 0).match(
                            new StructuredKoMatcher.Input(auction, cadastral, place, municipality));
                    var structuredEvidence = new ExtractedKoMatcher.StructuredEvidence(structured.inputFingerprint(),
                            structured.status(), structured.method(), structured.rationale(), structured.matchedKoCode(),
                            cadastral, place, municipality, dictionary.version(), dictionary.sourceGpkgSha256(),
                            dictionary.normalizerVersion(), dictionary.aliasDatasetVersion(), dictionary.aliasSha256(),
                            dictionary.aliasDatasetVersion(), dictionary.municipalityAliasSha256());
                    boolean parcelPresent = false, eligibleParcel = false;
                    for (ParsedPropertyReference reference : parsed.references()) {
                        if (reference.type() == PropertyReferenceType.STRUCTURED_LOCATION) continue;
                        count.references.merge(reference.type().name(), 1L, Long::sum);
                        if (reference.type() != PropertyReferenceType.PARCEL && reference.type() != PropertyReferenceType.ADDRESS) continue;
                        if (reference.type() == PropertyReferenceType.PARCEL) parcelPresent = true;
                        UUID id = PropertyReferenceExtractionRepository.referenceId(auction, parsed.parserVersion(), reference.canonicalKey());
                        boolean textKo = parsed.references().stream().anyMatch(value ->
                                value.type() == PropertyReferenceType.CADASTRAL_MUNICIPALITY
                                && java.util.Objects.equals(value.rawKo(), reference.rawKo())
                                && java.util.Objects.equals(value.normalizedKo(), reference.normalizedKo()));
                        var provenance = textKo ? ExtractedKoMatcher.KoProvenance.TEXT_EXTRACTED
                                : reference.rawKo() == null ? ExtractedKoMatcher.KoProvenance.UNRESOLVED
                                : ExtractedKoMatcher.KoProvenance.STRUCTURED_FALLBACK;
                        var ko = new ExtractedKoMatcher(dictionary, 0).match(new ExtractedKoMatcher.Input(id, auction,
                                reference.rawKo(), reference.normalizedKo(), place, municipality, provenance), structuredEvidence);
                        boolean eligible = rs.sud.eaukcija.spatial.LocationSelectionSql.publishableExtractionStatus(reference.status().name())
                                && ko.status() == StructuredKoMatcher.Status.MATCHED;
                        String reason = eligible ? "ELIGIBLE" : reference.status().name() + ":" + ko.status() + ":" + ko.reconciliation();
                        count.eligibility.merge(reference.type() + ":" + reason, 1L, Long::sum);
                        if (eligible && reference.type() == PropertyReferenceType.PARCEL) eligibleParcel = true;
                        if (!eligible && parsed.parserVersion().equals(PropertyReferenceParser.VERSION)) {
                            unresolved.add(Map.of("auctionId", auction, "sourceSnapshotSha256", canonical.path("sourceSnapshotSha256").asText(),
                                    "type", reference.type().name(), "referenceKeySha256",
                                    rs.sud.eaukcija.enrichment.EnrichmentHashing.sha256(reference.canonicalKey()), "reason", reason));
                        }
                    }
                    if (parcelPresent) count.auctionsWithParcels++;
                    if (eligibleParcel) count.auctionsWithEligibleParcel++;
                }
            }
        }
        Map<String, Object> report = Map.of("schemaVersion", "location-coverage-audit-v2", "population", population,
                "inputFrameSha256", HexFormat.of().formatHex(digest.digest()), "dictionaryVersion", dictionary.version(),
                "measurement", "same-input pipeline eligibility, not independent accuracy or verified geometry coverage",
                "parsers", totals, "currentParserVersion", PropertyReferenceParser.VERSION, "unresolvedCurrent", unresolved);
        Files.createDirectories(output.toAbsolutePath().getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private static String text(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : null; }
    public static final class Totals {
        public final Map<String, Long> references = new TreeMap<>();
        public final Map<String, Long> eligibility = new TreeMap<>();
        public long auctionsWithParcels;
        public long auctionsWithEligibleParcel;
    }
}
