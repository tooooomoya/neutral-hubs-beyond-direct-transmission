package network;

import agent.Agent;
import java.util.*;
import rand.randomGenerator;

/**
 * Directed scale-free network based on the Dorogovtsev-Mendes-Samukhin (2000) model.
 *
 * Each new node creates m directed edges TO existing nodes chosen with probability
 * proportional to (k_in + A), where A > 0 is the initial attractiveness.
 *
 * - In-degree distribution: P(k_in) ~ k^{-(2 + A/m)}
 * - Out-degree: fixed at m for all post-seed nodes (not scale-free)
 * - A > 0 ensures every node has non-zero selection probability from birth,
 *   so late-arriving nodes can still accumulate followers.
 *
 * Typical parameter choices:
 *   A=1, m=2  → γ ≈ 2.5
 *   A=1, m=3  → γ ≈ 2.33
 *   A=2, m=2  → γ = 3  (recovers undirected BA exponent)
 */
public class DMSNetwork extends Network {
    private final int m; // out-edges each new node creates
    private final int A; // initial attractiveness (integer; controls γ = 2 + A/m)

    public DMSNetwork(int size, int m, int A) {
        super(size);
        if (A < 1) throw new IllegalArgumentException("A must be >= 1 to ensure non-zero base probability");
        this.m = m;
        this.A = A;
    }

    @Override
    public void makeNetwork(Agent[] agentSet) {
        System.out.println("start making DMS network (m=" + m + ", A=" + A + ", gamma=" + (2.0 + (double) A / m) + ")");

        int n = getSize();

        // Pool encodes selection weight: node i appears (k_in(i) + A) times.
        // We initialise each node with A copies on joining, then add 1 per in-edge received.
        List<Integer> pool = new ArrayList<>();

        // Seed: complete directed subgraph on (m+1) nodes so the pool is non-empty
        // before the growth phase begins.
        int seedSize = Math.min(m + 1, n);

        for (int i = 0; i < seedSize; i++) {
            // initial attractiveness A for seed nodes
            for (int a = 0; a < A; a++) {
                pool.add(i);
            }
        }

        for (int i = 0; i < seedSize; i++) {
            for (int j = 0; j < seedSize; j++) {
                if (i != j) {
                    setEdge(i, j, 1.0); // i follows j
                    pool.add(j);        // j's in-degree++
                }
            }
        }

        // Growth phase
        for (int newNode = seedSize; newNode < n; newNode++) {
            // newNode enters: add its A initial-attractiveness entries first,
            // so it is reachable by even earlier selections in the same batch.
            for (int a = 0; a < A; a++) {
                pool.add(newNode);
            }

            Set<Integer> targets = new LinkedHashSet<>();
            while (targets.size() < m) {
                if (pool.isEmpty()) break;
                int candidate = pool.get(randomGenerator.network().nextInt(pool.size()));
                if (candidate != newNode && !targets.contains(candidate)) {
                    targets.add(candidate);
                }
            }

            for (int target : targets) {
                setEdge(newNode, target, 1.0); // directed: newNode follows target only
                pool.add(target);              // target's in-degree++ → weight increases
            }
        }
    }
}
