package rs.sud.eaukcija.sync;

import rs.sud.eaukcija.client.AuctionSummaryFingerprint;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionSummary;

/** Stable semantic fingerprint used to decide when source details are stale. */
public final class ListingFingerprint {

    private ListingFingerprint() {
    }

    public static String sha256(AuctionSummary summary) {
        return AuctionSummaryFingerprint.sha256(summary);
    }
}
