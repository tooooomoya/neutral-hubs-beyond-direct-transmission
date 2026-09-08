package network;

import agent.Agent;
import java.util.*;
import rand.randomGenerator;

/**
 * Directed LFR benchmark network (Lancichinetti-Fortunato-Radicchi 2008).
 *
 * In-degrees follow a power-law P(k) ~ k^{-gamma} (scale-free popularity),
 * consistent with BA/DMS/HolmeKim.  Out-degrees are a byproduct of the wiring
 * and are approximately uniform around avgDegree.
 *
 * Community structure is controlled by the mixing parameter mu:
 *   - a fraction (1-mu) of each node's in-edges come from its own community
 *   - a fraction mu come from other communities
 *   mu=0 → perfect communities, mu=1 → near-random
 *
 * Community sizes follow a power-law with exponent beta.
 *
 * Typical parameters (n=1000):
 *   avgDegree=10, maxDegree=50, mu=0.2, gamma=2.5, beta=1.5, minComm=10, maxComm=50
 */
public class LFRNetwork extends Network {
    private final int    avgDegree;
    private final int    maxDegree;
    private final double mu;
    private final double gamma;
    private final double beta;
    private final int    minComm;
    private final int    maxComm;

    /**
     * @param size      number of nodes
     * @param avgDegree target average in-degree
     * @param maxDegree maximum in-degree
     * @param mu        mixing parameter in [0,1]; fraction of in-edges crossing communities
     * @param gamma     power-law exponent for in-degrees (typically 2–3)
     * @param beta      power-law exponent for community sizes (typically 1–2)
     * @param minComm   minimum community size
     * @param maxComm   maximum community size
     */
    public LFRNetwork(int size, int avgDegree, int maxDegree, double mu,
                      double gamma, double beta, int minComm, int maxComm) {
        super(size);
        if (mu < 0 || mu > 1)         throw new IllegalArgumentException("mu must be in [0, 1]");
        if (gamma < 1)                 throw new IllegalArgumentException("gamma must be >= 1");
        if (minComm < 1 || minComm > maxComm)
            throw new IllegalArgumentException("need 1 <= minComm <= maxComm");
        this.avgDegree = avgDegree;
        this.maxDegree = maxDegree;
        this.mu        = mu;
        this.gamma     = gamma;
        this.beta      = beta;
        this.minComm   = minComm;
        this.maxComm   = maxComm;
    }

    @Override
    public void makeNetwork(Agent[] agentSet) {
        int n = getSize();
        System.out.println("start making LFR network (n=" + n + ", avgDeg=" + avgDegree
                + ", mu=" + mu + ", gamma=" + gamma + ", beta=" + beta + ")");

        // Step 1: assign target in-degrees from power-law
        int[] inDegrees = generatePowerLawDegrees(n);

        // Step 2: assign nodes to communities (sizes ~ power-law with exponent beta)
        int[] community = assignCommunities(n);
        int numComm = 0;
        for (int c : community) if (c + 1 > numComm) numComm = c + 1;

        List<List<Integer>> groups = new ArrayList<>();
        for (int c = 0; c < numComm; c++) groups.add(new ArrayList<>());
        for (int i = 0; i < n; i++) groups.get(community[i]).add(i);

        List<Integer> allNodes = new ArrayList<>();
        for (int i = 0; i < n; i++) allNodes.add(i);

        // Step 3: for each receiver j, pick senders from within/outside its community
        // This wires in-degree directly → power-law in-degree distribution
        for (int j = 0; j < n; j++) {
            int deg      = inDegrees[j];
            int intraDeg = (int) Math.round((1.0 - mu) * deg);
            int interDeg = deg - intraDeg;

            List<Integer> intraPool = new ArrayList<>(groups.get(community[j]));
            List<Integer> interPool = new ArrayList<>(allNodes);
            interPool.removeAll(groups.get(community[j]));

            for (int sender : sampleSenders(j, intraPool, intraDeg)) setEdge(sender, j, 1.0);
            for (int sender : sampleSenders(j, interPool, interDeg)) setEdge(sender, j, 1.0);
        }

        System.out.println("LFR: " + numComm + " communities created");
    }

    // Pick `count` unique senders for receiver `dst` from `pool`, skipping existing edges
    private List<Integer> sampleSenders(int dst, List<Integer> pool, int count) {
        List<Integer> candidates = new ArrayList<>();
        for (int v : pool) {
            if (v != dst && getEdge(v, dst) == 0) candidates.add(v);
        }
        Collections.shuffle(candidates, randomGenerator.network());
        int take = Math.min(count, candidates.size());
        return candidates.subList(0, take);
    }

    // Sample n in-degrees from power-law P(k) ~ k^{-gamma} over [1, maxDegree],
    // rescaled to match avgDegree on average
    private int[] generatePowerLawDegrees(int n) {
        double[] cdf = buildPowerLawCDF(1, maxDegree, gamma);
        int[] degrees = new int[n];
        for (int i = 0; i < n; i++) degrees[i] = sampleFromCDF(cdf, 1);

        double currentAvg = 0;
        for (int d : degrees) currentAvg += d;
        currentAvg /= n;
        double scale = (double) avgDegree / currentAvg;
        for (int i = 0; i < n; i++) {
            degrees[i] = Math.max(1, Math.min(maxDegree, (int) Math.round(degrees[i] * scale)));
        }
        return degrees;
    }

    // Assign nodes to communities whose sizes ~ power-law(beta),
    // then shuffle so membership is not contiguous
    private int[] assignCommunities(int n) {
        double[] cdf = buildPowerLawCDF(minComm, maxComm, beta);
        int[] community = new int[n];
        int commId = 0, assigned = 0;
        while (assigned < n) {
            int size = sampleFromCDF(cdf, minComm);
            int end  = Math.min(assigned + size, n);
            for (int i = assigned; i < end; i++) community[i] = commId;
            assigned = end;
            commId++;
        }
        List<Integer> commList = new ArrayList<>();
        for (int c : community) commList.add(c);
        Collections.shuffle(commList, randomGenerator.network());
        for (int i = 0; i < n; i++) community[i] = commList.get(i);
        return community;
    }

    // CDF for P(k) ~ k^{-exponent} over [min, max]
    private double[] buildPowerLawCDF(int min, int max, double exponent) {
        int len = max - min + 1;
        double[] cdf = new double[len];
        double sum = 0;
        for (int k = min; k <= max; k++) {
            cdf[k - min] = Math.pow(k, -exponent);
            sum += cdf[k - min];
        }
        cdf[0] /= sum;
        for (int i = 1; i < len; i++) cdf[i] = cdf[i - 1] + cdf[i] / sum;
        return cdf;
    }

    // Inverse-CDF sample; returns value in [min, min + cdf.length - 1]
    private int sampleFromCDF(double[] cdf, int min) {
        double r = randomGenerator.network().nextDouble();
        for (int i = 0; i < cdf.length; i++) {
            if (r <= cdf[i]) return min + i;
        }
        return min + cdf.length - 1;
    }
}
