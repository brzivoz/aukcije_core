package rs.sud.eaukcija.sync.persistence;

import java.util.List;

public record SyncRunClaimRequest(
        String idempotencyKey,
        List<Integer> configuredRoots,
        int pageSize,
        SyncTriggerKind triggerKind) {

    public SyncRunClaimRequest {
        SyncPersistenceValidation.nonBlank(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.length() > 512) {
            throw new IllegalArgumentException("idempotencyKey is too long");
        }
        SyncPersistenceValidation.required(configuredRoots, "configuredRoots");
        configuredRoots = configuredRoots.stream()
                .map(root -> SyncPersistenceValidation.required(root, "configuredRoot"))
                .peek(root -> {
                    if (root <= 0) {
                        throw new IllegalArgumentException("configured roots must be positive");
                    }
                })
                .distinct()
                .sorted()
                .toList();
        if (configuredRoots.isEmpty() || configuredRoots.size() > 16) {
            throw new IllegalArgumentException("configuredRoots must contain between 1 and 16 roots");
        }
        if (pageSize < 1 || pageSize > 3_000) {
            throw new IllegalArgumentException("pageSize must be between 1 and 3000");
        }
        SyncPersistenceValidation.required(triggerKind, "triggerKind");
    }
}
