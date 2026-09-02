package rs.sud.eaukcija.propertyreference.quality;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;
import rs.sud.eaukcija.propertyreference.ParsedPropertyReference;
import rs.sud.eaukcija.propertyreference.PropertyReferenceParser;
import rs.sud.eaukcija.propertyreference.PropertyReferenceQualityProfile;
import rs.sud.eaukcija.propertyreference.PropertyReferenceType;
import rs.sud.eaukcija.spatial.ParcelIdentityNormalizer;

/** Reproducible issue-19 reference-level evaluator over the frozen issue-18 corpus. */
public final class PropertyReferenceQualityCli {

    private static final String SCHEMA_VERSION = "property-reference-parser-metrics-v1";
    private static final String EVALUATION_SURFACE =
            "MINIMIZED_DETAIL_DESCRIPTION_AND_SHORT_DESCRIPTION_EVIDENCE_PHRASES";
    private static final BigDecimal AGRICULTURAL_RECALL = new BigDecimal("0.88");
    private static final BigDecimal GENERIC_PARCEL_RECALL = new BigDecimal("0.75");
    private static final BigDecimal DEFAULT_CATEGORY_RECALL = new BigDecimal("0.80");
    private static final int MIN_CATEGORY_REFERENCES = 5;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final PropertyReferenceParser parser = new PropertyReferenceParser();

    public static void main(String[] args) throws Exception {
        new PropertyReferenceQualityCli().run(Arguments.parse(args));
    }

    void run(Arguments arguments) throws IOException {
        Manifest manifest = objectMapper.readValue(
                arguments.corpusDirectory().resolve("manifest.json").toFile(), Manifest.class);
        List<CorpusAuction> development = read(
                arguments.corpusDirectory().resolve(manifest.split().developmentFile()),
                "DEVELOPMENT", manifest.corpusVersion());
        List<CorpusAuction> heldOut = arguments.developmentOnly()
                ? List.of()
                : read(arguments.corpusDirectory().resolve(manifest.split().heldOutFile()),
                        "HELD_OUT", manifest.corpusVersion());

        if (arguments.developmentOnly()) {
            printDevelopmentErrors(development);
        }

        SplitMetrics developmentMetrics = metrics(development);
        SplitMetrics heldOutMetrics = heldOut.isEmpty() ? null : metrics(heldOut);
        List<CorpusAuction> all = new ArrayList<>(development);
        all.addAll(heldOut);
        SplitMetrics overall = metrics(all);
        Thresholds thresholds = new Thresholds(
                PropertyReferenceQualityProfile.MIN_PRECISION,
                PropertyReferenceQualityProfile.MIN_RECALL,
                0,
                MIN_CATEGORY_REFERENCES,
                AGRICULTURAL_RECALL,
                GENERIC_PARCEL_RECALL,
                DEFAULT_CATEGORY_RECALL);
        Metrics report = new Metrics(
                SCHEMA_VERSION,
                PropertyReferenceParser.VERSION,
                manifest.corpusVersion(),
                EVALUATION_SURFACE,
                thresholds,
                developmentMetrics,
                heldOutMetrics,
                overall);

        byte[] bytes = (objectMapper.writeValueAsString(report) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(arguments.report().toAbsolutePath().normalize().getParent());
        Files.write(arguments.report(), bytes);

        if (heldOutMetrics != null) {
            require(heldOutMetrics.precision().compareTo(thresholds.heldOutPrecision()) >= 0,
                    "held-out precision is below " + thresholds.heldOutPrecision());
            require(heldOutMetrics.recall().compareTo(thresholds.heldOutRecall()) >= 0,
                    "held-out recall is below " + thresholds.heldOutRecall());
            require(heldOutMetrics.falsePositivesOnNegativeAuctions()
                            == thresholds.heldOutNegativeFalsePositives(),
                    "held-out annotated negatives contain a false positive");
            enforceCategoryThresholds(heldOutMetrics, thresholds);
        }
        if (arguments.expected() != null) {
            require(Files.isRegularFile(arguments.expected()),
                    "committed parser metrics are missing");
            require(java.util.Arrays.equals(bytes, Files.readAllBytes(arguments.expected())),
                    "committed parser metrics have drifted");
            verifyQualityProfile(arguments.expected(), heldOutMetrics, manifest.corpusVersion());
        }
        System.out.printf(Locale.ROOT,
                "Property-reference parser %s: development precision=%s recall=%s%s%n",
                PropertyReferenceParser.VERSION,
                developmentMetrics.precision(),
                developmentMetrics.recall(),
                heldOutMetrics == null ? " (held-out sealed)"
                        : "; held-out precision=" + heldOutMetrics.precision()
                        + " recall=" + heldOutMetrics.recall()
                        + " negative-fp=" + heldOutMetrics.falsePositivesOnNegativeAuctions());
    }

    private void printDevelopmentErrors(List<CorpusAuction> auctions) {
        for (CorpusAuction auction : auctions) {
            Set<Key> expected = new LinkedHashSet<>();
            auction.expectedReferences().forEach(reference ->
                    expected.add(key(auction.auctionId(), reference)));
            Set<Key> predicted = new LinkedHashSet<>();
            predictions(auction).forEach(value -> predicted.add(value.key()));
            predicted.stream().filter(value -> !expected.contains(value))
                    .forEach(value -> System.out.println("DEV_FP " + value));
            expected.stream().filter(value -> !predicted.contains(value))
                    .forEach(value -> System.out.println("DEV_FN " + value));
        }
    }

    private void verifyQualityProfile(
            Path expected,
            SplitMetrics heldOut,
            String corpusVersion) throws IOException {
        require(heldOut != null, "held-out metrics are required for profile verification");
        PropertyReferenceQualityProfile.Profile profile;
        try (InputStream input = PropertyReferenceQualityCli.class.getClassLoader()
                .getResourceAsStream(PropertyReferenceQualityProfile.RESOURCE)) {
            require(input != null, "quality profile resource is missing");
            profile = objectMapper.readValue(
                    input, PropertyReferenceQualityProfile.Profile.class);
        }
        require(PropertyReferenceParser.VERSION.equals(profile.parserVersion()),
                "quality profile parser version has drifted");
        require(corpusVersion.equals(profile.corpusVersion()),
                "quality profile corpus version has drifted");
        require(sha256(expected).equals(profile.metricsSha256()),
                "quality profile metrics hash has drifted");
        require(heldOut.precision().compareTo(profile.heldOutPrecision()) == 0,
                "quality profile precision has drifted");
        require(heldOut.recall().compareTo(profile.heldOutRecall()) == 0,
                "quality profile recall has drifted");
        require(heldOut.falsePositivesOnNegativeAuctions()
                        == profile.heldOutNegativeFalsePositives(),
                "quality profile negative count has drifted");
    }

    private static void enforceCategoryThresholds(SplitMetrics heldOut, Thresholds thresholds) {
        for (Map.Entry<String, CategoryMetrics> entry : heldOut.byCategory().entrySet()) {
            CategoryMetrics category = entry.getValue();
            if (category.expectedReferences() < thresholds.minimumCategoryReferences()) {
                continue;
            }
            BigDecimal floor = categoryFloor(entry.getKey(), thresholds);
            require(category.recall().compareTo(floor) >= 0,
                    "held-out category recall is below " + floor + " for " + entry.getKey());
        }
    }

    private static BigDecimal categoryFloor(String category, Thresholds thresholds) {
        if ("Пољопривредно земљиште".equals(category)) {
            return thresholds.agriculturalRecall();
        }
        if ("Парцела".equals(category)) {
            return thresholds.genericParcelRecall();
        }
        return thresholds.defaultCategoryRecall();
    }

    private List<CorpusAuction> read(Path file, String split, String corpusVersion)
            throws IOException {
        CorpusFile corpus = objectMapper.readValue(file.toFile(), CorpusFile.class);
        require(corpusVersion.equals(corpus.corpusVersion()), "corpus version mismatch");
        require(split.equals(corpus.split()), "corpus split mismatch");
        return List.copyOf(corpus.auctions());
    }

    private SplitMetrics metrics(List<CorpusAuction> auctions) {
        Map<Key, ExpectedReference> expected = new LinkedHashMap<>();
        Map<Key, Prediction> predicted = new LinkedHashMap<>();
        Set<Long> negatives = new HashSet<>();
        Map<Long, String> categories = new LinkedHashMap<>();
        for (CorpusAuction auction : auctions) {
            categories.put(auction.auctionId(), auction.category());
            if (auction.expectedReferences().isEmpty()) {
                negatives.add(auction.auctionId());
            }
            for (ExpectedReference reference : auction.expectedReferences()) {
                expected.put(key(auction.auctionId(), reference), reference);
            }
            for (Prediction prediction : predictions(auction)) {
                predicted.putIfAbsent(prediction.key(), prediction);
            }
        }

        int truePositives = 0;
        int negativeFalsePositives = 0;
        TreeMap<String, MutableBreakdown> byType = new TreeMap<>();
        TreeMap<String, MutableBreakdown> byCategory = new TreeMap<>();
        for (Map.Entry<Key, ExpectedReference> entry : expected.entrySet()) {
            boolean hit = predicted.containsKey(entry.getKey());
            if (hit) {
                truePositives++;
            }
            byType.computeIfAbsent(entry.getKey().type(), ignored -> new MutableBreakdown())
                    .expected(hit);
            byCategory.computeIfAbsent(categories.get(entry.getKey().auctionId()),
                            ignored -> new MutableBreakdown())
                    .expected(hit);
        }
        for (Prediction value : predicted.values()) {
            boolean hit = expected.containsKey(value.key());
            byType.computeIfAbsent(value.key().type(), ignored -> new MutableBreakdown())
                    .predicted(hit);
            byCategory.computeIfAbsent(categories.get(value.key().auctionId()),
                            ignored -> new MutableBreakdown())
                    .predicted(hit);
            if (!hit && negatives.contains(value.key().auctionId())) {
                negativeFalsePositives++;
            }
        }
        LinkedHashMap<String, TypeMetrics> typeMetrics = new LinkedHashMap<>();
        byType.forEach((type, value) -> typeMetrics.put(type, value.typeMetrics()));
        LinkedHashMap<String, CategoryMetrics> categoryMetrics = new LinkedHashMap<>();
        byCategory.forEach((category, value) -> categoryMetrics.put(
                category, value.categoryMetrics(categoryFloor(category, new Thresholds(
                        PropertyReferenceQualityProfile.MIN_PRECISION,
                        PropertyReferenceQualityProfile.MIN_RECALL,
                        0,
                        MIN_CATEGORY_REFERENCES,
                        AGRICULTURAL_RECALL,
                        GENERIC_PARCEL_RECALL,
                        DEFAULT_CATEGORY_RECALL)))));
        int falsePositives = predicted.size() - truePositives;
        int falseNegatives = expected.size() - truePositives;
        return new SplitMetrics(
                auctions.size(),
                expected.size(),
                negatives.size(),
                predicted.size(),
                truePositives,
                falsePositives,
                falseNegatives,
                ratio(truePositives, truePositives + falsePositives),
                ratio(truePositives, truePositives + falseNegatives),
                negativeFalsePositives,
                typeMetrics,
                categoryMetrics);
    }

    private List<Prediction> predictions(CorpusAuction auction) {
        String description = evidence(auction, "detail.Description");
        String shortDescription = evidence(auction, "detail.ShortDescription");
        String structuredKo = auction.expectedReferences().stream()
                .map(ExpectedReference::koName)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("TEST KO");
        var parsed = parser.parse(new PropertyReferenceParser.Input(
                auction.auctionId(),
                auction.snapshotSha256(),
                structuredKo,
                null,
                null,
                description,
                shortDescription));
        LinkedHashMap<Key, Prediction> predictions = new LinkedHashMap<>();
        for (ParsedPropertyReference reference : parsed.references()) {
            if (reference.type() == PropertyReferenceType.STRUCTURED_LOCATION) {
                continue;
            }
            Key key = switch (reference.type()) {
                case PARCEL -> new Key(
                        auction.auctionId(), "PARCEL", reference.canonicalParcelNumber());
                case CADASTRAL_MUNICIPALITY -> new Key(
                        auction.auctionId(), "CADASTRAL_MUNICIPALITY", reference.normalizedKo());
                case LAND_REGISTER -> new Key(
                        auction.auctionId(), "LAND_REGISTER", reference.landRegisterNumber());
                case ADDRESS -> new Key(
                        auction.auctionId(), "ADDRESS", normalizeAddress(
                                reference.addressStreet(), reference.addressHouseNumber()));
                case STRUCTURED_LOCATION -> throw new IllegalStateException("filtered above");
            };
            predictions.putIfAbsent(key, new Prediction(key));
        }
        return List.copyOf(predictions.values());
    }

    private static String evidence(CorpusAuction auction, String sourceField) {
        return auction.evidence().stream()
                .filter(value -> sourceField.equals(value.sourceField()))
                .map(Evidence::text)
                .reduce((left, right) -> left + "\n" + right)
                .orElse(null);
    }

    private static Key key(long auctionId, ExpectedReference reference) {
        String canonical = switch (reference.type()) {
            case "PARCEL" -> ParcelIdentityNormalizer.canonicalParcelNumber(
                    reference.parcelNumber());
            case "CADASTRAL_MUNICIPALITY" -> SerbianNameNormalizer.normalize(reference.koName());
            case "LAND_REGISTER" -> reference.landRegisterNumber().replaceAll("\\s+", "");
            case "ADDRESS" -> SerbianNameNormalizer.normalize(
                    String.join(" ", reference.addressTokens()));
            default -> throw new IllegalArgumentException("unsupported corpus type " + reference.type());
        };
        return new Key(auctionId, reference.type(), canonical);
    }

    private static String normalizeAddress(String street, String houseNumber) {
        return SerbianNameNormalizer.normalize(
                houseNumber == null ? street : street + " " + houseNumber);
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(5);
        }
        return BigDecimal.valueOf((double) numerator / denominator)
                .setScale(5, java.math.RoundingMode.HALF_UP);
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new QualityGateException(message);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Manifest(String corpusVersion, Split split) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Split(String developmentFile, String heldOutFile) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CorpusFile(String corpusVersion, String split, List<CorpusAuction> auctions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CorpusAuction(
            long auctionId,
            String snapshotSha256,
            String category,
            List<Evidence> evidence,
            List<ExpectedReference> expectedReferences) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Evidence(String sourceField, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ExpectedReference(
            String type,
            String koName,
            String parcelNumber,
            String landRegisterNumber,
            List<String> addressTokens) {
    }

    public record Metrics(
            String schemaVersion,
            String parserVersion,
            String corpusVersion,
            String evaluationSurface,
            Thresholds thresholds,
            SplitMetrics development,
            SplitMetrics heldOut,
            SplitMetrics overall) {
    }

    public record Thresholds(
            BigDecimal heldOutPrecision,
            BigDecimal heldOutRecall,
            int heldOutNegativeFalsePositives,
            int minimumCategoryReferences,
            BigDecimal agriculturalRecall,
            BigDecimal genericParcelRecall,
            BigDecimal defaultCategoryRecall) {
    }

    public record SplitMetrics(
            int auctions,
            int expectedReferences,
            int negativeAuctions,
            int predictedReferences,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            BigDecimal precision,
            BigDecimal recall,
            int falsePositivesOnNegativeAuctions,
            Map<String, TypeMetrics> byType,
            Map<String, CategoryMetrics> byCategory) {
    }

    public record TypeMetrics(
            int expectedReferences,
            int predictedReferences,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            BigDecimal precision,
            BigDecimal recall) {
    }

    public record CategoryMetrics(
            int expectedReferences,
            int predictedReferences,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            BigDecimal precision,
            BigDecimal recall,
            BigDecimal recallFloor) {
    }

    private record Key(long auctionId, String type, String canonicalValue) {
    }

    private record Prediction(Key key) {
    }

    private static final class MutableBreakdown {
        int expected;
        int predicted;
        int truePositives;

        void expected(boolean hit) {
            expected++;
            if (hit) {
                truePositives++;
            }
        }

        void predicted(boolean hit) {
            predicted++;
        }

        TypeMetrics typeMetrics() {
            return new TypeMetrics(
                    expected,
                    predicted,
                    truePositives,
                    predicted - truePositives,
                    expected - truePositives,
                    ratio(truePositives, predicted),
                    ratio(truePositives, expected));
        }

        CategoryMetrics categoryMetrics(BigDecimal floor) {
            return new CategoryMetrics(
                    expected,
                    predicted,
                    truePositives,
                    predicted - truePositives,
                    expected - truePositives,
                    ratio(truePositives, predicted),
                    ratio(truePositives, expected),
                    floor);
        }
    }

    private record Arguments(
            Path corpusDirectory,
            Path report,
            Path expected,
            boolean developmentOnly) {

        static Arguments parse(String[] args) {
            Path corpus = null;
            Path report = null;
            Path expected = null;
            boolean developmentOnly = false;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--corpus-dir" -> corpus = Path.of(value(args, ++index));
                    case "--report" -> report = Path.of(value(args, ++index));
                    case "--expected" -> expected = Path.of(value(args, ++index));
                    case "--development-only" -> developmentOnly = true;
                    default -> throw new QualityGateException("unknown argument " + args[index]);
                }
            }
            require(corpus != null, "--corpus-dir is required");
            require(report != null, "--report is required");
            return new Arguments(corpus.toAbsolutePath().normalize(),
                    report.toAbsolutePath().normalize(),
                    expected == null ? null : expected.toAbsolutePath().normalize(),
                    developmentOnly);
        }

        private static String value(String[] args, int index) {
            if (index >= args.length) {
                throw new QualityGateException("argument value is missing");
            }
            return args[index];
        }
    }

    public static final class QualityGateException extends RuntimeException {
        QualityGateException(String message) {
            super(message);
        }
    }
}
