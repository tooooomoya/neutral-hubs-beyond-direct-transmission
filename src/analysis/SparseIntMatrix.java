package analysis;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Sparse integer adjacency counts for the repost/relay interaction graph.
 *
 * A dense {@code int[N][N]} would cost O(N^2) per step in the interaction metrics regardless of how
 * few edges exist, and its footprint becomes prohibitive at larger N. Iteration here is over
 * realized (source -> target) entries only; a dense snapshot is materialized on demand via
 * {@link #toDense(int)}, solely at the periodic GEXF export cadence.
 */
public class SparseIntMatrix {

    private final Map<Integer, Map<Integer, Integer>> rows = new HashMap<>();

    /** counts[i][j] += 1. */
    public void increment(int i, int j) {
        rows.computeIfAbsent(i, k -> new HashMap<>()).merge(j, 1, Integer::sum);
    }

    public int get(int i, int j) {
        Map<Integer, Integer> r = rows.get(i);
        return (r == null) ? 0 : r.getOrDefault(j, 0);
    }

    /** Drop all entries (used for the resettable per-window repost matrix). */
    public void clear() {
        rows.clear();
    }

    /** Realized rows: each entry is source i -> {target j : count}. Iterate for O(edges) work. */
    public Set<Map.Entry<Integer, Map<Integer, Integer>>> rowEntries() {
        return rows.entrySet();
    }

    /** Dense snapshot, materialized for GEXF export only. */
    public int[][] toDense(int n) {
        int[][] dense = new int[n][n];
        for (Map.Entry<Integer, Map<Integer, Integer>> row : rows.entrySet()) {
            int i = row.getKey();
            if (i < 0 || i >= n) continue;
            for (Map.Entry<Integer, Integer> col : row.getValue().entrySet()) {
                int j = col.getKey();
                if (j >= 0 && j < n) dense[i][j] = col.getValue();
            }
        }
        return dense;
    }
}
