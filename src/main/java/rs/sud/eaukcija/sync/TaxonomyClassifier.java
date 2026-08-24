package rs.sud.eaukcija.sync;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import rs.sud.eaukcija.client.EAukcijaApiTypes.CategoryNode;
import rs.sud.eaukcija.client.EAukcijaApiTypes.CategoryTree;
import rs.sud.eaukcija.sync.persistence.NormalizedPropertyKind;
import rs.sud.eaukcija.sync.persistence.SaleScope;

/** Classification derived only from the taxonomy and observed child endpoint membership. */
public final class TaxonomyClassifier {

    private static final Map<Integer, NormalizedPropertyKind> REVIEWED_KINDS = Map.of(
            47, NormalizedPropertyKind.PARCEL,
            121, NormalizedPropertyKind.PARCEL,
            48, NormalizedPropertyKind.BUILDING,
            124, NormalizedPropertyKind.BUILDING,
            49, NormalizedPropertyKind.UNIT,
            135, NormalizedPropertyKind.UNIT);
    private static final Map<Integer, Set<Integer>> REVIEWED_CHILDREN_BY_ROOT = Map.of(
            7, Set.of(47, 48, 49),
            8, Set.of(121, 124, 135));

    private final Map<Integer, CategoryNode> roots;
    private final Map<Integer, Integer> childRoots;

    public TaxonomyClassifier(CategoryTree tree) {
        Objects.requireNonNull(tree, "tree");
        Map<Integer, CategoryNode> rootsById = new HashMap<>();
        Map<Integer, Integer> rootByChild = new HashMap<>();
        for (CategoryNode root : tree.roots()) {
            rootsById.put(root.value(), root);
            for (CategoryNode child : safeChildren(root)) {
                rootByChild.put(child.value(), root.value());
            }
        }
        this.roots = Map.copyOf(rootsById);
        this.childRoots = Map.copyOf(rootByChild);
    }

    public Classification classify(
            Set<Integer> contributingRoots,
            Set<Integer> contributingChildren) {
        if (contributingRoots == null || contributingRoots.isEmpty()) {
            throw new IllegalArgumentException("an auction must contribute at least one configured root");
        }
        SaleScope scope = null;
        for (int rootId : contributingRoots) {
            CategoryNode root = roots.get(rootId);
            if (root == null) {
                throw new IllegalArgumentException("configured root disappeared from category snapshot");
            }
            SaleScope next = switch (root.categoryType()) {
                case "ImmovableProperties" -> SaleScope.IMMOVABLE;
                case "CommonProperties" -> SaleScope.COMMON;
                default -> throw new IllegalArgumentException("configured root has an unsupported category type");
            };
            if (scope != null && scope != next) {
                throw new IllegalArgumentException("auction appears beneath incompatible sale scopes");
            }
            scope = next;
        }

        Set<NormalizedPropertyKind> observedKinds = new HashSet<>();
        if (contributingChildren != null) {
            for (int childId : contributingChildren) {
                Integer owningRoot = childRoots.get(childId);
                if (owningRoot == null || !contributingRoots.contains(owningRoot)) {
                    throw new IllegalArgumentException(
                            "child membership is outside the auction's contributing roots");
                }
                NormalizedPropertyKind reviewed = REVIEWED_KINDS.get(childId);
                if (reviewed != null) {
                    observedKinds.add(reviewed);
                }
            }
        }
        NormalizedPropertyKind kind = observedKinds.size() == 1
                ? observedKinds.iterator().next()
                : NormalizedPropertyKind.UNKNOWN;
        return new Classification(scope, kind);
    }

    public void validateConfiguredRoots(List<Integer> configuredRoots) {
        if (configuredRoots == null || configuredRoots.isEmpty()) {
            throw new IllegalArgumentException("at least one configured root is required");
        }
        for (int rootId : configuredRoots) {
            CategoryNode root = roots.get(rootId);
            if (root == null) {
                throw new IllegalArgumentException("configured root disappeared from category snapshot");
            }
            String expectedType = switch (rootId) {
                case 7 -> "ImmovableProperties";
                case 8 -> "CommonProperties";
                default -> root.categoryType();
            };
            if (!("ImmovableProperties".equals(root.categoryType())
                    || "CommonProperties".equals(root.categoryType()))
                    || !expectedType.equals(root.categoryType())) {
                throw new IllegalArgumentException("configured root has an unsupported category type");
            }
            Set<Integer> expectedChildren = REVIEWED_CHILDREN_BY_ROOT.get(rootId);
            for (CategoryNode child : safeChildren(root)) {
                if (!root.categoryType().equals(child.categoryType())) {
                    throw new IllegalArgumentException(
                            "direct child changed taxonomy scope");
                }
            }
            if (expectedChildren != null) {
                Map<Integer, CategoryNode> directChildren = new HashMap<>();
                for (CategoryNode child : safeChildren(root)) {
                    directChildren.put(child.value(), child);
                }
                for (int childId : expectedChildren) {
                    CategoryNode child = directChildren.get(childId);
                    if (child == null || !root.categoryType().equals(child.categoryType())) {
                        throw new IllegalArgumentException(
                                "reviewed child disappeared or changed taxonomy scope");
                    }
                }
            }
        }
    }

    private static List<CategoryNode> safeChildren(CategoryNode node) {
        return node.children() == null ? List.of() : node.children();
    }

    public record Classification(SaleScope saleScope, NormalizedPropertyKind propertyKind) {
    }
}
