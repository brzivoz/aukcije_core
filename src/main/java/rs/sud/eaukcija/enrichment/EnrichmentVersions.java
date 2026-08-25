package rs.sud.eaukcija.enrichment;

import java.util.Objects;

public record EnrichmentVersions(
        String parserVersion,
        String resolverVersion,
        String datasetVersion) {

    public EnrichmentVersions {
        parserVersion = require(parserVersion, "parserVersion");
        resolverVersion = require(resolverVersion, "resolverVersion");
        datasetVersion = require(datasetVersion, "datasetVersion");
    }

    public String workKey(long auctionId, String snapshotSha256, String dependencySha256) {
        if (auctionId <= 0) {
            throw new IllegalArgumentException("auctionId must be positive");
        }
        return EnrichmentHashing.sha256(
                Long.toString(auctionId),
                requireSha256(snapshotSha256, "snapshotSha256"),
                parserVersion,
                resolverVersion,
                datasetVersion,
                requireSha256(dependencySha256, "dependencySha256"));
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 512) {
            throw new IllegalArgumentException(name + " must contain at most 512 characters");
        }
        return trimmed;
    }

    static String requireSha256(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return value;
    }
}
