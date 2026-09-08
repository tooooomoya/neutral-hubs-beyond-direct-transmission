package network;

import agent.Agent;
import java.util.*;
import rand.randomGenerator;

/**
 * Directed Holme-Kim network with DMS-style preferential attachment.
 *
 * Each new node adds exactly m directed edges (newNode → target) in two alternating steps:
 *   PA step : target drawn ∝ (k_in + A)  — same pool mechanic as DMSNetwork
 *   TF step : with probability pt, connect to a random out-neighbor of the PA target
 *             (triad formation); skipped silently if no valid candidate exists
 *
 * Both PA and TF edges count toward the m budget, so |out-degree(newNode)| == m always.
 *
 * In-degree distribution: P(k_in) ~ k^{-(2 + A/m)}   (same exponent as DMS)
 * Clustering coefficient: increases monotonically with pt; pt=0 recovers pure DMS/BA
 */
public class HolmeKimNetwork extends Network {
    private final int m;       // out-edges per new node
    private final int A;       // initial attractiveness; controls γ = 2 + A/m
    private final double pt;   // triad formation probability ∈ [0, 1]

    public HolmeKimNetwork(int size, int m, int A, double pt) {
        super(size);
        if (A < 1) throw new IllegalArgumentException("A must be >= 1");
        if (pt < 0 || pt > 1) throw new IllegalArgumentException("pt must be in [0, 1]");
        this.m = m;
        this.A = A;
        this.pt = pt;
    }

    @Override
    public void makeNetwork(Agent[] agentSet) {
        System.out.println("start making Holme-Kim network (m=" + m + ", A=" + A
                + ", gamma=" + (2.0 + (double) A / m) + ", pt=" + pt + ")");

        int n = getSize();

        // pool encodes selection weight: node i appears (k_in(i) + A) times
        List<Integer> pool = new ArrayList<>();

        // Seed: complete directed subgraph on (m+1) nodes
        int seedSize = Math.min(m + 1, n);

        for (int i = 0; i < seedSize; i++) {
            for (int a = 0; a < A; a++) pool.add(i);
        }
        for (int i = 0; i < seedSize; i++) {
            for (int j = 0; j < seedSize; j++) {
                if (i != j) {
                    setEdge(i, j, 1.0);
                    pool.add(j); // j's k_in++
                }
            }
        }

        for (int newNode = seedSize; newNode < n; newNode++) {
            // newNode joins: add initial attractiveness so it can receive in-edges
            for (int a = 0; a < A; a++) pool.add(newNode);

            Set<Integer> targets = new LinkedHashSet<>();

            while (targets.size() < m) {
                // --- PA step ---
                int u = -1;
                int attempts = 0;
                while (attempts < pool.size() * 2) {
                    int candidate = pool.get(randomGenerator.network().nextInt(pool.size()));
                    if (candidate != newNode && !targets.contains(candidate)) {
                        u = candidate;
                        break;
                    }
                    attempts++;
                }
                if (u == -1) break; // pool exhausted (only possible for tiny networks)

                setEdge(newNode, u, 1.0);
                pool.add(u); // u's k_in++
                targets.add(u);

                if (targets.size() == m) break;

                // --- TF step ---
                if (randomGenerator.network().nextDouble() < pt) {
                    List<Integer> outNeighbors = new ArrayList<>();
                    for (int v = 0; v < newNode; v++) {
                        if (getEdge(u, v) > 0 && !targets.contains(v)) {
                            outNeighbors.add(v);
                        }
                    }
                    if (!outNeighbors.isEmpty()) {
                        int v = outNeighbors.get(randomGenerator.network().nextInt(outNeighbors.size()));
                        setEdge(newNode, v, 1.0);
                        pool.add(v); // v's k_in++
                        targets.add(v);
                    }
                    // else: silent skip; next loop iteration does another PA step
                }
            }
        }
    }
}
