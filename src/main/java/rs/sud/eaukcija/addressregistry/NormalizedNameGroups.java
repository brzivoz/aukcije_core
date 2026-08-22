package rs.sud.eaukcija.addressregistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** Shared connected-component grouping for identities linked by normalized names. */
final class NormalizedNameGroups {

    private NormalizedNameGroups() {
    }

    static <T> List<List<T>> connectedComponents(
            List<T> values,
            Function<T, ? extends Iterable<String>> groupingKeys) {
        UnionFind union = new UnionFind(values.size());
        Map<String, Integer> firstByKey = new TreeMap<>();
        for (int index = 0; index < values.size(); index++) {
            for (String key : groupingKeys.apply(values.get(index))) {
                Integer first = firstByKey.putIfAbsent(key, index);
                if (first != null) {
                    union.join(first, index);
                }
            }
        }
        Map<Integer, List<T>> groups = new TreeMap<>();
        for (int index = 0; index < values.size(); index++) {
            groups.computeIfAbsent(union.root(index), ignored -> new ArrayList<>()).add(values.get(index));
        }
        return groups.values().stream().map(List::copyOf).toList();
    }

    private static final class UnionFind {

        private final int[] parent;

        private UnionFind(int size) {
            parent = new int[size];
            for (int index = 0; index < size; index++) {
                parent[index] = index;
            }
        }

        private int root(int value) {
            int current = value;
            while (parent[current] != current) {
                parent[current] = parent[parent[current]];
                current = parent[current];
            }
            return current;
        }

        private void join(int left, int right) {
            int leftRoot = root(left);
            int rightRoot = root(right);
            if (leftRoot != rightRoot) {
                parent[Math.max(leftRoot, rightRoot)] = Math.min(leftRoot, rightRoot);
            }
        }
    }
}
