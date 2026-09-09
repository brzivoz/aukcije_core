package rs.sud.eaukcija.propertyreference;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;

/** Conservative contextual detectors. A candidate is not permission to query RGZ. */
final class UnlabelledParcelPatterns {
    private static final String NUMBER = "[0-9]{1,7}(?:\\s*[/\u2044\u2215]\\s*[0-9]{1,5})?";
    private static final String LAND = "(?:пољопривредн\\p{L}{0,12}\\s+земљ\\p{L}{0,12}|poljoprivredn\\p{L}{0,12}\\s+zemlj\\p{L}{0,12}|"
            + "грађевин\\p{L}{0,12}\\s+земљ\\p{L}{0,12}|gra[đd]evin\\p{L}{0,12}\\s+zemlj\\p{L}{0,12}|"
            + "њив\\p{L}{0,12}|njiv\\p{L}{0,12}|ливада|livada|шума|[šs]uma|пашњак|pa[šs]njak)";
    private static final Pattern LAND_NUMBER_KO = Pattern.compile("(?iuU)(?<!\\p{L})" + LAND
            + "\\s+(?<number>" + NUMBER + ")(?=\\s+(?:к\\.?\\s*о|k\\.?\\s*o)(?:\\s|[.:]))");
    private static final Pattern LEADING_FRACTION_LAND = Pattern.compile("(?iuU)^\\s*(?<number>" + NUMBER
            + ")\\s+" + LAND + "(?=\\s|[,;.]|$)");
    private static final Pattern PARCEL_TITLE = Pattern.compile(
            "(?iuU)^\\s*(?:(?:катастарска|katastarska)\\s+)?(?:парцела|parcela)\\s*[.!]?\\s*$");
    private static final Pattern NUMBER_PLACE_TITLE = Pattern.compile("(?iuU)^\\s*(?<number>" + NUMBER
            + ")\\s+(?<place>[\\p{L}][\\p{L}0-9 .'-]{0,100}?)(?:\\s+(?:и|i)\\s+[0-9]{1,2}"
            + "\\s+(?:објек\\p{L}{0,12}|objek\\p{L}{0,12}))?\\s*[.!]?\\s*$");
    private static final Pattern UNIT_DESCRIPTION = Pattern.compile(
            "(?iuU)^\\s*(?:стан|stan|гараж\\p{L}{0,12}|gara[žz]\\p{L}{0,12}|apartment)\\b");

    private UnlabelledParcelPatterns() { }

    static List<Candidate> detect(String text, int fieldOrder, PropertyReferenceParser.Input input) {
        if (text == null || text.isBlank()) return List.of();
        List<Candidate> candidates = new ArrayList<>();
        String companion = fieldOrder == 0 ? input.shortDescription() : input.description();
        boolean parcelTitle = companion != null && PARCEL_TITLE.matcher(companion).matches();
        var explicitContext = LAND_NUMBER_KO.matcher(text);
        while (explicitContext.find()) {
            String number = explicitContext.group("number");
            // A proper fraction can still mean an ownership share, not a parcel.
            if (unambiguousFraction(number) || (parcelTitle && !hasSlash(number))) {
                candidates.add(new Candidate(explicitContext.start(), explicitContext.end("number"), number, false));
            }
        }
        var leading = LEADING_FRACTION_LAND.matcher(text);
        if (parcelTitle && leading.find() && unambiguousFraction(leading.group("number"))) {
            candidates.add(new Candidate(leading.start("number"), leading.end(), leading.group("number"), false));
        }
        if (fieldOrder == 1 && (input.description() == null || !UNIT_DESCRIPTION.matcher(input.description()).find())) {
            var title = NUMBER_PLACE_TITLE.matcher(text);
            if (title.matches()) {
                String number = title.group("number");
                String place = SerbianNameNormalizer.normalize(title.group("place"));
                if ((!hasSlash(number) || unambiguousFraction(number)) && place != null
                        && (place.equals(SerbianNameNormalizer.normalize(input.cadastral()))
                            || place.equals(SerbianNameNormalizer.normalize(input.placeName())))) {
                    // Even an exact place name cannot prove whether a bare number
                    // denotes a parcel. Preserve it for review; never authorize lookup.
                    candidates.add(new Candidate(title.start("number"), title.end("place"), number, true));
                }
            }
        }
        return candidates;
    }

    private static boolean hasSlash(String number) {
        return number.indexOf('/') >= 0 || number.indexOf('\u2044') >= 0 || number.indexOf('\u2215') >= 0;
    }
    private static boolean unambiguousFraction(String number) {
        String[] pieces = number.replaceAll("(?U)\\s+", "").split("[/\u2044\u2215]");
        if (pieces.length != 2) return false;
        long numerator = Long.parseLong(pieces[0]), denominator = Long.parseLong(pieces[1]);
        return denominator > 0 && numerator > denominator;
    }

    record Candidate(int start, int end, String number, boolean reviewRequired) { }
}
