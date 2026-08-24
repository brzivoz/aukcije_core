package rs.sud.eaukcija.sync.persistence;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

public record TaxonomySnapshot(
        String treeSha256,
        String normalizerVersion,
        JsonNode canonicalTree,
        Instant observedAt) {

    public TaxonomySnapshot {
        SyncPersistenceValidation.sha256(treeSha256, "treeSha256");
        SyncPersistenceValidation.nonBlank(normalizerVersion, "normalizerVersion");
        SyncPersistenceValidation.required(canonicalTree, "canonicalTree");
        if (!canonicalTree.isArray()) {
            throw new IllegalArgumentException("canonicalTree must be a JSON array");
        }
        canonicalTree = canonicalTree.deepCopy();
        SyncPersistenceValidation.required(observedAt, "observedAt");
    }
}
