package rs.sud.eaukcija.propertyreference.corpus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;
import rs.sud.eaukcija.snapshot.AuctionSourceCanonicalJson;
import rs.sud.eaukcija.snapshot.AuctionSourceSnapshot;
import rs.sud.eaukcija.snapshot.AuctionSourceSnapshotFactory;
import rs.sud.eaukcija.spatial.ParcelIdentityNormalizer;
import rs.sud.eaukcija.sync.persistence.SaleScope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline quality gate for the reviewed issue-18 corpus.
 *
 * <p>The default command never opens a network connection or reads a source
 * snapshot payload. It validates only the deliberately minimized fixtures,
 * evaluates the frozen pre-parser baseline, and compares the result with the
 * committed metrics. The explicit private-source mode additionally checks the
 * fixtures against an ignored local capture.</p>
 */
public final class PropertyReferenceCorpusCli {

    public static final String SCHEMA_VERSION = "property-reference-corpus-v1";
    public static final String SCHEMA_ID =
            "urn:aukcije-core:schema:property-reference-corpus-v1";
    public static final String BASELINE_VERSION = "issue-32-regex-baseline-v1";

    private static final Set<String> SPLITS = Set.of("DEVELOPMENT", "HELD_OUT");
    private static final Set<String> TYPES = Set.of(
            "PARCEL", "CADASTRAL_MUNICIPALITY", "LAND_REGISTER", "ADDRESS");
    private static final Set<String> SOURCE_FIELDS = Set.of("detail.Description");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PERSONAL_DATA = Pattern.compile(
            "(?iuU)(?:\\bJMBG\\b|\\bЈМБГ\\b|\\bEMBG\\b|\\bЕМБГ\\b|"
                    + "\\b[0-9]{13}\\b|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,})");
    private static final Pattern PARCEL_REVERSED = Pattern.compile(
            "(?iuU)(?:бр\\.?|број)\\s*парцел\\w*\\s*[:.]?\\s*"
                    + "(\\d{1,6}(?:\\s*[/⁄∕]\\s*\\d{1,4})?)");
    private static final Pattern PARCEL_LABELED = Pattern.compile(
            "(?iuU)(?<!дела\\s)(?<!део\\s)(?<!делу\\s)(?<!подброј\\s)"
                    + "(?:кат(?:астарск\\w*)?\\.?\\s*)?"
                    + "(?:парц(?:ел\\w*)?|к\\.?\\s*п\\.?|kp)\\s*"
                    + "(?:бр\\.?|број)?\\s*[:.]?\\s*"
                    + "(\\d{1,6}(?:\\s*[/⁄∕]\\s*\\d{1,4})?)");
    private static final Pattern KO_LABELED = Pattern.compile(
            "(?iuU)(?:катастарска\\s+општина|к\\.?\\s*о\\.?|k\\.?\\s*o\\.?)"
                    + "\\s*[:.-]?\\s*([\\p{L}][\\p{L}0-9 '\u201e\u201c\"-]{0,60})");
    private static final Pattern LAND_REGISTER = Pattern.compile(
            "(?iuU)(?:л\\.?\\s*н\\.?|лист\\w*\\s+непокретности|"
                    + "број\\s+листа\\s+непокретности)\\s*(?:бр\\.?|број)?\\s*[:.]?\\s*"
                    + "(\\d{1,12})");
    private static final Pattern ADDRESS_LABELED = Pattern.compile(
            "(?iuU)(?:адреса|улица\\s*/\\s*потес|улица|ул\\.)\\s*[:.]?\\s*"
                    + "([\\p{L}0-9 .'-]{2,80}?)(?=,|;|\\s+(?:општина|ко|к\\.о\\.|"
                    + "број\\s+парцеле|катастарск|на\\s+кп|на\\s+к\\.п\\.)|$)");
    private static final Pattern HOUSE_NUMBER = Pattern.compile(
            "(?iuU)^(.*?)(?:\\s+(?:бр\\.?|број)\\s*)?(\\d{1,4}[\\p{L}]?)$");
    private static final Pattern CYRILLIC_TEXT = Pattern.compile("\\p{IsCyrillic}");
    private static final Pattern LATIN_TOKEN = Pattern.compile("[A-Za-zČĆŽŠĐčćžšđ]{2,}");
    private static final Pattern ROMAN_NUMERAL = Pattern.compile("(?i)[IVXLCDM]+");
    private static final Pattern TEMPLATE_NUMBER = Pattern.compile("\\d+");
    private static final String EVALUATION_SURFACE =
            "MINIMIZED_DETAIL_DESCRIPTION_EVIDENCE_PHRASES";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        new PropertyReferenceCorpusCli().run(Arguments.parse(args));
    }

    void run(Arguments arguments) throws IOException {
        Path directory = arguments.corpusDirectory().toAbsolutePath().normalize();
        Manifest manifest = read(directory.resolve("manifest.json"), Manifest.class);
        validateManifest(directory, manifest);
        Schema corpusSchema = loadAndValidateSchema(directory.resolve("corpus.schema.json"));
        validateSchema(corpusSchema, directory.resolve(manifest.split().developmentFile()));
        validateSchema(corpusSchema, directory.resolve(manifest.split().heldOutFile()));

        List<CorpusAuction> development = readAuctions(
                directory.resolve(manifest.split().developmentFile()),
                manifest.corpusVersion(), "DEVELOPMENT");
        List<CorpusAuction> heldOut = readAuctions(
                directory.resolve(manifest.split().heldOutFile()),
                manifest.corpusVersion(), "HELD_OUT");
        List<CorpusAuction> all = new ArrayList<>(development);
        all.addAll(heldOut);
        validateCorpus(directory, manifest, development, heldOut, all);
        if (arguments.sourceCapture() != null) {
            validateAgainstSource(arguments.sourceCapture(), manifest, all);
        }

        BaselineMetrics report = evaluate(manifest, development, heldOut);
        Files.createDirectories(arguments.report().toAbsolutePath().normalize().getParent());
        objectMapper.writeValue(arguments.report().toFile(), report);

        if (arguments.verifyCommitted()) {
            JsonNode expected = objectMapper.readTree(
                    directory.resolve(manifest.baseline().metricsFile()).toFile());
            JsonNode actual = objectMapper.valueToTree(report);
            if (!expected.equals(actual)) {
                throw new CorpusValidationException(
                        "baseline metrics changed; inspect " + arguments.report()
                                + " and review the corpus or baseline before updating evidence");
            }
        }

        Counts counts = Counts.from(all);
        System.out.printf(Locale.ROOT,
                "Property-reference corpus %s validated: %d auctions, %d references, "
                        + "%d negatives; held-out=%d auctions. Baseline precision=%.4f, "
                        + "recall=%.4f, false positives=%d.%n",
                manifest.corpusVersion(), counts.auctions(), counts.references(),
                counts.negatives(), heldOut.size(), report.overall().precision(),
                report.overall().recall(), report.overall().falsePositives());
    }

    private void validateManifest(Path directory, Manifest manifest) throws IOException {
        require(SCHEMA_VERSION.equals(manifest.schemaVersion()),
                "manifest schemaVersion must be " + SCHEMA_VERSION);
        require(nonblank(manifest.corpusVersion()), "manifest corpusVersion is required");
        require(manifest.source() != null, "manifest source is required");
        Instant.parse(manifest.source().capturedAt());
        require(manifest.source().population() >= 60, "source population is implausibly small");
        requireSha(manifest.source().captureSha256(), "source captureSha256");
        require("eaukcija-listing-detail-v1".equals(manifest.source().snapshotSchemaVersion()),
                "unexpected source snapshot schema version");
        require("public-auction-fields-v1".equals(
                        manifest.source().minimizationPolicyVersion()),
                "unexpected source minimization policy version");
        require(nonblank(manifest.source().selectionQueryFile()),
                "selection query file is required");
        require(nonblank(manifest.source().selectionMethod())
                        && nonblank(manifest.source().samplingFrame())
                        && nonblank(manifest.source().limitations()),
                "source selection method, sampling frame, and limitations are required");
        require(Files.isRegularFile(directory.resolve(manifest.source().selectionQueryFile())),
                "selection query file is missing");
        require(nonblank(manifest.annotationScope())
                        && manifest.annotationScope().contains("detail.Description")
                        && manifest.annotationScope().contains("detail.Place.Cadastral"),
                "annotationScope must distinguish description evidence from structured KO context");
        require(manifest.review() != null
                        && manifest.review().annotationPass() != null
                        && manifest.review().adjudicationPass() != null,
                "two review passes are required");
        require(nonblank(manifest.review().annotationPass().reviewer())
                        && nonblank(manifest.review().adjudicationPass().reviewer()),
                "reviewers are required");
        require(!manifest.review().annotationPass().reviewId().equals(
                        manifest.review().adjudicationPass().reviewId()),
                "annotation and adjudication review IDs must differ");
        require(nonblank(manifest.review().limitations())
                        && manifest.review().limitations().length() >= 80,
                "review limitations must disclose reviewer independence limits");
        LocalDate.parse(manifest.review().annotationPass().reviewedAt());
        LocalDate.parse(manifest.review().adjudicationPass().reviewedAt());
        require(Files.isRegularFile(directory.resolve(manifest.review().adjudicationsFile())),
                "adjudications file is missing");
        require(manifest.split() != null
                        && nonblank(manifest.split().developmentFile())
                        && nonblank(manifest.split().heldOutFile())
                        && nonblank(manifest.split().heldOutPolicy()),
                "split contract is incomplete");
        LocalDate.parse(manifest.split().heldOutFrozenAt());
        require(manifest.supportedPatterns() != null && !manifest.supportedPatterns().isEmpty(),
                "supportedPatterns must not be empty");
        require(manifest.baseline() != null
                        && BASELINE_VERSION.equals(manifest.baseline().version())
                        && nonblank(manifest.baseline().metricsFile()),
                "baseline contract is incomplete");
        require(Files.isRegularFile(directory.resolve(manifest.baseline().metricsFile())),
                "committed baseline metrics are missing");
        require(manifest.koAuthority() != null
                        && nonblank(manifest.koAuthority().file())
                        && nonblank(manifest.koAuthority().dictionaryVersion()),
                "KO authority contract is incomplete");
        requireSha(manifest.koAuthority().sourceGpkgSha256(), "KO source GPKG SHA-256");
        requireSha(manifest.koAuthority().sourceDictionarySha256(),
                "KO source dictionary SHA-256");
        require(manifest.artifacts() != null && manifest.artifacts().size() == 7,
                "the exact seven corpus artifacts must be hash-addressed");
        Set<String> artifactPaths = new HashSet<>();
        for (Artifact artifact : manifest.artifacts()) {
            require(nonblank(artifact.path()) && artifactPaths.add(artifact.path()),
                    "artifact paths must be unique and nonblank");
            requireSha(artifact.sha256(), "artifact SHA-256 for " + artifact.path());
            Path path = directory.resolve(artifact.path()).normalize();
            require(path.getParent().equals(directory),
                    "artifact path must remain directly under the corpus directory");
            require(Files.isRegularFile(path), "artifact is missing: " + artifact.path());
            require(artifact.sha256().equals(sha256(path)),
                    "artifact hash changed: " + artifact.path());
        }
        require(artifactPaths.equals(Set.of(
                        manifest.split().developmentFile(), manifest.split().heldOutFile(),
                        manifest.review().adjudicationsFile(),
                        manifest.source().selectionQueryFile(), "corpus.schema.json",
                        manifest.baseline().metricsFile(), manifest.koAuthority().file())),
                "artifact inventory is incomplete or contains an unexpected file");
        require(nonblank(manifest.licensingAndProvenanceNote())
                        && manifest.licensingAndProvenanceNote().length() >= 120,
                "licensing/provenance note is incomplete");

        JsonNode schema = objectMapper.readTree(directory.resolve("corpus.schema.json").toFile());
        require("https://json-schema.org/draft/2020-12/schema".equals(
                        schema.path("$schema").asText()),
                "corpus.schema.json must use JSON Schema draft 2020-12");
        require(SCHEMA_ID.equals(schema.path("$id").asText()),
                "corpus.schema.json has an unexpected $id");
    }

    private Schema loadAndValidateSchema(Path schemaPath) throws IOException {
        String schemaJson = Files.readString(schemaPath, StandardCharsets.UTF_8);
        SchemaRegistry registry = SchemaRegistry.withDialect(Dialects.getDraft202012());
        Schema metaSchema = registry.getSchema(
                SchemaLocation.of(Dialects.getDraft202012().getId()));
        List<com.networknt.schema.Error> errors = metaSchema.validate(
                schemaJson, InputFormat.JSON);
        require(errors.isEmpty(), "corpus.schema.json is not valid Draft 2020-12: "
                + summarizeSchemaErrors(errors));
        return registry.getSchema(schemaJson, InputFormat.JSON);
    }

    private void validateSchema(Schema schema, Path fixturePath) throws IOException {
        List<com.networknt.schema.Error> errors = schema.validate(
                Files.readString(fixturePath, StandardCharsets.UTF_8), InputFormat.JSON);
        require(errors.isEmpty(), fixturePath.getFileName() + " violates corpus.schema.json: "
                + summarizeSchemaErrors(errors));
    }

    private static String summarizeSchemaErrors(List<com.networknt.schema.Error> errors) {
        return errors.stream().limit(5).map(com.networknt.schema.Error::getMessage)
                .toList().toString();
    }

    private List<CorpusAuction> readAuctions(
            Path path,
            String expectedCorpusVersion,
            String expectedSplit) throws IOException {
        CorpusFile file = read(path, CorpusFile.class);
        require(SCHEMA_VERSION.equals(file.schemaVersion()),
                path.getFileName() + " has an unexpected schema version");
        require(expectedCorpusVersion.equals(file.corpusVersion()),
                path.getFileName() + " has an unexpected corpus version");
        require(expectedSplit.equals(file.split()),
                path.getFileName() + " has an unexpected split");
        require(file.auctions() != null, path.getFileName() + " auctions are required");
        require(file.auctions().stream().allMatch(
                        auction -> auction != null && expectedSplit.equals(auction.split())),
                path.getFileName() + " contains an auction from another split");
        return file.auctions();
    }

    private void validateCorpus(
            Path directory,
            Manifest manifest,
            List<CorpusAuction> development,
            List<CorpusAuction> heldOut,
            List<CorpusAuction> all) throws IOException {
        require(development.size() >= 40, "development split must contain at least 40 auctions");
        require(heldOut.size() >= 15, "held-out split must contain at least 15 auctions");

        Set<Long> auctionIds = new HashSet<>();
        Set<String> annotationIds = new HashSet<>();
        Set<String> coveredPatterns = new HashSet<>();
        Set<String> tags = new HashSet<>();
        KoAuthorityFile koAuthority = read(
                directory.resolve(manifest.koAuthority().file()), KoAuthorityFile.class);
        Map<String, KoAuthorityEntry> koByCode = validateKoAuthority(manifest, koAuthority);
        for (CorpusAuction auction : all) {
            require(auctionIds.add(auction.auctionId()),
                    "auction appears in more than one split: " + auction.auctionId());
            validateAuction(manifest, auction, annotationIds, coveredPatterns, tags, koByCode);
        }
        Counts counts = Counts.from(all);
        require(counts.auctions() >= 60, "corpus must contain at least 60 auctions");
        require(counts.references() >= 100, "corpus must contain at least 100 references");
        require(counts.negatives() >= 20, "corpus must contain at least 20 negatives");
        require(Counts.from(heldOut).negatives() >= 5,
                "held-out split must contain at least five negatives");
        long latinAuctions = all.stream()
                .filter(auction -> auction.patternTags().contains("LATIN"))
                .count();
        require(latinAuctions >= 5,
                "corpus must contain at least five auctions with a non-Roman Latin token "
                        + "of at least two letters");
        long distinctNegativeTemplates = all.stream()
                .filter(auction -> auction.expectedReferences().isEmpty())
                .map(PropertyReferenceCorpusCli::negativeTemplateSignature)
                .distinct()
                .count();
        require(distinctNegativeTemplates >= 15,
                "negative descriptions must contain at least 15 distinct text templates");
        require(coveredPatterns.containsAll(manifest.supportedPatterns()),
                "missing supported patterns: " + difference(
                        manifest.supportedPatterns(), coveredPatterns));
        for (String requiredTag : List.of(
                "CYRILLIC", "LATIN", "MULTIPLE_REFERENCES", "PARCEL_SUFFIX",
                "LAND_REGISTER", "ADDRESS", "MALFORMED_PROSE", "MISSING_FIELDS",
                "FALSE_POSITIVE_TRAP", "NEGATIVE")) {
            require(tags.contains(requiredTag), "missing selection stratum: " + requiredTag);
        }

        AdjudicationFile adjudicationFile = read(
                directory.resolve(manifest.review().adjudicationsFile()),
                AdjudicationFile.class);
        require(SCHEMA_VERSION.equals(adjudicationFile.schemaVersion()),
                "adjudications have an unexpected schema version");
        require(manifest.corpusVersion().equals(adjudicationFile.corpusVersion()),
                "adjudications have an unexpected corpus version");
        require(adjudicationFile.adjudications() != null,
                "adjudications are required");
        List<Adjudication> adjudications = adjudicationFile.adjudications();
        Map<String, Long> adjudicationIds = new HashMap<>();
        for (Adjudication adjudication : adjudications) {
            require(adjudication != null
                            && nonblank(adjudication.id())
                            && adjudicationIds.putIfAbsent(
                                    adjudication.id(), adjudication.auctionId()) == null,
                    "adjudication IDs must be unique and nonblank");
            require(auctionIds.contains(adjudication.auctionId()),
                    "adjudication references an unknown auction: " + adjudication.auctionId());
            require(nonblank(adjudication.disagreement()) && nonblank(adjudication.resolution()),
                    "adjudication disagreement and resolution are required");
        }
        Set<String> linkedAdjudicationIds = new HashSet<>();
        for (CorpusAuction auction : all) {
            for (String adjudicationId : safe(auction.adjudicationIds())) {
                require(adjudicationIds.containsKey(adjudicationId),
                        "unknown adjudication ID " + adjudicationId);
                require(adjudicationIds.get(adjudicationId) == auction.auctionId(),
                        "adjudication is linked to the wrong auction: " + adjudicationId);
                require(linkedAdjudicationIds.add(adjudicationId),
                        "adjudication is linked more than once: " + adjudicationId);
            }
        }
        require(linkedAdjudicationIds.equals(adjudicationIds.keySet()),
                "every recorded disagreement must be linked to its auction");
    }

    private void validateAgainstSource(
            Path sourceCapture,
            Manifest manifest,
            List<CorpusAuction> auctions) throws IOException {
        String sourceJson = Files.readString(sourceCapture, StandardCharsets.UTF_8);
        require(sha256(sourceCapture).equals(manifest.source().captureSha256()),
                "private source capture hash does not match the manifest");
        JsonNode rows = AuctionSourceCanonicalJson.readTree(sourceJson);
        require(rows.isArray(), "private source capture must be an array");
        Map<Long, JsonNode> byId = new HashMap<>();
        rows.forEach(row -> {
            JsonNode id = row.path("Id");
            require(id.isIntegralNumber() && id.canConvertToLong(),
                    "private source capture contains an invalid ID");
            require(nonblank(row.path("_detalji").path("Place")
                            .path("Cadastral").asText(null)),
                    "annotation-scope claim changed: sampling-frame record lacks structured KO");
            require(byId.putIfAbsent(id.longValue(), row) == null,
                    "private source capture contains a duplicate ID");
        });
        require(byId.size() == manifest.source().population(),
                "private source population does not match the manifest");

        AuctionSourceSnapshotFactory factory = new AuctionSourceSnapshotFactory(objectMapper);
        Instant fetchedAt = Instant.parse(manifest.source().capturedAt());
        for (CorpusAuction auction : auctions) {
            JsonNode source = byId.get(auction.auctionId());
            require(source != null, "private source capture is missing " + auction.auctionId());
            JsonNode detail = source.path("_detalji");
            AuctionSourceSnapshot snapshot = factory.create(
                    auction.auctionId(), source, detail, SaleScope.IMMOVABLE,
                    fetchedAt, fetchedAt);
            require(snapshot.contentSha256().equals(auction.snapshotSha256()),
                    "snapshot hash mismatch for " + auction.auctionId());
            require(sha256Text(detail.path("Description").asText(""))
                            .equals(auction.sourceFieldHashes().descriptionSha256()),
                    "description hash mismatch for " + auction.auctionId());
            require(sha256Text(detail.path("ShortDescription").asText(""))
                            .equals(auction.sourceFieldHashes().shortDescriptionSha256()),
                    "short-description hash mismatch for " + auction.auctionId());
            for (Evidence evidence : auction.evidence()) {
                String sourceText = detail.path("Description").asText("");
                require(sourceText.contains(evidence.text()),
                        "minimized evidence is not exact source text for "
                                + auction.auctionId());
            }
        }
        System.out.printf(Locale.ROOT,
                "Private source verification passed for %d corpus auctions.%n",
                auctions.size());
    }

    private Map<String, KoAuthorityEntry> validateKoAuthority(
            Manifest manifest,
            KoAuthorityFile authority) {
        require("property-reference-ko-authority-v1".equals(authority.schemaVersion()),
                "KO authority has an unexpected schema version");
        require(manifest.koAuthority().dictionaryVersion().equals(authority.dictionaryVersion()),
                "KO authority dictionary version does not match the manifest");
        require(manifest.koAuthority().sourceGpkgSha256().equals(authority.sourceGpkgSha256()),
                "KO authority source GPKG hash does not match the manifest");
        require(manifest.koAuthority().sourceDictionarySha256().equals(
                        authority.sourceDictionarySha256()),
                "KO authority source dictionary hash does not match the manifest");
        require(authority.entries() != null && !authority.entries().isEmpty(),
                "KO authority entries are required");
        Map<String, KoAuthorityEntry> byCode = new HashMap<>();
        for (KoAuthorityEntry entry : authority.entries()) {
            require(entry != null && entry.koCode() != null
                            && entry.koCode().matches("[0-9]{6}")
                            && byCode.putIfAbsent(entry.koCode(), entry) == null,
                    "KO authority codes must be unique six-digit values");
            require(nonblank(entry.officialNameCyrillic())
                            && nonblank(entry.officialNameLatin()),
                    "KO authority names are required for " + entry.koCode());
        }
        return byCode;
    }

    private void validateAuction(
            Manifest manifest,
            CorpusAuction auction,
            Set<String> annotationIds,
            Set<String> coveredPatterns,
            Set<String> tags,
            Map<String, KoAuthorityEntry> koByCode) {
        require(auction.auctionId() > 0, "auctionId must be positive");
        requireSha(auction.snapshotSha256(), "snapshotSha256 for " + auction.auctionId());
        require(SPLITS.contains(auction.split()), "invalid split for " + auction.auctionId());
        require(nonblank(auction.category()), "category is required for " + auction.auctionId());
        require(Set.of("REFERENCES_PRESENT", "NO_DESCRIPTION_REFERENCE")
                        .contains(auction.caseStatus()),
                "invalid caseStatus for " + auction.auctionId());
        require("ADJUDICATED".equals(auction.reviewStatus()),
                "auction is not adjudicated: " + auction.auctionId());
        require(auction.sourceFieldHashes() != null,
                "sourceFieldHashes are required for " + auction.auctionId());
        requireSha(auction.sourceFieldHashes().descriptionSha256(),
                "description hash for " + auction.auctionId());
        requireSha(auction.sourceFieldHashes().shortDescriptionSha256(),
                "short-description hash for " + auction.auctionId());
        require(auction.evidence() != null && !auction.evidence().isEmpty(),
                "minimized evidence is required for " + auction.auctionId());
        require(auction.expectedReferences() != null,
                "expectedReferences is required for " + auction.auctionId());
        require(auction.patternTags() != null && !auction.patternTags().isEmpty(),
                "patternTags are required for " + auction.auctionId());
        require(auction.patternTags().stream().allMatch(
                        PropertyReferenceCorpusCli::nonblank)
                        && new HashSet<>(auction.patternTags()).size()
                        == auction.patternTags().size(),
                "patternTags must be unique and nonblank for " + auction.auctionId());
        require(auction.adjudicationIds() != null,
                "adjudicationIds are required for " + auction.auctionId());
        tags.addAll(auction.patternTags());

        String allEvidence = auction.evidence().stream()
                .map(Evidence::text)
                .reduce("", (left, right) -> left + " " + right);
        boolean hasCyrillic = CYRILLIC_TEXT.matcher(allEvidence).find();
        boolean hasLatin = hasMeaningfulLatinToken(allEvidence);
        require(auction.patternTags().contains("CYRILLIC") == hasCyrillic,
                "CYRILLIC tag does not match exact evidence for " + auction.auctionId());
        require(auction.patternTags().contains("LATIN") == hasLatin,
                "LATIN tag does not match exact evidence for " + auction.auctionId());

        int evidenceCharacters = 0;
        for (int index = 0; index < auction.evidence().size(); index++) {
            Evidence evidence = auction.evidence().get(index);
            require(evidence != null && SOURCE_FIELDS.contains(evidence.sourceField()),
                    "invalid source field for " + auction.auctionId());
            require(nonblank(evidence.text()),
                    "blank evidence for " + auction.auctionId());
            require(evidence.text().length() <= 240,
                    "evidence exceeds 240 characters for " + auction.auctionId());
            require(!PERSONAL_DATA.matcher(evidence.text()).find(),
                    "personal data is forbidden in evidence for " + auction.auctionId());
            evidenceCharacters += evidence.text().length();
        }
        require(evidenceCharacters <= 900,
                "auction evidence exceeds the minimization budget: " + auction.auctionId());

        boolean negative = "NO_DESCRIPTION_REFERENCE".equals(auction.caseStatus());
        require(negative == auction.expectedReferences().isEmpty(),
                "caseStatus/reference mismatch for " + auction.auctionId());
        for (ExpectedReference reference : auction.expectedReferences()) {
            require(reference != null
                            && nonblank(reference.annotationId())
                            && annotationIds.add(reference.annotationId()),
                    "annotation IDs must be unique and nonblank");
            require(TYPES.contains(reference.type()),
                    "invalid reference type " + reference.type());
            require(manifest.supportedPatterns().contains(reference.pattern()),
                    "unsupported pattern " + reference.pattern());
            coveredPatterns.add(reference.pattern());
            require(reference.evidenceIndex() >= 0
                            && reference.evidenceIndex() < auction.evidence().size(),
                    "invalid evidenceIndex for " + reference.annotationId());
            Evidence evidence = auction.evidence().get(reference.evidenceIndex());
            require(evidence.text().equals(reference.rawEvidence()),
                    "rawEvidence must equal the minimized exact evidence for "
                            + reference.annotationId());
            require(nonblank(reference.koName()),
                    "KO name is required for " + reference.annotationId());
            require(reference.koCode() != null && reference.koCode().matches("[0-9]{6}"),
                    "KO code is required for " + reference.annotationId());
            KoAuthorityEntry authority = koByCode.get(reference.koCode());
            require(authority != null,
                    "KO code is absent from the authority extract for "
                            + reference.annotationId());
            String normalizedKo = SerbianNameNormalizer.normalize(reference.koName());
            require(normalizedKo.equals(SerbianNameNormalizer.normalize(
                                    authority.officialNameCyrillic()))
                            || normalizedKo.equals(SerbianNameNormalizer.normalize(
                                    authority.officialNameLatin())),
                    "KO name/code mismatch against authority for "
                            + reference.annotationId());
            validateTypedReference(reference);
        }
    }

    private static String negativeTemplateSignature(CorpusAuction auction) {
        String joined = auction.evidence().stream().map(Evidence::text)
                .reduce("", (left, right) -> left + " " + right);
        return TEMPLATE_NUMBER.matcher(joined.toLowerCase(Locale.ROOT))
                .replaceAll("#").replaceAll("\\s+", " ").trim();
    }

    private static boolean hasMeaningfulLatinToken(String text) {
        Matcher matcher = LATIN_TOKEN.matcher(text);
        while (matcher.find()) {
            if (!ROMAN_NUMERAL.matcher(matcher.group()).matches()) {
                return true;
            }
        }
        return false;
    }

    private static void validateTypedReference(ExpectedReference reference) {
        switch (reference.type()) {
            case "PARCEL" -> {
                require(nonblank(reference.parcelNumber()),
                        "parcelNumber is required for " + reference.annotationId());
                require(reference.landRegisterNumber() == null
                                && reference.addressTokens() == null,
                        "parcel annotation contains another typed value for "
                                + reference.annotationId());
                ParcelIdentityNormalizer.canonicalParcelNumber(reference.parcelNumber());
            }
            case "CADASTRAL_MUNICIPALITY" -> {
                require(SerbianNameNormalizer.normalize(reference.koName()) != null,
                        "KO name cannot be normalized for " + reference.annotationId());
                require(reference.parcelNumber() == null
                                && reference.landRegisterNumber() == null
                                && reference.addressTokens() == null,
                        "KO annotation contains another typed value for "
                                + reference.annotationId());
            }
            case "LAND_REGISTER" -> {
                require(nonblank(reference.landRegisterNumber()),
                        "landRegisterNumber is required for " + reference.annotationId());
                require(reference.parcelNumber() == null
                                && reference.addressTokens() == null,
                        "land-register annotation contains another typed value for "
                                + reference.annotationId());
            }
            case "ADDRESS" -> {
                require(reference.addressTokens() != null
                                && !reference.addressTokens().isEmpty()
                                && reference.addressTokens().stream().allMatch(
                                        PropertyReferenceCorpusCli::nonblank),
                        "addressTokens are required for " + reference.annotationId());
                require(reference.parcelNumber() == null
                                && reference.landRegisterNumber() == null,
                        "address annotation contains another typed value for "
                                + reference.annotationId());
            }
            default -> throw new CorpusValidationException("unreachable reference type");
        }
    }

    private BaselineMetrics evaluate(
            Manifest manifest,
            List<CorpusAuction> development,
            List<CorpusAuction> heldOut) {
        SplitMetrics developmentMetrics = metrics(development);
        SplitMetrics heldOutMetrics = metrics(heldOut);
        List<CorpusAuction> all = new ArrayList<>(development);
        all.addAll(heldOut);
        SplitMetrics overallMetrics = metrics(all);
        return new BaselineMetrics(
                SCHEMA_VERSION,
                manifest.corpusVersion(),
                BASELINE_VERSION,
                EVALUATION_SURFACE,
                developmentMetrics,
                heldOutMetrics,
                overallMetrics);
    }

    private SplitMetrics metrics(List<CorpusAuction> auctions) {
        Map<Key, ExpectedReference> expected = new LinkedHashMap<>();
        Map<Key, Prediction> predicted = new LinkedHashMap<>();
        Set<Long> negativeAuctionIds = new HashSet<>();
        for (CorpusAuction auction : auctions) {
            if (auction.expectedReferences().isEmpty()) {
                negativeAuctionIds.add(auction.auctionId());
            }
            for (ExpectedReference reference : auction.expectedReferences()) {
                Key key = key(auction.auctionId(), reference);
                require(expected.putIfAbsent(key, reference) == null,
                        "duplicate expected canonical reference " + key);
            }
            for (Prediction prediction : baselinePredictions(auction)) {
                predicted.putIfAbsent(prediction.key(), prediction);
            }
        }

        int truePositives = 0;
        int negativeFalsePositives = 0;
        Map<String, ExpectedPatternAccumulator> expectedPatterns = new TreeMap<>();
        Map<String, DetectorAccumulator> detectors = new TreeMap<>();
        for (Map.Entry<Key, ExpectedReference> entry : expected.entrySet()) {
            ExpectedReference reference = entry.getValue();
            ExpectedPatternAccumulator accumulator = expectedPatterns.computeIfAbsent(
                    reference.pattern(), ignored -> new ExpectedPatternAccumulator());
            accumulator.expected++;
            Prediction matchingPrediction = predicted.get(entry.getKey());
            if (matchingPrediction != null) {
                truePositives++;
                accumulator.truePositives++;
            } else {
                accumulator.falseNegatives++;
            }
        }
        for (Prediction prediction : predicted.values()) {
            DetectorAccumulator detector = detectors.computeIfAbsent(
                    prediction.pattern(), ignored -> new DetectorAccumulator());
            detector.predicted++;
            if (expected.containsKey(prediction.key())) {
                detector.truePositives++;
            } else {
                detector.falsePositives++;
                if (negativeAuctionIds.contains(prediction.key().auctionId())) {
                    negativeFalsePositives++;
                }
            }
        }

        int falsePositives = predicted.size() - truePositives;
        int falseNegatives = expected.size() - truePositives;
        Map<String, ExpectedPatternMetrics> byExpectedPattern = new LinkedHashMap<>();
        expectedPatterns.forEach((pattern, accumulator) -> byExpectedPattern.put(
                pattern, accumulator.toMetrics()));
        Map<String, DetectorMetrics> byDetector = new LinkedHashMap<>();
        detectors.forEach((detector, accumulator) -> byDetector.put(
                detector, accumulator.toMetrics()));
        return new SplitMetrics(
                auctions.size(),
                expected.size(),
                negativeAuctionIds.size(),
                predicted.size(),
                truePositives,
                falsePositives,
                falseNegatives,
                ratio(truePositives, truePositives + falsePositives),
                ratio(truePositives, truePositives + falseNegatives),
                negativeFalsePositives,
                byExpectedPattern,
                byDetector);
    }

    private List<Prediction> baselinePredictions(CorpusAuction auction) {
        Map<Key, Prediction> predictions = new LinkedHashMap<>();
        for (Evidence evidence : auction.evidence()) {
            extractParcels(auction, evidence, PARCEL_REVERSED, "PARCEL_NUMBER_REVERSED", predictions);
            extractParcels(auction, evidence, PARCEL_LABELED, "PARCEL_LABELED", predictions);
            extractKo(auction, evidence, predictions);
            extractLandRegister(auction, evidence, predictions);
            extractAddress(auction, evidence, predictions);
        }
        return List.copyOf(predictions.values());
    }

    private void extractParcels(
            CorpusAuction auction,
            Evidence evidence,
            Pattern pattern,
            String patternName,
            Map<Key, Prediction> predictions) {
        Matcher matcher = pattern.matcher(evidence.text());
        while (matcher.find()) {
            String parcel = matcher.group(1);
            Key key = new Key(
                    auction.auctionId(), "PARCEL",
                    ParcelIdentityNormalizer.canonicalParcelNumber(parcel));
            predictions.putIfAbsent(key, new Prediction(key, patternName));
        }
    }

    private void extractKo(
            CorpusAuction auction,
            Evidence evidence,
            Map<Key, Prediction> predictions) {
        Matcher matcher = KO_LABELED.matcher(evidence.text());
        while (matcher.find()) {
            String candidate = matcher.group(1)
                    .replaceFirst("(?iuU)\\s+(?:општина|површина|лист|лн|и\\s+то)$", "")
                    .trim();
            String normalized = SerbianNameNormalizer.normalize(candidate);
            if (normalized != null) {
                Key key = new Key(auction.auctionId(), "CADASTRAL_MUNICIPALITY", normalized);
                predictions.putIfAbsent(key, new Prediction(key, "KO_LABELED"));
            }
        }
    }

    private void extractLandRegister(
            CorpusAuction auction,
            Evidence evidence,
            Map<Key, Prediction> predictions) {
        Matcher matcher = LAND_REGISTER.matcher(evidence.text());
        while (matcher.find()) {
            Key key = new Key(auction.auctionId(), "LAND_REGISTER", matcher.group(1));
            predictions.putIfAbsent(key, new Prediction(key, "LAND_REGISTER_LABELED"));
        }
    }

    private void extractAddress(
            CorpusAuction auction,
            Evidence evidence,
            Map<Key, Prediction> predictions) {
        Matcher matcher = ADDRESS_LABELED.matcher(evidence.text());
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            Matcher house = HOUSE_NUMBER.matcher(candidate);
            String normalized;
            if (house.matches()) {
                normalized = normalizeAddress(List.of(house.group(1).trim(), house.group(2)));
            } else {
                normalized = normalizeAddress(List.of(candidate));
            }
            if (normalized != null) {
                Key key = new Key(auction.auctionId(), "ADDRESS", normalized);
                predictions.putIfAbsent(key, new Prediction(key, "ADDRESS_LABELED"));
            }
        }
    }

    private static Key key(long auctionId, ExpectedReference reference) {
        String value = switch (reference.type()) {
            case "PARCEL" -> ParcelIdentityNormalizer.canonicalParcelNumber(
                    reference.parcelNumber());
            case "CADASTRAL_MUNICIPALITY" -> SerbianNameNormalizer.normalize(
                    reference.koName());
            case "LAND_REGISTER" -> reference.landRegisterNumber()
                    .replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            case "ADDRESS" -> normalizeAddress(reference.addressTokens());
            default -> throw new CorpusValidationException("unsupported reference type");
        };
        return new Key(auctionId, reference.type(), value);
    }

    private static String normalizeAddress(List<String> tokens) {
        return SerbianNameNormalizer.normalize(String.join(" ", tokens));
    }

    private <T> T read(Path path, Class<T> type) throws IOException {
        require(Files.isRegularFile(path), "missing corpus file: " + path);
        return objectMapper.readValue(path.toFile(), type);
    }

    private static Double ratio(int numerator, int denominator) {
        if (denominator == 0) {
            return null;
        }
        return Math.round((double) numerator / denominator * 10_000.0d) / 10_000.0d;
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String sha256Text(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static Set<String> difference(Set<String> required, Set<String> actual) {
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(actual);
        return missing;
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireSha(String value, String field) {
        require(value != null && SHA_256.matcher(value).matches(),
                field + " must be a lowercase SHA-256");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CorpusValidationException(message);
        }
    }

    private record Arguments(
            Path corpusDirectory,
            Path report,
            boolean verifyCommitted,
            Path sourceCapture) {
        static Arguments parse(String[] args) {
            Path corpusDirectory = Path.of("corpus/property-references/v1");
            Path report = Path.of("build/reports/property-reference-corpus/baseline-metrics.json");
            boolean verifyCommitted = false;
            Path sourceCapture = null;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--corpus-dir" -> corpusDirectory = Path.of(requireValue(args, ++index));
                    case "--report" -> report = Path.of(requireValue(args, ++index));
                    case "--verify-committed" -> verifyCommitted = true;
                    case "--source-capture" -> sourceCapture = Path.of(
                            requireValue(args, ++index));
                    default -> throw new IllegalArgumentException(
                            "unknown corpus argument: " + args[index]);
                }
            }
            return new Arguments(corpusDirectory, report, verifyCommitted, sourceCapture);
        }

        private static String requireValue(String[] args, int index) {
            if (index >= args.length) {
                throw new IllegalArgumentException("missing corpus argument value");
            }
            return args[index];
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Manifest(
            String schemaVersion,
            String corpusVersion,
            String annotationScope,
            Source source,
            Review review,
            Split split,
            Set<String> supportedPatterns,
            Baseline baseline,
            KoAuthority koAuthority,
            List<Artifact> artifacts,
            String licensingAndProvenanceNote) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Source(
            String name,
            String capturedAt,
            int population,
            String captureSha256,
            String snapshotSchemaVersion,
            String minimizationPolicyVersion,
            String selectionQueryFile,
            String selectionMethod,
            String samplingFrame,
            String limitations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Review(
            ReviewPass annotationPass,
            ReviewPass adjudicationPass,
            String adjudicationsFile,
            String limitations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReviewPass(String reviewId, String reviewer, String reviewedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Split(
            String developmentFile,
            String heldOutFile,
            String heldOutFrozenAt,
            String heldOutPolicy) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Baseline(String version, String metricsFile) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record KoAuthority(
            String file,
            String dictionaryVersion,
            String sourceGpkgSha256,
            String sourceDictionarySha256) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Artifact(String path, String sha256) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CorpusFile(
            String schemaVersion,
            String corpusVersion,
            String split,
            List<CorpusAuction> auctions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CorpusAuction(
            long auctionId,
            String snapshotSha256,
            String split,
            String category,
            String caseStatus,
            List<String> patternTags,
            SourceFieldHashes sourceFieldHashes,
            List<Evidence> evidence,
            List<ExpectedReference> expectedReferences,
            String reviewStatus,
            List<String> adjudicationIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceFieldHashes(
            String descriptionSha256,
            String shortDescriptionSha256) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Evidence(String sourceField, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ExpectedReference(
            String annotationId,
            String type,
            String pattern,
            int evidenceIndex,
            String rawEvidence,
            String koName,
            String koCode,
            String parcelNumber,
            String landRegisterNumber,
            List<String> addressTokens) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AdjudicationFile(
            String schemaVersion,
            String corpusVersion,
            List<Adjudication> adjudications) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Adjudication(
            String id,
            long auctionId,
            String disagreement,
            String resolution) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record KoAuthorityFile(
            String schemaVersion,
            String dictionaryVersion,
            String sourceGpkgSha256,
            String sourceDictionarySha256,
            List<KoAuthorityEntry> entries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record KoAuthorityEntry(
            String koCode,
            String officialNameCyrillic,
            String officialNameLatin) {
    }

    public record BaselineMetrics(
            String schemaVersion,
            String corpusVersion,
            String baselineVersion,
            String evaluationSurface,
            SplitMetrics development,
            SplitMetrics heldOut,
            SplitMetrics overall) {
    }

    public record SplitMetrics(
            int auctions,
            int expectedReferences,
            int negativeAuctions,
            int predictedReferences,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            double precision,
            double recall,
            int falsePositivesOnNegativeAuctions,
            Map<String, ExpectedPatternMetrics> byExpectedPattern,
            Map<String, DetectorMetrics> byDetector) {
    }

    public record ExpectedPatternMetrics(
            int expected,
            int truePositives,
            int falseNegatives,
            Double recall) {
    }

    public record DetectorMetrics(
            int predicted,
            int truePositives,
            int falsePositives,
            Double precision) {
    }

    private record Key(long auctionId, String type, String canonicalValue) {
    }

    private record Prediction(Key key, String pattern) {
    }

    private static final class ExpectedPatternAccumulator {
        int expected;
        int truePositives;
        int falseNegatives;

        ExpectedPatternMetrics toMetrics() {
            return new ExpectedPatternMetrics(
                    expected,
                    truePositives,
                    falseNegatives,
                    ratio(truePositives, truePositives + falseNegatives));
        }
    }

    private static final class DetectorAccumulator {
        int predicted;
        int truePositives;
        int falsePositives;

        DetectorMetrics toMetrics() {
            return new DetectorMetrics(
                    predicted,
                    truePositives,
                    falsePositives,
                    ratio(truePositives, truePositives + falsePositives));
        }
    }

    private record Counts(int auctions, int references, int negatives) {
        static Counts from(List<CorpusAuction> auctions) {
            int references = auctions.stream()
                    .mapToInt(auction -> auction.expectedReferences().size())
                    .sum();
            int negatives = (int) auctions.stream()
                    .filter(auction -> auction.expectedReferences().isEmpty())
                    .count();
            return new Counts(auctions.size(), references, negatives);
        }
    }

    public static final class CorpusValidationException extends RuntimeException {
        CorpusValidationException(String message) {
            super(message);
        }
    }
}
