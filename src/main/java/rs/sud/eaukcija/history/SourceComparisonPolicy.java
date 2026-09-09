package rs.sud.eaukcija.history;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import rs.sud.eaukcija.snapshot.AuctionSourceSnapshotFactory;

/** Display-safe codes only. Exact hashes and legally meaningful text are never normalized. */
public final class SourceComparisonPolicy {
    public static final String VERSION = "source-review-v1";
    private static final Set<String> MONEY = Set.of("StartingPrice", "EstimatedPrice", "BidStep",
            "CurrentPrice", "MaxOfferedPrice");
    private static final Set<String> LIVE = Set.of("CURRENT_PRICE", "MAX_OFFERED_PRICE");
    private static final List<String> FIELDS = List.of("StartingPrice", "EstimatedPrice", "StartDate",
            "EndDate", "Status", "PropertyType", "Category", "Place", "Description", "ShortDescription",
            "AuctionNumber", "IsFirstSale", "BidStep", "PublicationDate", "ExecutorName",
            "CurrentPrice", "MaxOfferedPrice");
    private static final List<String> CODES = List.of("STARTING_PRICE", "ESTIMATED_PRICE", "START_DATE",
            "END_DATE", "SOURCE_STATUS", "PROPERTY_TYPE", "CATEGORY", "STRUCTURED_PLACE", "DESCRIPTION",
            "SHORT_DESCRIPTION", "AUCTION_NUMBER", "FIRST_SALE", "BID_STEP", "SOURCE_PUBLICATION_DATE",
            "EXECUTOR", "CURRENT_PRICE", "MAX_OFFERED_PRICE");

    private SourceComparisonPolicy() { }

    public record Difference(String policy, String kind, List<String> fields) { }

    public static boolean supported(String schema, String minimization, String policy) {
        return AuctionSourceSnapshotFactory.SCHEMA_VERSION.equals(schema)
                && AuctionSourceSnapshotFactory.MINIMIZATION_POLICY_VERSION.equals(minimization)
                && VERSION.equals(policy);
    }

    public static Difference compare(JsonNode before, JsonNode after, boolean compatible, boolean exactEqual) {
        if (!compatible) return new Difference(VERSION, "UNSUPPORTED", List.of());
        if (before == null || after == null) return new Difference(VERSION, "BASELINE", List.of());
        if (exactEqual) return new Difference(VERSION, "UNCHANGED", List.of());
        List<String> changed = new ArrayList<>();
        for (int i = 0; i < FIELDS.size(); i++) {
            String field = FIELDS.get(i);
            for (String section : List.of("listing", "detail")) {
                if (!equal(before.path(section).path(field), after.path(section).path(field), MONEY.contains(field))) {
                    changed.add(CODES.get(i));
                    break;
                }
            }
        }
        String kind = changed.isEmpty() ? "REPRESENTATION_ONLY"
                : LIVE.containsAll(changed) ? "LIVE_BIDDING_ONLY" : "SUBSTANTIVE";
        return new Difference(VERSION, kind, List.copyOf(changed));
    }

    private static boolean equal(JsonNode a, JsonNode b, boolean monetary) {
        if (monetary && (a.isNumber() || a.isTextual()) && (b.isNumber() || b.isTextual())) {
            try {
                return new BigDecimal(a.asText()).compareTo(new BigDecimal(b.asText())) == 0;
            } catch (NumberFormatException ignored) {
                // Invalid or future representations are compared exactly, not guessed.
            }
        }
        return a.equals(b);
    }
}
