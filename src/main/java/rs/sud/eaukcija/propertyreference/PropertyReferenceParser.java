package rs.sud.eaukcija.propertyreference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;
import rs.sud.eaukcija.enrichment.EnrichmentInputSnapshot;
import rs.sud.eaukcija.spatial.ParcelIdentityNormalizer;

/**
 * Bounded, source-order-stable Serbian property-reference parser.
 *
 * <p>The parser intentionally does not resolve KO identities. It retains both
 * structured and free-text KO evidence so issue #33 can match and reconcile
 * them without turning extraction into a geospatial lookup.</p>
 */
@Component
public final class PropertyReferenceParser {

    public static final String VERSION = "property-reference-v1";
    public static final int MAX_FIELD_CHARACTERS = 32_768;
    public static final int MAX_TOTAL_CHARACTERS = 65_536;
    public static final int MAX_REFERENCES = 256;

    private static final String NUMBER =
            "[0-9]{1,7}(?:\\s*[/\u2044\u2215]\\s*[0-9]{1,5})?";
    private static final Pattern PARCEL_REVERSED = Pattern.compile(
            "(?iuU)(?<!\\p{L})(?:br\\.?|broj|бр\\.?|број)\\s+"
                    + "(?:kat\\p{L}*\\.?\\s*)?(?:parcel\\p{L}*|парц\\p{L}*)\\.?"
                    + "\\s*[:.]?\\s*(?<number>" + NUMBER + ")");
    private static final Pattern PARCEL_LABELED = Pattern.compile(
            "(?iuU)(?:(?:kat\\p{L}*\\.?\\s*)?"
                    + "(?:parcel\\p{L}*|парц\\p{L}*)\\.?|"
                    + "k\\s*\\.?\\s*p\\s*\\.?|к\\s*\\.?\\s*п\\s*\\.?)\\s*"
                    + "(?:(?:br\\.?|broj|бр\\.?|број)\\s*)?[:.]?\\s*"
                    + "(?<number>" + NUMBER + ")");
    private static final Pattern PARCEL_FALSE_CONTEXT = Pattern.compile(
            "(?iuU)(?:broj|бр(?:ој)?|number)\\s+(?:dela|deo|дела|део)\\s*$|"
                    + "(?:povr\\p{L}*|површ\\p{L}*)\\s*$|"
                    + "(?:podbroj|подброј|subparcel)\\s*$");
    private static final Pattern ENUMERATION_NEXT = Pattern.compile(
            "(?iuU)^\\s*(?:,|;|i\\b|и\\b)\\s*(?<number>" + NUMBER + ")");
    private static final Pattern ENUMERATION_STOP = Pattern.compile(
            "(?iuU)^\\s*(?:\\.|\\n|\\r|(?:broj|бр(?:ој)?|number)\\s+(?:dela|deo|дела|део)|"
                    + "povr\\p{L}*|површ\\p{L}*|podbroj|подброј|list|лист|ln\\b|лн\\b|"
                    + "ko\\b|к\\.?\\s*о\\.?\\b)");
    private static final Pattern KO_LABELED = Pattern.compile(
            "(?iuU)(?<!\\p{L})(?:katastarsk\\p{L}*\\s+opstina|"
                    + "катастарск\\p{L}*\\s+општина|k\\s*\\.?\\s*o|"
                    + "к\\s*\\.?\\s*о)(?:\\s+|\\s*[:.\u2013-]\\s*)"
                    + "(?<name>[\\p{L}][\\p{L}0-9 '\u201e\u201c\"-]{0,80}?)"
                    + "(?=\\s*(?:[,;\\n\\r]|\\.(?:\\s|$)|"
                    + "(?:parcel\\p{L}*|парц\\p{L}*|k\\.?\\s*p\\.?|к\\.?\\s*п\\.?|"
                    + "list\\p{L}*|лист\\p{L}*|ln\\b|лн\\b|adresa|адреса|ul\\.|ул\\.)\\s|$))");
    private static final Pattern LAND_REGISTER = Pattern.compile(
            "(?iuU)(?:l\\s*\\.?\\s*n\\.?|л\\s*\\.?\\s*н\\.?|"
                    + "list\\p{L}*\\s+nepokretnosti|лист\\p{L}*\\s+непокретности|"
                    + "(?:broj|број)\\s+list\\p{L}*\\s+nepokretnosti|"
                    + "(?:broj|број)\\s+лист\\p{L}*\\s+непокретности)"
                    + "\\s*(?:(?:br\\.?|broj|бр\\.?|број)\\s*)?[:.]?\\s*"
                    + "(?<number>[0-9]{1,12})");
    private static final Pattern ADDRESS_LABELED = Pattern.compile(
            "(?iuU)(?:adresa|адреса|ulica\\s*/\\s*potes|улица\\s*/\\s*потес|"
                    + "ulica|улица|ul\\.|ул\\.)\\s*[:.]?\\s*"
                    + "(?<address>[\\p{L}0-9 .'-]{2,100}?)"
                    + "(?=\\s*(?:[,;\\n\\r]|(?:opstina|општина|ko|k\\.o\\.|ко|к\\.о\\.|"
                    + "broj\\s+parcele|број\\s+парцеле|katastarsk|катастарск|"
                    + "na\\s+kp|на\\s+кп|na\\s+k\\.p\\.|на\\s+к\\.п\\.)\\s|$))");
    private static final Pattern HOUSE_NUMBER = Pattern.compile(
            "(?iuU)^(.*?)(?:\\s+(?:br\\.?|broj|бр\\.?|број)\\s*)?"
                    + "([0-9]{1,4}[\\p{L}]?)$");
    private static final Pattern CONTROL = Pattern.compile("[\\u0000\\u000B\\u000C]");

    public PropertyReferenceParseResult parse(JsonNode canonicalInput) {
        if (canonicalInput == null || !canonicalInput.isObject()) {
            throw new PropertyReferenceParseException("PARSER_INPUT_INVALID");
        }
        if (!EnrichmentInputSnapshot.SCHEMA_VERSION.equals(text(canonicalInput, "schemaVersion"))) {
            throw new PropertyReferenceParseException("PARSER_INPUT_SCHEMA_UNSUPPORTED");
        }
        long auctionId = canonicalInput.path("auctionId").canConvertToLong()
                ? canonicalInput.path("auctionId").longValue() : -1;
        return parse(new Input(
                auctionId,
                requiredSha(canonicalInput, "sourceSnapshotSha256"),
                text(canonicalInput, "cadastral"),
                text(canonicalInput, "placeName"),
                text(canonicalInput, "municipality"),
                text(canonicalInput, "description"),
                text(canonicalInput, "shortDescription")));
    }

    public PropertyReferenceParseResult parse(Input input) {
        if (input == null || input.auctionId() <= 0) {
            throw new PropertyReferenceParseException("PARSER_INPUT_INVALID");
        }
        requireSha(input.sourceSnapshotSha256());
        validateField(input.cadastral());
        validateField(input.placeName());
        validateField(input.municipality());
        validateField(input.description());
        validateField(input.shortDescription());
        int total = length(input.cadastral()) + length(input.placeName())
                + length(input.municipality()) + length(input.description())
                + length(input.shortDescription());
        if (total > MAX_TOTAL_CHARACTERS) {
            throw new PropertyReferenceParseException("PARSER_INPUT_TOO_LARGE");
        }

        List<TextMatch> matches = new ArrayList<>();
        extract("detail.Description", input.description(), 0, matches);
        extract("detail.ShortDescription", input.shortDescription(), 1, matches);
        matches.sort(Comparator.comparingInt(TextMatch::fieldOrder)
                .thenComparingInt(TextMatch::start)
                .thenComparing(match -> match.type().ordinal())
                .thenComparing(TextMatch::canonicalValue));

        List<TextMatch> koMatches = matches.stream()
                .filter(match -> match.type() == PropertyReferenceType.CADASTRAL_MUNICIPALITY)
                .toList();
        LinkedHashMap<String, TextMatch> distinctKos = new LinkedHashMap<>();
        koMatches.forEach(match -> distinctKos.putIfAbsent(match.canonicalValue(), match));
        TextMatch selectedTextKo = distinctKos.size() == 1
                ? distinctKos.values().iterator().next() : null;
        String structuredNormalizedKo = SerbianNameNormalizer.normalize(input.cadastral());
        boolean multipleTextKos = distinctKos.size() > 1;
        boolean structuredTextConflict = selectedTextKo != null
                && structuredNormalizedKo != null
                && !structuredNormalizedKo.equals(selectedTextKo.canonicalValue());
        boolean koConflict = multipleTextKos || structuredTextConflict;

        List<ParsedPropertyReference> ordered = new ArrayList<>();
        PropertyReferenceExtractionStatus structuredStatus = noStructuredFields(input)
                ? PropertyReferenceExtractionStatus.NO_STRUCTURED_REFERENCE
                : koConflict ? PropertyReferenceExtractionStatus.NEEDS_REVIEW
                : PropertyReferenceExtractionStatus.EXTRACTED;
        ordered.add(new ParsedPropertyReference(
                0,
                PropertyReferenceType.STRUCTURED_LOCATION,
                input.cadastral(),
                structuredNormalizedKo,
                null,
                null,
                null,
                null,
                input.municipality(),
                input.placeName(),
                null,
                null,
                "detail.Place.Cadastral|detail.Place.Name|detail.Place.Municipality",
                null,
                null,
                structuredEvidence(input),
                structuredStatus,
                "structured-place",
                koConflict));

        LinkedHashMap<String, ParsedPropertyReference> deduplicated = new LinkedHashMap<>();
        for (TextMatch match : matches) {
            KoContext context = koContext(match, selectedTextKo, structuredNormalizedKo, input.cadastral(), koConflict);
            String canonicalKey = canonicalKey(match, context.normalizedKo());
            ParsedPropertyReference reference = new ParsedPropertyReference(
                    0,
                    match.type(),
                    context.rawKo(),
                    context.normalizedKo(),
                    null,
                    match.type() == PropertyReferenceType.PARCEL ? match.rawValue() : null,
                    match.type() == PropertyReferenceType.PARCEL ? match.canonicalValue() : null,
                    match.type() == PropertyReferenceType.LAND_REGISTER ? match.canonicalValue() : null,
                    match.type() == PropertyReferenceType.ADDRESS ? input.municipality() : null,
                    match.type() == PropertyReferenceType.ADDRESS ? input.placeName() : null,
                    match.type() == PropertyReferenceType.ADDRESS ? match.addressStreet() : null,
                    match.type() == PropertyReferenceType.ADDRESS ? match.addressHouseNumber() : null,
                    match.sourceField(),
                    match.start(),
                    match.end(),
                    match.rawEvidence(),
                    context.conflict() ? PropertyReferenceExtractionStatus.NEEDS_REVIEW
                            : PropertyReferenceExtractionStatus.EXTRACTED,
                    canonicalKey,
                    context.conflict());
            if (!deduplicated.containsKey(canonicalKey)
                    && deduplicated.size() >= MAX_REFERENCES - 1) {
                throw new PropertyReferenceParseException("PARSER_REFERENCE_LIMIT_EXCEEDED");
            }
            deduplicated.putIfAbsent(canonicalKey, reference);
        }
        int order = 1;
        for (ParsedPropertyReference reference : deduplicated.values()) {
            ordered.add(withOrder(reference, order++));
        }

        String outputHash = hash(ordered);
        return new PropertyReferenceParseResult(
                VERSION,
                ordered,
                outputHash,
                ordered.size() - 1,
                structuredStatus == PropertyReferenceExtractionStatus.NO_STRUCTURED_REFERENCE ? 1 : 0,
                koConflict ? 1 : 0);
    }

    private static void extract(
            String sourceField,
            String text,
            int fieldOrder,
            List<TextMatch> matches) {
        if (text == null || text.isBlank()) {
            return;
        }
        extractParcels(sourceField, text, fieldOrder, PARCEL_REVERSED, matches);
        extractParcels(sourceField, text, fieldOrder, PARCEL_LABELED, matches);
        extractKo(sourceField, text, fieldOrder, matches);
        extractLandRegisters(sourceField, text, fieldOrder, matches);
        extractAddresses(sourceField, text, fieldOrder, matches);
    }

    private static void extractParcels(
            String sourceField,
            String text,
            int fieldOrder,
            Pattern pattern,
            List<TextMatch> matches) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (falseParcelContext(text, matcher.start())) {
                continue;
            }
            addParcel(sourceField, text, fieldOrder, matcher.start(), matcher.end("number"),
                    matcher.group("number"), matches);
            int cursor = matcher.end("number");
            int remaining = Math.min(text.length(), cursor + 160);
            while (cursor < remaining) {
                String tail = text.substring(cursor, remaining);
                if (ENUMERATION_STOP.matcher(tail).find()) {
                    break;
                }
                Matcher next = ENUMERATION_NEXT.matcher(tail);
                if (!next.find()) {
                    break;
                }
                int numberStart = cursor + next.start("number");
                int numberEnd = cursor + next.end("number");
                addParcel(sourceField, text, fieldOrder, numberStart, numberEnd,
                        next.group("number"), matches);
                cursor += next.end();
            }
        }
    }

    private static void addParcel(
            String sourceField,
            String text,
            int fieldOrder,
            int evidenceStart,
            int evidenceEnd,
            String rawNumber,
            List<TextMatch> matches) {
        String canonical;
        try {
            canonical = ParcelIdentityNormalizer.canonicalParcelNumber(rawNumber);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        matches.add(new TextMatch(
                PropertyReferenceType.PARCEL,
                rawNumber,
                canonical,
                sourceField,
                evidenceStart,
                evidenceEnd,
                text.substring(evidenceStart, evidenceEnd),
                fieldOrder,
                null,
                null));
    }

    private static boolean falseParcelContext(String text, int start) {
        int contextStart = Math.max(0, start - 48);
        return PARCEL_FALSE_CONTEXT.matcher(text.substring(contextStart, start).trim()).find();
    }

    private static void extractKo(
            String sourceField,
            String text,
            int fieldOrder,
            List<TextMatch> matches) {
        Matcher matcher = KO_LABELED.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group("name").trim();
            String normalized = SerbianNameNormalizer.normalize(raw);
            if (normalized != null) {
                matches.add(new TextMatch(
                        PropertyReferenceType.CADASTRAL_MUNICIPALITY,
                        raw,
                        normalized,
                        sourceField,
                        matcher.start(),
                        matcher.end(),
                        text.substring(matcher.start(), matcher.end()),
                        fieldOrder,
                        null,
                        null));
            }
        }
    }

    private static void extractLandRegisters(
            String sourceField,
            String text,
            int fieldOrder,
            List<TextMatch> matches) {
        Matcher matcher = LAND_REGISTER.matcher(text);
        while (matcher.find()) {
            String value = matcher.group("number").replaceAll("\\s+", "");
            matches.add(new TextMatch(
                    PropertyReferenceType.LAND_REGISTER,
                    matcher.group("number"),
                    value,
                    sourceField,
                    matcher.start(),
                    matcher.end(),
                    text.substring(matcher.start(), matcher.end()),
                    fieldOrder,
                    null,
                    null));
        }
    }

    private static void extractAddresses(
            String sourceField,
            String text,
            int fieldOrder,
            List<TextMatch> matches) {
        Matcher matcher = ADDRESS_LABELED.matcher(text);
        while (matcher.find()) {
            String address = matcher.group("address").trim();
            Matcher house = HOUSE_NUMBER.matcher(address);
            String street = address;
            String houseNumber = null;
            if (house.matches() && !house.group(1).isBlank()) {
                street = house.group(1).trim();
                houseNumber = house.group(2);
            }
            String normalized = SerbianNameNormalizer.normalize(
                    houseNumber == null ? street : street + " " + houseNumber);
            if (normalized != null) {
                matches.add(new TextMatch(
                        PropertyReferenceType.ADDRESS,
                        address,
                        normalized,
                        sourceField,
                        matcher.start(),
                        matcher.end(),
                        text.substring(matcher.start(), matcher.end()),
                        fieldOrder,
                        street,
                        houseNumber));
            }
        }
    }

    private static KoContext koContext(
            TextMatch match,
            TextMatch selectedTextKo,
            String structuredNormalizedKo,
            String structuredRawKo,
            boolean conflict) {
        if (match.type() == PropertyReferenceType.CADASTRAL_MUNICIPALITY) {
            boolean thisConflict = conflict || (structuredNormalizedKo != null
                    && !structuredNormalizedKo.equals(match.canonicalValue()));
            return new KoContext(match.rawValue(), match.canonicalValue(), thisConflict);
        }
        if (selectedTextKo != null) {
            return new KoContext(selectedTextKo.rawValue(), selectedTextKo.canonicalValue(), conflict);
        }
        if (conflict) {
            return new KoContext(null, null, true);
        }
        return new KoContext(structuredRawKo, structuredNormalizedKo, false);
    }

    private static String canonicalKey(TextMatch match, String normalizedKo) {
        String context = normalizedKo == null ? "UNRESOLVED_KO" : normalizedKo;
        return switch (match.type()) {
            case PARCEL -> "parcel:" + context + ":" + match.canonicalValue();
            case CADASTRAL_MUNICIPALITY -> "ko:" + match.canonicalValue();
            case LAND_REGISTER -> "land-register:" + context + ":" + match.canonicalValue();
            case ADDRESS -> "address:" + context + ":" + match.canonicalValue();
            case STRUCTURED_LOCATION -> throw new IllegalArgumentException("unexpected text type");
        };
    }

    private static ParsedPropertyReference withOrder(ParsedPropertyReference value, int order) {
        return new ParsedPropertyReference(
                order, value.type(), value.rawKo(), value.normalizedKo(), value.koCode(),
                value.rawParcelNumber(), value.canonicalParcelNumber(),
                value.landRegisterNumber(), value.addressMunicipality(), value.addressSettlement(),
                value.addressStreet(), value.addressHouseNumber(), value.sourceField(),
                value.sourceOffsetStart(), value.sourceOffsetEnd(), value.rawEvidence(),
                value.status(), value.canonicalKey(), value.koConflict());
    }

    private static String structuredEvidence(Input input) {
        return "{\"cadastral\":" + jsonString(input.cadastral())
                + ",\"municipality\":" + jsonString(input.municipality())
                + ",\"placeName\":" + jsonString(input.placeName())
                + ",\"sourceSnapshotSha256\":" + jsonString(input.sourceSnapshotSha256()) + "}";
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", codePoint));
                    } else {
                        escaped.appendCodePoint(codePoint);
                    }
                }
            }
        });
        return escaped.append('"').toString();
    }

    private static boolean noStructuredFields(Input input) {
        return blank(input.cadastral()) && blank(input.placeName()) && blank(input.municipality());
    }

    private static String hash(List<ParsedPropertyReference> references) {
        StringBuilder canonical = new StringBuilder(VERSION);
        for (ParsedPropertyReference value : references) {
            append(canonical, Integer.toString(value.referenceOrder()));
            append(canonical, value.type().name());
            append(canonical, value.rawKo());
            append(canonical, value.normalizedKo());
            append(canonical, value.koCode());
            append(canonical, value.rawParcelNumber());
            append(canonical, value.canonicalParcelNumber());
            append(canonical, value.landRegisterNumber());
            append(canonical, value.addressMunicipality());
            append(canonical, value.addressSettlement());
            append(canonical, value.addressStreet());
            append(canonical, value.addressHouseNumber());
            append(canonical, value.sourceField());
            append(canonical, value.sourceOffsetStart() == null ? null
                    : value.sourceOffsetStart().toString());
            append(canonical, value.sourceOffsetEnd() == null ? null
                    : value.sourceOffsetEnd().toString());
            append(canonical, value.rawEvidence());
            append(canonical, value.status().name());
            append(canonical, value.canonicalKey());
            append(canonical, Boolean.toString(value.koConflict()));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void append(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.length()).append(':').append(value);
        }
    }

    private static String requiredSha(JsonNode input, String field) {
        String value = text(input, field);
        requireSha(value);
        return value;
    }

    private static void requireSha(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new PropertyReferenceParseException("PARSER_SOURCE_SNAPSHOT_INVALID");
        }
    }

    private static String text(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new PropertyReferenceParseException("PARSER_INPUT_FIELD_INVALID");
        }
        return value.textValue();
    }

    private static void validateField(String value) {
        if (value != null && (value.length() > MAX_FIELD_CHARACTERS || CONTROL.matcher(value).find())) {
            throw new PropertyReferenceParseException(
                    value.length() > MAX_FIELD_CHARACTERS
                            ? "PARSER_INPUT_TOO_LARGE" : "PARSER_INPUT_CONTROL_CHARACTER");
        }
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record Input(
            long auctionId,
            String sourceSnapshotSha256,
            String cadastral,
            String placeName,
            String municipality,
            String description,
            String shortDescription) {
    }

    private record TextMatch(
            PropertyReferenceType type,
            String rawValue,
            String canonicalValue,
            String sourceField,
            int start,
            int end,
            String rawEvidence,
            int fieldOrder,
            String addressStreet,
            String addressHouseNumber) {
    }

    private record KoContext(String rawKo, String normalizedKo, boolean conflict) {
    }

    public static final class PropertyReferenceParseException extends RuntimeException {
        private final String safeCode;

        public PropertyReferenceParseException(String safeCode) {
            super(safeCode);
            this.safeCode = safeCode;
        }

        public String safeCode() {
            return safeCode;
        }
    }
}
