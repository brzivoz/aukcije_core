package rs.sud.eaukcija.sync.persistence;

public record CategoryMembership(
        int categoryId,
        CategoryMembershipType type,
        String categoryName) {

    public CategoryMembership {
        if (categoryId <= 0) {
            throw new IllegalArgumentException("categoryId must be positive");
        }
        SyncPersistenceValidation.required(type, "type");
        if (categoryName != null && categoryName.length() > 1000) {
            throw new IllegalArgumentException("categoryName is too long");
        }
    }
}
