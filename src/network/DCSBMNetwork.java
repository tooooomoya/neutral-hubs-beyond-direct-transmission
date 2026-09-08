package network;

import agent.Agent;
import java.util.*;
import rand.randomGenerator;

/**
 * Directed Degree-Corrected Stochastic Block Model (DC-SBM).
 *
 * Edge probability:
 *   P(i→j) = min( θ_out(i) · θ_in(j) · B[c_i, c_j] , 1 )
 *
 *   θ_in(j)  ~ Pareto(γ-1)  via inverse-CDF: (1-u)^{-1/(γ-1)}
 *              → scale-free in-degree (follower popularity)
 *   θ_out(i) ~ CONSTANT | LOGNORMAL | GAMMA
 *              → mildly heterogeneous out-degree (following activity)
 *   B[r][r]  = pIn   (intra-community affinity)
 *   B[r][s]  = pOut  (inter-community affinity, r ≠ s)
 *
 * θ_in is rescaled after sampling so that E[avg in-degree] ≈ targetAvgDegree,
 * then soft-clamped to prevent edge-probability saturation at hubs.
 *
 * Community assignment supports two modes:
 *   BALANCED      – exactly n/K nodes per community (round-robin + shuffle)
 *   HETEROGENEOUS – power-law-distributed sizes (LFR-style), exactly K communities
 *
 * Recommended defaults for Twitter/X-like opinion-dynamics graphs:
 *   n=1000, K=4, pIn=0.08, pOut=0.002, γ=2.3, targetAvgDegree=10
 */
public class DCSBMNetwork extends Network {

    public enum OutDegreeMode { CONSTANT, LOGNORMAL, GAMMA }

    // ---- model parameters ----
    private final int           numCommunities;
    private final double        pIn;
    private final double        pOut;
    private final double        gamma;
    private final int           targetAvgDegree;
    private final OutDegreeMode outMode;
    private final boolean       balancedCommunities;
    // out-mode shape parameters (used only for LOGNORMAL / GAMMA respectively)
    private final double        outSigma;   // lognormal σ  (mild: 0.2–0.5)
    private final double        outShape;   // gamma shape   (narrow: 5–20)

    // ---- state set during makeNetwork ----
    private int[]    community;
    private double[] thetaIn;
    private double[] thetaOut;

    // P(i→j) is capped at this value before applying min(...,1)
    private static final double SATURATION_THRESHOLD = 0.8;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Convenience constructor: constant out-degree, balanced communities.
     * Good starting point for most opinion-dynamics experiments.
     */
    public DCSBMNetwork(int size, int numCommunities, double pIn,
                        double pOut, double gamma, int targetAvgDegree) {
        this(size, numCommunities, pIn, pOut, gamma, targetAvgDegree,
             OutDegreeMode.CONSTANT, true, 0.3, 10.0);
    }

    /**
     * Full constructor.
     *
     * @param size                number of nodes
     * @param numCommunities      number of blocks K (>= 2)
     * @param pIn                 intra-community affinity in (0, 1]
     * @param pOut                inter-community affinity in [0, 1); must be < pIn
     * @param gamma               Pareto exponent for in-degree (> 1; typical: 2.0–2.5)
     * @param targetAvgDegree     target average in-degree
     * @param outMode             CONSTANT | LOGNORMAL | GAMMA
     * @param balancedCommunities true → equal-sized blocks; false → power-law sizes
     * @param outSigma            lognormal σ (LOGNORMAL mode only; suggest 0.2–0.4)
     * @param outShape            gamma shape (GAMMA mode only; suggest 5–20 for narrow dist.)
     */
    public DCSBMNetwork(int size, int numCommunities, double pIn, double pOut,
                        double gamma, int targetAvgDegree,
                        OutDegreeMode outMode, boolean balancedCommunities,
                        double outSigma, double outShape) {
        super(size);
        if (numCommunities < 2)         throw new IllegalArgumentException("numCommunities must be >= 2");
        if (pIn <= 0 || pIn > 1)        throw new IllegalArgumentException("pIn must be in (0, 1]");
        if (pOut < 0 || pOut >= pIn)    throw new IllegalArgumentException("pOut must be in [0, pIn)");
        if (gamma <= 1.0)               throw new IllegalArgumentException("gamma must be > 1 for finite mean");
        if (outShape < 1.0)             throw new IllegalArgumentException("outShape must be >= 1");
        this.numCommunities     = numCommunities;
        this.pIn                = pIn;
        this.pOut               = pOut;
        this.gamma              = gamma;
        this.targetAvgDegree    = targetAvgDegree;
        this.outMode            = outMode;
        this.balancedCommunities = balancedCommunities;
        this.outSigma           = outSigma;
        this.outShape           = outShape;
    }

    // -----------------------------------------------------------------------
    // Network construction
    // -----------------------------------------------------------------------

    @Override
    public void makeNetwork(Agent[] agentSet) {
        int n = getSize();
        System.out.printf("start making DC-SBM (n=%d, K=%d, pIn=%.4f, pOut=%.4f, γ=%.2f, targetDeg=%d)%n",
                n, numCommunities, pIn, pOut, gamma, targetAvgDegree);

        community = balancedCommunities ? assignBalanced(n) : assignHeterogeneous(n);
        thetaIn   = sampleThetaIn(n);
        thetaOut  = sampleThetaOut(n);
        normalizeThetaIn(n);

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                double b = (community[i] == community[j]) ? pIn : pOut;
                double p = Math.min(thetaOut[i] * thetaIn[j] * b, 1.0);
                if (randomGenerator.network().nextDouble() < p) setEdge(i, j, 1.0);
            }
        }

        double realAvgIn = 0;
        for (int d : computeInDegrees()) realAvgIn += d;
        System.out.printf("DC-SBM done: realised avg in-degree = %.2f%n", realAvgIn / n);
    }

    // -----------------------------------------------------------------------
    // θ sampling
    // -----------------------------------------------------------------------

    /**
     * Sample thetaIn from Pareto(α = γ-1) via inverse-CDF:
     *   θ_in = (1 - u)^{-1/(γ-1)},  u ~ Uniform(0,1)
     * Theoretical in-degree exponent: P(k_in) ~ k^{-γ}
     * Mean = (γ-1)/(γ-2) for γ > 2; undefined (but finite sample mean) for γ ∈ (1,2].
     */
    private double[] sampleThetaIn(int n) {
        double[] theta = new double[n];
        double alpha = gamma - 1.0;
        for (int j = 0; j < n; j++) {
            double u = randomGenerator.network().nextDouble();
            theta[j] = Math.pow(1.0 - u, -1.0 / alpha);
        }
        return theta;
    }

    private double[] sampleThetaOut(int n) {
        double[] theta = new double[n];
        switch (outMode) {
            case CONSTANT:
                Arrays.fill(theta, 1.0);
                break;
            case LOGNORMAL:
                // θ_out ~ LogNormal(0, σ²);  mean = exp(σ²/2) ≈ 1 for small σ
                for (int i = 0; i < n; i++)
                    theta[i] = Math.exp(outSigma * standardNormal());
                break;
            case GAMMA:
                // θ_out ~ Gamma(shape, 1/shape);  mean = 1, CV = 1/√shape (narrow)
                for (int i = 0; i < n; i++)
                    theta[i] = sampleGamma(outShape, 1.0 / outShape);
                break;
        }
        return theta;
    }

    /**
     * Rescale thetaIn so E[avg in-degree] ≈ targetAvgDegree, then apply a
     * single-pass soft cap so that no intra-community edge probability exceeds
     * SATURATION_THRESHOLD.  After capping, uncapped values are rescaled upward
     * (bounded by the cap) to partially restore the target mean.
     */
    private void normalizeThetaIn(int n) {
        // avgB: expected affinity over a uniformly random (c_i, c_j) pair
        double avgB      = (pIn + (numCommunities - 1.0) * pOut) / numCommunities;
        double eThetaOut = meanOf(thetaOut);

        // 1. Initial scale: E[in_j] ≈ θ_in(j)·(n-1)·eThetaOut·avgB → avg = targetAvgDegree
        double scale = (double) targetAvgDegree / (meanOf(thetaIn) * (n - 1) * eThetaOut * avgB);
        for (int j = 0; j < n; j++) thetaIn[j] *= scale;

        // 2. Soft cap: P_max = θ_in_cap · max(θ_out) · pIn = SATURATION_THRESHOLD
        double cap     = SATURATION_THRESHOLD / (maxOf(thetaOut) * pIn);
        boolean clipped = false;
        for (int j = 0; j < n; j++) {
            if (thetaIn[j] > cap) { thetaIn[j] = cap; clipped = true; }
        }

        // 3. Rescale uncapped values to recover lost mean — but never push past cap
        if (clipped) {
            double currentMean = meanOf(thetaIn);
            double targetMean  = (double) targetAvgDegree / ((n - 1) * eThetaOut * avgB);
            if (currentMean < targetMean) {
                double maxCurrent = maxOf(thetaIn);
                // cap / maxCurrent ≥ 1 since maxCurrent ≤ cap; take the smaller factor
                double reScale = Math.min(targetMean / currentMean,
                                          maxCurrent > 0 ? cap / maxCurrent : 1.0);
                for (int j = 0; j < n; j++) thetaIn[j] *= reScale;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Community assignment
    // -----------------------------------------------------------------------

    /** Exactly n/K nodes per community (round-robin then global shuffle). */
    private int[] assignBalanced(int n) {
        int[] comm = new int[n];
        for (int i = 0; i < n; i++) comm[i] = i % numCommunities;
        List<Integer> list = new ArrayList<>();
        for (int c : comm) list.add(c);
        Collections.shuffle(list, randomGenerator.network());
        for (int i = 0; i < n; i++) comm[i] = list.get(i);
        return comm;
    }

    /**
     * Exactly K communities with power-law-distributed sizes (LFR-style, exponent 1.5).
     * The first K-1 communities draw sizes from the distribution; the last absorbs the rest.
     * Each community is guaranteed at least one node.
     */
    private int[] assignHeterogeneous(int n) {
        int minSize = Math.max(2, n / (numCommunities * 4));
        int maxSize = Math.max(minSize + 1, n / 2);
        double[] cdf = buildPowerLawCDF(minSize, maxSize, 1.5);

        int[] comm = new int[n];
        int assigned = 0;
        for (int id = 0; id < numCommunities - 1 && assigned < n; id++) {
            // leave at least 1 node for each remaining community
            int remaining = n - assigned;
            int maxForThis = remaining - (numCommunities - 1 - id);
            int size = Math.min(sampleFromCDF(cdf, minSize), maxForThis);
            size = Math.max(size, 1);
            for (int i = assigned; i < assigned + size; i++) comm[i] = id;
            assigned += size;
        }
        for (int i = assigned; i < n; i++) comm[i] = numCommunities - 1;

        List<Integer> list = new ArrayList<>();
        for (int c : comm) list.add(c);
        Collections.shuffle(list, randomGenerator.network());
        for (int i = 0; i < n; i++) comm[i] = list.get(i);
        return comm;
    }

    // -----------------------------------------------------------------------
    // Research / analysis methods (call after makeNetwork)
    // -----------------------------------------------------------------------

    /**
     * Directed Newman-Girvan modularity Q ∈ [-0.5, 1].
     * Q > 0.3 indicates meaningful community structure.
     *
     * Q = (1/m) Σ_{ij} [ A_ij - k_out(i)·k_in(j)/m ] · δ(c_i, c_j)
     */
    public double computeModularity() {
        if (community == null) return Double.NaN;
        int n = getSize();
        int[] kOut = computeOutDegrees();
        int[] kIn  = computeInDegrees();
        long m = 0;
        for (int k : kOut) m += k;
        if (m == 0) return 0.0;

        double q = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j || community[i] != community[j]) continue;
                q += getEdge(i, j) - (double) kOut[i] * kIn[j] / m;
            }
        }
        return q / m;
    }

    /**
     * Local directed clustering coefficient per node.
     * C_i = |{(u,v) : u,v ∈ N(i), u≠v, edge u→v}| / (deg_total(i) · (deg_total(i)-1))
     * where N(i) is the set of nodes connected to i by any directed edge.
     * Returns NaN for nodes with fewer than 2 neighbours.
     */
    public double[] computeClusteringCoefficients() {
        int n = getSize();
        double[] cc = new double[n];
        for (int i = 0; i < n; i++) {
            List<Integer> nb = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                if (j != i && (getEdge(i, j) > 0 || getEdge(j, i) > 0)) nb.add(j);
            }
            int d = nb.size();
            if (d < 2) { cc[i] = Double.NaN; continue; }
            int tri = 0;
            for (int u : nb) for (int v : nb) {
                if (u != v && getEdge(u, v) > 0) tri++;
            }
            cc[i] = (double) tri / ((long) d * (d - 1));
        }
        return cc;
    }

    /** In-degree (follower count) for each node. */
    public int[] computeInDegrees() {
        int n = getSize();
        int[] deg = new int[n];
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++)
                if (getEdge(i, j) > 0) deg[j]++;
        return deg;
    }

    /** Out-degree (following count) for each node. */
    public int[] computeOutDegrees() {
        int n = getSize();
        int[] deg = new int[n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (getEdge(i, j) > 0) deg[i]++;
        return deg;
    }

    /**
     * Degree assortativity: Pearson correlation of
     * (out-degree of edge source, in-degree of edge target) over all directed edges.
     * Positive → hubs tend to follow other hubs.
     */
    public double computeAssortativity() {
        int n = getSize();
        int[] kOut = computeOutDegrees();
        int[] kIn  = computeInDegrees();
        double sJ = 0, sI = 0, sJ2 = 0, sI2 = 0, sJI = 0;
        long m = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (getEdge(i, j) == 0) continue;
                double oi = kOut[i], ii = kIn[j];
                sJ += oi;  sI += ii;
                sJ2 += oi * oi;  sI2 += ii * ii;
                sJI += oi * ii;
                m++;
            }
        }
        if (m == 0) return 0.0;
        double num = m * sJI - sJ * sI;
        double den = Math.sqrt((m * sJ2 - sJ * sJ) * (m * sI2 - sI * sI));
        return (den == 0) ? 0.0 : num / den;
    }

    /**
     * Size of the largest weakly connected component (Union-Find; O(n + m)).
     */
    public int computeGiantComponentSize() {
        int n = getSize();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (getEdge(i, j) > 0 || getEdge(j, i) > 0) union(parent, i, j);
        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < n; i++) counts.merge(find(parent, i), 1, Integer::sum);
        return Collections.max(counts.values());
    }

    /**
     * Average directed shortest path length over all reachable pairs (BFS; O(n²)).
     * Warn: only practical for n ≤ 2000.
     */
    public double computeAverageShortestPath() {
        int n = getSize();
        if (n > 2000) System.err.println(
                "Warning: computeAverageShortestPath() is O(n²) — n=" + n + " will be slow");
        long totalDist = 0;
        long pairs     = 0;
        for (int src = 0; src < n; src++) {
            for (int d : bfs(src)) {
                if (d > 0) { totalDist += d; pairs++; }
            }
        }
        return (pairs == 0) ? Double.POSITIVE_INFINITY : (double) totalDist / pairs;
    }

    /** Community assignment per node (available after makeNetwork). */
    public int[] getCommunityAssignments() {
        return community == null ? new int[0] : community.clone();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** BFS from src; returns dist[] where -1 = unreachable, 0 = self. */
    private int[] bfs(int src) {
        int n = getSize();
        int[] dist = new int[n];
        Arrays.fill(dist, -1);
        dist[src] = 0;
        Queue<Integer> q = new LinkedList<>();
        q.add(src);
        while (!q.isEmpty()) {
            int u = q.poll();
            for (int v = 0; v < n; v++) {
                if (getEdge(u, v) > 0 && dist[v] == -1) {
                    dist[v] = dist[u] + 1;
                    q.add(v);
                }
            }
        }
        return dist;
    }

    private int find(int[] parent, int x) {
        if (parent[x] != x) parent[x] = find(parent, parent[x]);
        return parent[x];
    }

    private void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    /** Box-Muller standard normal sample. */
    private double standardNormal() {
        double u1 = randomGenerator.network().nextDouble();
        double u2 = randomGenerator.network().nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    /** Marsaglia-Tsang Gamma(shape, scale) sampler; requires shape >= 1. */
    private double sampleGamma(double shape, double scale) {
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double z = standardNormal();
            double v = 1.0 + c * z;
            if (v <= 0) continue;
            v = v * v * v;
            double u = randomGenerator.network().nextDouble();
            if (u < 1.0 - 0.0331 * z * z * z * z) return d * v * scale;
            if (Math.log(u) < 0.5 * z * z + d * (1.0 - v + Math.log(v))) return d * v * scale;
        }
    }

    /** CDF for P(k) ∝ k^{-exponent} over discrete range [min, max]. */
    private double[] buildPowerLawCDF(int min, int max, double exponent) {
        int len = max - min + 1;
        double[] cdf = new double[len];
        double sum = 0;
        for (int k = min; k <= max; k++) { cdf[k - min] = Math.pow(k, -exponent); sum += cdf[k - min]; }
        cdf[0] /= sum;
        for (int i = 1; i < len; i++) cdf[i] = cdf[i - 1] + cdf[i] / sum;
        return cdf;
    }

    private int sampleFromCDF(double[] cdf, int min) {
        double r = randomGenerator.network().nextDouble();
        for (int i = 0; i < cdf.length; i++) if (r <= cdf[i]) return min + i;
        return min + cdf.length - 1;
    }

    private double meanOf(double[] arr) { double s = 0; for (double v : arr) s += v; return s / arr.length; }
    private double maxOf(double[] arr)  { double m = arr[0]; for (double v : arr) if (v > m) m = v; return m; }
}
