package analysis;

import admin.AdminOptim;
import agent.*;
import constants.Const;
import java.util.*;

public class Analysis {
    private List<Post> postCash;
    private int n;
    private double postOpinionVar;
    private double exposureOpinionVar;
    private double exposureOpinionMean;
    private List<List<Post>> feedList;
    private double[] feedMeanArray = new double[Const.NUM_OF_BINS_OF_OPINION];
    private double[] feedVarArray = new double[Const.NUM_OF_BINS_OF_OPINION];
    private double[] cRateMeanArray = new double[Const.NUM_OF_BINS_OF_OPINION];
    private double[] cRateVarArray = new double[Const.NUM_OF_BINS_OF_OPINION];
    private double[] highComfortRateNumArray = new double[Const.NUM_OF_BINS_OF_OPINION];
    private double[] postShareArray = new double[Const.NUM_OF_BINS_OF_OPINION];
    private Map<Integer, List<Post>> feedMap = new HashMap<>();

    // constructor
    public Analysis(int n) {
        this.n = n;
        this.postCash = new ArrayList<>();
        this.postOpinionVar = -1;
        this.feedList = new ArrayList<>();
    }

    public double[] getFeedMeanArray() {
        return this.feedMeanArray;
    }

    public double[] getFeedVarArray() {
        return this.feedVarArray;
    }

    public double[] getCRateMeanArray() {
        return this.cRateMeanArray;
    }

    public double[] getCRateVarArray() {
        return this.cRateVarArray;
    }

    public double[] getHighComfortRateNumArray() {
        return this.highComfortRateNumArray;
    }

    public double[] getPostShareArray() {
        return this.postShareArray;
    }

    public void clearPostCash() {
        postCash.clear();
    }

    public void clearFeedList() {
        this.feedList.clear();
    }

    public void setPostCash(Post post) {
        postCash.add(post.copyPost());
    }

    public void setFeedList(List<Post> feed) {
        this.feedList.add(new ArrayList<>(feed));
    }

    public void setFeedMap(Agent agent) {
        this.feedMap.put(agent.getId(), new ArrayList<>(agent.getFeed()));
    }

    public void resetFeedMap() {
        feedMap.clear();
    }

    // compute variance of inner opinions
    public double computeVarianceOpinion(Agent[] agentSet) {
        int num = 0;
        if (n == 0) {
            return -1;
        }

        double sum = 0.0;
        for (Agent agent : agentSet) {
            if (!agent.getTarget()) {
                sum += agent.getOpinion();
                num++;
            }
        }
        double mean = sum / num;

        double squaredDiffSum = 0.0;
        for (Agent agent : agentSet) {
            if (!agent.getTarget()) {
                double diff = agent.getOpinion() - mean;
                squaredDiffSum += diff * diff;
            }
        }
        return squaredDiffSum / num;
    }

    // Mean opinion disagreement across follow edges, a measure of echo-chamber strength. Edges
    // touching a pinned hub are skipped, consistent with every other population metric.
    public double computeDisagreement(Agent[] agentSet, AdminOptim admin) {
        if (agentSet.length == 0) return -1;

        double sum = 0.0;
        int edgeCount = 0;

        for (int i = 0; i < agentSet.length; i++) {
            if (agentSet[i].getTarget()) continue;
            for (int j : admin.getFollowees(i)) {
                if (agentSet[j].getTarget()) continue;
                double diff = agentSet[i].getOpinion() - agentSet[j].getOpinion();
                sum += diff * diff;
                edgeCount++;
            }
        }

        return (edgeCount > 0) ? sum / edgeCount : 0;
    }

    // Sarle's bimodality coefficient b = (g1^2 + 1) / (g2 + 3(n-1)^2/((n-2)(n-3))).
    // b > 5/9 (~0.555) suggests bimodality; complements kurtosis. Excludes target hubs.
    public double computeBimodalityCoefficient(Agent[] agentSet) {
        double sum = 0.0; int n = 0;
        for (Agent a : agentSet) if (!a.getTarget()) { sum += a.getOpinion(); n++; }
        if (n < 4) return -1;
        double mean = sum / n;
        double m2 = 0, m3 = 0, m4 = 0;
        for (Agent a : agentSet) {
            if (a.getTarget()) continue;
            double d = a.getOpinion() - mean;
            m2 += d * d; m3 += d * d * d; m4 += d * d * d * d;
        }
        m2 /= n; m3 /= n; m4 /= n;
        if (m2 < 1e-12) return -1;
        double g1 = m3 / Math.pow(m2, 1.5);                 // skewness
        double g2 = m4 / (m2 * m2) - 3.0;                   // excess kurtosis
        double correction = 3.0 * (n - 1.0) * (n - 1.0) / ((n - 2.0) * (n - 3.0));
        return (g1 * g1 + 1.0) / (g2 + correction);
    }

    // ===================================================================
    // Two-dimensional outcome plane used to separate the two forms of polarization:
    //   x-axis = STRUCTURAL separation: opinion assortativity plus cross-cutting-edge fraction
    //   y-axis = AFFECTIVE hostility:   mean bounded confidence, where low means hostile
    // Structural polarization (echo-chamber fragmentation) sits at high separation, high hostility;
    // affective polarization (a connected but hostile network) sits at LOW separation, high
    // hostility. All population metrics exclude the exogenously pinned hubs, as opinionVar does.
    // The structural axis is computed on the follow graph here; Conover et al. (2011) locate the
    // cross-cutting layer in the interaction graph instead, which the interaction-graph variants
    // below measure.
    // ===================================================================

    // y-axis: mean bounded confidence over the population. Lower means more hostile, i.e. less
    // willing to accept out-of-tolerance content. This is the affective axis, used in place of
    // opinion variance.
    public double computeMeanTolerance(Agent[] agentSet) {
        double sum = 0.0;
        int num = 0;
        for (Agent agent : agentSet) {
            if (!agent.getTarget()) {
                sum += agent.getBc();
                num++;
            }
        }
        return (num > 0) ? sum / num : -1;
    }

    public double computeToleranceVar(Agent[] agentSet) {
        double sum = 0.0;
        int num = 0;
        for (Agent agent : agentSet) {
            if (!agent.getTarget()) {
                sum += agent.getBc();
                num++;
            }
        }
        if (num == 0) return -1;
        double mean = sum / num;
        double sq = 0.0;
        for (Agent agent : agentSet) {
            if (!agent.getTarget()) {
                double diff = agent.getBc() - mean;
                sq += diff * diff;
            }
        }
        return sq / num;
    }

    // x-axis (1): Newman scalar assortativity of opinion over directed follow edges (i->j).
    // = Pearson correlation of (O_source, O_target) across edges. High (->1) = homophilous
    // sorting (structural-polarization signature); near 0 / negative = opinions mixed across ties (affective-polarization substrate).
    // Edges touching a target hub are skipped (exogenous opinion would bias the measure).
    public double computeOpinionAssortativity(Agent[] agentSet, AdminOptim admin) {
        double sx = 0.0, sy = 0.0, sxy = 0.0, sx2 = 0.0, sy2 = 0.0;
        long m = 0;
        for (int i = 0; i < agentSet.length; i++) {
            if (agentSet[i].getTarget()) continue;
            double x = agentSet[i].getOpinion();
            for (int j : admin.getFollowees(i)) {
                if (agentSet[j].getTarget()) continue;
                double y = agentSet[j].getOpinion();
                sx += x; sy += y; sxy += x * y; sx2 += x * x; sy2 += y * y;
                m++;
            }
        }
        if (m == 0) return 0.0;
        double cov = sxy / m - (sx / m) * (sy / m);
        double vx = sx2 / m - (sx / m) * (sx / m);
        double vy = sy2 / m - (sy / m) * (sy / m);
        double denom = Math.sqrt(vx * vy);
        return (denom > 1e-12) ? cov / denom : 0.0;
    }

    // x-axis (2): fraction of follow edges that cross the O=0 divide (sign(O_i) != sign(O_j)).
    // High = network stays connected across camps (affective-polarization substrate); low = camps
    // separated (structural polarization). Complements assortativity; insensitive to within-camp spread.
    public double computeCrossCuttingFraction(Agent[] agentSet, AdminOptim admin, double neutralBandHalfWidth) {
        long cross = 0, total = 0;
        for (int i = 0; i < agentSet.length; i++) {
            if (agentSet[i].getTarget() || isNeutral(agentSet[i], neutralBandHalfWidth)) continue;
            double oi = agentSet[i].getOpinion();
            for (int j : admin.getFollowees(i)) {
                if (agentSet[j].getTarget() || isNeutral(agentSet[j], neutralBandHalfWidth)) continue;
                double oj = agentSet[j].getOpinion();
                if (oi * oj < 0) cross++;
                total++;
            }
        }
        return (total > 0) ? (double) cross / total : 0.0;
    }

    // Interaction-graph variants of the structural axis. The follow graph is segregated by
    // construction (Conover et al. 2011: endorsement and retweeting are homophilous), so measuring
    // separation there measures it where it is guaranteed to appear. The cross-cutting signal
    // instead lives in who relays whom, tracked cumulatively over the run in
    // OpinionDynamics.repostNetworkCumulative. Each edge is weighted by repost count rather than
    // mere presence, since a repeatedly used relay path is a stronger signal than a one-off.
    // Iterates realized (i -> j) repost entries only, not a dense N-by-N grid.
    public double computeInteractionAssortativity(Agent[] agentSet, SparseIntMatrix interactionNetwork) {
        double sx = 0.0, sy = 0.0, sxy = 0.0, sx2 = 0.0, sy2 = 0.0;
        long m = 0;
        for (Map.Entry<Integer, Map<Integer, Integer>> row : interactionNetwork.rowEntries()) {
            int i = row.getKey();
            if (agentSet[i].getTarget()) continue;
            double x = agentSet[i].getOpinion();
            for (Map.Entry<Integer, Integer> col : row.getValue().entrySet()) {
                int j = col.getKey();
                int c = col.getValue();
                if (c <= 0 || agentSet[j].getTarget()) continue;
                double y = agentSet[j].getOpinion();
                sx += c * x; sy += c * y; sxy += c * x * y; sx2 += c * x * x; sy2 += c * y * y;
                m += c;
            }
        }
        if (m == 0) return 0.0;
        double cov = sxy / m - (sx / m) * (sy / m);
        double vx = sx2 / m - (sx / m) * (sx / m);
        double vy = sy2 / m - (sy / m) * (sy / m);
        double denom = Math.sqrt(vx * vy);
        return (denom > 1e-12) ? cov / denom : 0.0;
    }

    public double computeInteractionCrossCuttingFraction(Agent[] agentSet, SparseIntMatrix interactionNetwork, double neutralBandHalfWidth) {
        long cross = 0, total = 0;
        for (Map.Entry<Integer, Map<Integer, Integer>> row : interactionNetwork.rowEntries()) {
            int i = row.getKey();
            if (agentSet[i].getTarget() || isNeutral(agentSet[i], neutralBandHalfWidth)) continue;
            double oi = agentSet[i].getOpinion();
            for (Map.Entry<Integer, Integer> col : row.getValue().entrySet()) {
                int j = col.getKey();
                int c = col.getValue();
                if (c <= 0 || agentSet[j].getTarget() || isNeutral(agentSet[j], neutralBandHalfWidth)) continue;
                double oj = agentSet[j].getOpinion();
                if (oi * oj < 0) cross += c;
                total += c;
            }
        }
        return (total > 0) ? (double) cross / total : 0.0;
    }

    // Relationship between extremity and tolerance: Pearson correlation of (|opinion|, bc) over
    // all population agents. A negative value means extremists hold tighter tolerance than
    // moderates; a value near zero or positive means no such relationship. Structurally the same
    // correlation as computeOpinionAssortativity, taken over agent-level (extremity, bc) pairs
    // instead of edge-level (source opinion, target opinion) pairs.
    public double computeBcExtremityCorrelation(Agent[] agentSet) {
        double sx = 0.0, sy = 0.0, sxy = 0.0, sx2 = 0.0, sy2 = 0.0;
        long m = 0;
        for (Agent agent : agentSet) {
            if (agent.getTarget()) continue;
            double x = Math.abs(agent.getOpinion());
            double y = agent.getBc();
            sx += x; sy += y; sxy += x * y; sx2 += x * x; sy2 += y * y;
            m++;
        }
        if (m == 0) return 0.0;
        double cov = sxy / m - (sx / m) * (sy / m);
        double vx = sx2 / m - (sx / m) * (sx / m);
        double vy = sy2 / m - (sy / m) * (sy / m);
        double denom = Math.sqrt(vx * vy);
        return (denom > 1e-12) ? cov / denom : 0.0;
    }

    // affective/bimodality companion: excess kurtosis of all (non-target) inner opinions.
    // Negative (platykurtic) => bimodal / two-camp distribution; positive => peaked/unimodal.
    // Distinguishes "opinions moved apart into two modes" from a single consensus mode in a
    // way variance alone cannot.
    public double computeOpinionKurtosis(Agent[] agentSet) {
        double sum = 0.0;
        int num = 0;
        for (Agent agent : agentSet) {
            if (!agent.getTarget()) { sum += agent.getOpinion(); num++; }
        }
        if (num < 2) return 0.0;
        double mean = sum / num;
        double m2 = 0.0, m4 = 0.0;
        for (Agent agent : agentSet) {
            if (!agent.getTarget()) {
                double d = agent.getOpinion() - mean;
                m2 += d * d;
                m4 += d * d * d * d;
            }
        }
        m2 /= num; m4 /= num;
        return (m2 > 1e-12) ? m4 / (m2 * m2) - 3.0 : 0.0;
    }

    // Agents with |opinion| < halfWidth are excluded from every sign-based camp metric below
    // (Q_sign, Q_sign_repost, crossCuttingFraction, interactionCrossCuttingFraction). This mirrors
    // Conover et al. (2011)'s exclusion of undecidable accounts; the threshold itself is ours, as
    // that paper partitions by network clustering rather than by an opinion threshold. See
    // ExperimentConfig.neutralBandHalfWidth.
    private static boolean isNeutral(Agent a, double halfWidth) {
        return Math.abs(a.getOpinion()) < halfWidth;
    }

    // directed Newman modularity of the sign(opinion) partition on the current follow graph.
    // Q = (1/m) Σ_ij [A_ij - k_out(i)k_in(j)/m] δ(s_i,s_j). Folding the null term over all same-sign
    // ordered pairs gives Q = (sameSignEdges - (KoutPos·KinPos + KoutNeg·KinNeg)/m) / m. O(edges).
    // High Q = the two opinion camps are structurally separated on the follow graph. Excludes hubs
    // and neutral agents (see isNeutral above).
    public double computeSignModularity(Agent[] agentSet, AdminOptim admin, double neutralBandHalfWidth) {
        int[] kOut = new int[agentSet.length];
        int[] kIn = new int[agentSet.length];
        long m = 0;
        for (int i = 0; i < agentSet.length; i++) {
            if (agentSet[i].getTarget() || isNeutral(agentSet[i], neutralBandHalfWidth)) continue;
            for (int j : admin.getFollowees(i)) {
                if (agentSet[j].getTarget() || isNeutral(agentSet[j], neutralBandHalfWidth)) continue;
                kOut[i]++; kIn[j]++; m++;
            }
        }
        if (m == 0) return 0.0;

        long koPos = 0, kiPos = 0, koNeg = 0, kiNeg = 0;
        for (int i = 0; i < agentSet.length; i++) {
            if (agentSet[i].getTarget() || isNeutral(agentSet[i], neutralBandHalfWidth)) continue;
            if (agentSet[i].getOpinion() >= 0) { koPos += kOut[i]; kiPos += kIn[i]; }
            else                                { koNeg += kOut[i]; kiNeg += kIn[i]; }
        }
        double nullTerm = ((double) koPos * kiPos + (double) koNeg * kiNeg) / m;

        long sameSignEdges = 0;
        for (int i = 0; i < agentSet.length; i++) {
            if (agentSet[i].getTarget() || isNeutral(agentSet[i], neutralBandHalfWidth)) continue;
            boolean posi = agentSet[i].getOpinion() >= 0;
            for (int j : admin.getFollowees(i)) {
                if (agentSet[j].getTarget() || isNeutral(agentSet[j], neutralBandHalfWidth)) continue;
                if (posi == (agentSet[j].getOpinion() >= 0)) sameSignEdges++;
            }
        }
        return (sameSignEdges - nullTerm) / m;
    }

    // Repost-network counterpart to computeSignModularity. The repost graph, not the follow graph,
    // is the correct referent for the ~0.7-0.8 modularity reported by Conover et al. (2011): follow
    // graphs are segregated by construction, so a follow-graph modularity target would be measured
    // on the wrong network. Same Q formula as computeSignModularity, generalized to weighted edges
    // (repost count rather than presence) via SparseIntMatrix, matching how
    // computeInteractionAssortativity weights its edges.
    public double computeSignModularityFromMatrix(Agent[] agentSet, SparseIntMatrix net, double neutralBandHalfWidth) {
        long[] kOut = new long[agentSet.length];
        long[] kIn = new long[agentSet.length];
        long w = 0;
        for (Map.Entry<Integer, Map<Integer, Integer>> row : net.rowEntries()) {
            int i = row.getKey();
            if (agentSet[i].getTarget() || isNeutral(agentSet[i], neutralBandHalfWidth)) continue;
            for (Map.Entry<Integer, Integer> col : row.getValue().entrySet()) {
                int j = col.getKey();
                int c = col.getValue();
                if (c <= 0 || agentSet[j].getTarget() || isNeutral(agentSet[j], neutralBandHalfWidth)) continue;
                kOut[i] += c; kIn[j] += c; w += c;
            }
        }
        if (w == 0) return 0.0;

        long koPos = 0, kiPos = 0, koNeg = 0, kiNeg = 0;
        for (int i = 0; i < agentSet.length; i++) {
            if (agentSet[i].getTarget() || isNeutral(agentSet[i], neutralBandHalfWidth)) continue;
            if (agentSet[i].getOpinion() >= 0) { koPos += kOut[i]; kiPos += kIn[i]; }
            else                                { koNeg += kOut[i]; kiNeg += kIn[i]; }
        }
        double nullTerm = ((double) koPos * kiPos + (double) koNeg * kiNeg) / w;

        long sameSignWeight = 0;
        for (Map.Entry<Integer, Map<Integer, Integer>> row : net.rowEntries()) {
            int i = row.getKey();
            if (agentSet[i].getTarget() || isNeutral(agentSet[i], neutralBandHalfWidth)) continue;
            boolean posi = agentSet[i].getOpinion() >= 0;
            for (Map.Entry<Integer, Integer> col : row.getValue().entrySet()) {
                int j = col.getKey();
                int c = col.getValue();
                if (c <= 0 || agentSet[j].getTarget() || isNeutral(agentSet[j], neutralBandHalfWidth)) continue;
                if (posi == (agentSet[j].getOpinion() >= 0)) sameSignWeight += c;
            }
        }
        return (sameSignWeight - nullTerm) / w;
    }

    // compute Shannon-Wiener index to represent diversity of opinions
    public double computeShannonWienerIndex(Agent[] agentSet) {
        int totalAgents = 0;
        int[] counts = new int[Const.NUM_OF_BINS_OF_OPINION];

        // 1. Count agents in each opinion bin
        for (Agent agent : agentSet) {
            if (!agent.getTarget()) { // Assuming we exclude target/media nodes
                int bin = agent.getOpinionClass();
                if (bin >= 0 && bin < counts.length) {
                    counts[bin]++;
                    totalAgents++;
                }
            }
        }

        if (totalAgents == 0) return 0.0;

        // 2. Calculate H' = -sum(pi * ln(pi))
        double shannonIndex = 0.0;
        for (int count : counts) {
            if (count > 0) {
                double p = (double) count / totalAgents;
                shannonIndex -= p * Math.log(p);
            }
        }

        return shannonIndex;
    }

    // compute mean of inner opinions
    public double computeMeanOpinion(Agent[] agentSet) {
        int num = 0;
        if (n == 0 || agentSet == null || agentSet.length == 0) {
            return -1;
        }

        double sum = 0.0;
        for (Agent agent : agentSet) {
            if (!agent.getTarget()) {
                sum += agent.getOpinion();
                num++;
            }
        }
        return sum / num;
    }

    // compute variance of opinions on posts at a step
    public double computeFeedVariance() {
        double temp = 0.0;
        int postNum = 0;

        for (List<Post> feed : this.feedList) {
            for (Post post : feed) {
                temp += post.getPostOpinion();
                postNum++;
            }
        }

        if (postNum == 0) {
            System.out.println("no post was read by users in this step.");
            return -1;
        }

        double avg = temp / postNum;

        double squaredDiffSum = 0.0;
        for (List<Post> feed : this.feedList) {
            for (Post post : feed) {
                double diff = post.getPostOpinion() - avg;
                squaredDiffSum += diff * diff;
            }
        }

        return squaredDiffSum / postNum;
    }

    // compute mean and var of every agent's feed
    public void computeFeedMetrics(Agent[] agentSet) {
        Arrays.fill(this.feedMeanArray, 0.0);
        Arrays.fill(this.feedVarArray, 0.0);

        double[] classVarianceSum = new double[Const.NUM_OF_BINS_OF_OPINION];
        int[] agentCount = new int[Const.NUM_OF_BINS_OF_OPINION];

        for (Map.Entry<Integer, List<Post>> entry : feedMap.entrySet()) {
            Integer userId = entry.getKey();
            List<Post> feed = entry.getValue();
            Agent agent = agentSet[userId];
            int classId = agent.getOpinionClass();

            if (feed.isEmpty())
                continue;

            double sum = 0.0;
            for (Post post : feed) {
                sum += post.getPostOpinion();
            }
            double mean = sum / feed.size();
            this.feedMeanArray[classId] += mean;

            double var = 0.0;
            for (Post post : feed) {
                double diff = post.getPostOpinion() - mean;
                var += diff * diff;
            }
            var /= feed.size(); // or (feed.size() - 1) for unbiased

            classVarianceSum[classId] += var;
            agentCount[classId]++;
        }

        for (int i = 0; i < Const.NUM_OF_BINS_OF_OPINION; i++) {
            if (agentCount[i] != 0) {
                this.feedVarArray[i] = classVarianceSum[i] / agentCount[i];
                this.feedMeanArray[i] = this.feedMeanArray[i] / agentCount[i];
            }
        }

    }

    // compute mean and var of every agent's comfort rate
    public void computeCRateArray(Agent[] agentSet) {
        Arrays.fill(this.cRateMeanArray, 0.0);
        Arrays.fill(this.cRateVarArray, 0.0);

        double[] classVarianceSum = new double[Const.NUM_OF_BINS_OF_OPINION];
        int[] agentCount = new int[Const.NUM_OF_BINS_OF_OPINION];

        for (Map.Entry<Integer, List<Post>> entry : feedMap.entrySet()) {
            Integer userId = entry.getKey();
            Agent agent = agentSet[userId];
            int classId = agent.getOpinionClass();

            double cRate = agent.getAverageComfortRate(); // the agent's own trailing comfort-rate average

            this.cRateMeanArray[classId] += cRate;
            classVarianceSum[classId] += cRate * cRate; // sum of squares, for the variance below
            agentCount[classId]++;
        }

        // Per-class mean and variance.
        for (int i = 0; i < Const.NUM_OF_BINS_OF_OPINION; i++) {
            if (agentCount[i] != 0) {
                double mean = this.cRateMeanArray[i] / agentCount[i];
                double meanSq = classVarianceSum[i] / agentCount[i];
                double var = meanSq - mean * mean; // Var = E[x^2] - (E[x])^2

                this.cRateMeanArray[i] = mean;
                this.cRateVarArray[i] = var;
            }
        }
    }

    // Vocal-minority readout: the share of this step's ORIGINAL posts authored by each opinion
    // class. The population is the same as for postOpinionVar, i.e. reposts and pinned-hub posts
    // are excluded. Complements cRateMean_i, which measures receptiveness, by measuring output
    // volume instead.
    public void computePostShareArray(Agent[] agentSet) {
        Arrays.fill(this.postShareArray, 0.0);
        int total = postCash.size();
        if (total == 0) return;
        for (Post post : postCash) {
            int classId = agentSet[post.getPostUserId()].getOpinionClass();
            this.postShareArray[classId] += 1.0;
        }
        for (int i = 0; i < this.postShareArray.length; i++) this.postShareArray[i] /= total;
    }

    public void computePostVariance() {
        int size = postCash.size();
        if (size == 0) {
            this.postOpinionVar = -1;
            return;
        }

        double sum = 0.0;
        for (Post post : postCash) {
            sum += post.getPostOpinion();
        }
        double mean = sum / size;

        double squaredDiffSum = 0.0;
        for (Post post : postCash) {
            double diff = post.getPostOpinion() - mean;
            squaredDiffSum += diff * diff;
        }
        this.postOpinionVar = squaredDiffSum / size;
    }

    public double getPostOpinionVar() {
        return postOpinionVar;
    }

    public double getExposureOpinionVar() {
        return exposureOpinionVar;
    }

    public double getExposureOpinionMean() {
        return exposureOpinionMean;
    }

    // Opinion distribution of the content actually delivered to feeds this step. Unlike
    // postOpinionVar, which covers original posts only, this includes reposts and is
    // reach-weighted: a widely reposted post is counted in every feed it reaches, so repost-hub
    // amplification is reflected. Posts authored by pinned hubs are excluded, so the metric
    // describes the population's own exposure. The mean also serves as a one-sided capture signal
    // under an asymmetric opinion background.
    public void computeExposureMetrics(Agent[] agentSet) {
        double sum = 0.0;
        long count = 0;
        for (List<Post> feed : feedMap.values()) {
            for (Post post : feed) {
                if (agentSet[post.getPostUserId()].getTarget()) continue;
                sum += post.getPostOpinion();
                count++;
            }
        }

        if (count == 0) {
            this.exposureOpinionVar = -1;
            this.exposureOpinionMean = 0.0;
            return;
        }

        double mean = sum / count;
        double squaredDiffSum = 0.0;
        for (List<Post> feed : feedMap.values()) {
            for (Post post : feed) {
                if (agentSet[post.getPostUserId()].getTarget()) continue;
                double diff = post.getPostOpinion() - mean;
                squaredDiffSum += diff * diff;
            }
        }

        this.exposureOpinionMean = mean;
        this.exposureOpinionVar = squaredDiffSum / count;
    }

    public double[] computeClusteringCoefficients(double[][] adj) {
        double[] clustering = new double[n];

        for (int i = 0; i < n; i++) {

            Set<Integer> neighbors = new HashSet<>();
            for (int j = 0; j < n; j++) {
                if (adj[i][j] > 0.0)
                    neighbors.add(j); // out-neighbor
                if (adj[j][i] > 0.0)
                    neighbors.add(j); // in-neighbor
            }
            neighbors.remove(i);

            int k_total = neighbors.size();
            if (k_total < 2) {
                clustering[i] = 0.0;
                continue;
            }

            int linkCount = 0;
            for (int u : neighbors) {
                for (int v : neighbors) {
                    if (u != v && adj[u][v] > 0.0) {
                        linkCount++;
                    }
                }
            }

            clustering[i] = (double) linkCount / (k_total * (k_total - 1));
        }

        return clustering;
    }

    // Per-opinion-class echo-chamber structure on the follow graph. Restricted to (a) the graph's
    // largest connected component, using undirected reachability (adj[i][j]>0 or adj[j][i]>0), and
    // (b) population agents, excluding pinned hubs as elsewhere. For each of the
    // NUM_OF_BINS_OF_OPINION classes it reports the size, directed-edge density, mean undirected
    // local clustering coefficient, and triangle count of the subgraph induced on the population
    // agents of that class inside the largest component. It uses the standard undirected clustering
    // formula 2*links/(k*(k-1)) rather than the directed ordered-pair convention of
    // computeClusteringCoefficients, since this is a cohesion metric in its own right rather than a
    // variant of the per-agent clustering export.
    public EchoChamberStats[] computeEchoChamberByClass(Agent[] agentSet, double[][] adj) {
        int n = adj.length;
        int[] comp = findConnectedComponents(adj);
        int numComp = 0;
        for (int c : comp) numComp = Math.max(numComp, c + 1);
        int[] compSize = new int[numComp];
        for (int c : comp) compSize[c]++;
        int largest = 0;
        for (int i = 1; i < numComp; i++) if (compSize[i] > compSize[largest]) largest = i;

        List<List<Integer>> byClass = new ArrayList<>();
        for (int c = 0; c < Const.NUM_OF_BINS_OF_OPINION; c++) byClass.add(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            if (agentSet[i].getTarget()) continue;      // population only (R1 hub-exclusion convention)
            if (comp[i] != largest) continue;            // LCC only
            byClass.get(agentSet[i].getOpinionClass()).add(i);
        }

        EchoChamberStats[] result = new EchoChamberStats[Const.NUM_OF_BINS_OF_OPINION];
        for (int c = 0; c < Const.NUM_OF_BINS_OF_OPINION; c++) {
            List<Integer> members = byClass.get(c);
            int k = members.size();
            EchoChamberStats s = new EchoChamberStats();
            s.nodeCount = k;
            if (k >= 2) {
                long directedEdges = 0;
                for (int i : members) {
                    for (int j : members) {
                        if (i != j && adj[i][j] > 0.0) directedEdges++;
                    }
                }
                s.density = (double) directedEdges / ((double) k * (k - 1));

                double clusterSum = 0.0;
                long triangleSum = 0;
                for (int i : members) {
                    List<Integer> nb = new ArrayList<>();
                    for (int j : members) {
                        if (j != i && (adj[i][j] > 0.0 || adj[j][i] > 0.0)) nb.add(j);
                    }
                    int kk = nb.size();
                    if (kk >= 2) {
                        int linked = 0;
                        for (int a = 0; a < kk; a++) {
                            for (int b = a + 1; b < kk; b++) {
                                int u = nb.get(a), v = nb.get(b);
                                if (adj[u][v] > 0.0 || adj[v][u] > 0.0) linked++;
                            }
                        }
                        clusterSum += 2.0 * linked / ((double) kk * (kk - 1));
                        triangleSum += linked;
                    }
                }
                s.avgClusteringCoeff = clusterSum / k;
                s.triangleCount = triangleSum / 3; // each triangle counted once per vertex above
            }
            result[c] = s;
        }
        return result;
    }

    // Undirected connected-component labeling (BFS), treating adj[i][j]>0 OR adj[j][i]>0 as an edge
    // -- same symmetrization convention computeClusteringCoefficients uses for neighbor membership.
    private int[] findConnectedComponents(double[][] adj) {
        int n = adj.length;
        int[] comp = new int[n];
        Arrays.fill(comp, -1);
        int next = 0;
        for (int start = 0; start < n; start++) {
            if (comp[start] != -1) continue;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            comp[start] = next;
            while (!queue.isEmpty()) {
                int u = queue.poll();
                for (int v = 0; v < n; v++) {
                    if (comp[v] == -1 && (adj[u][v] > 0.0 || adj[v][u] > 0.0)) {
                        comp[v] = next;
                        queue.add(v);
                    }
                }
            }
            next++;
        }
        return comp;
    }

    public static class EchoChamberStats {
        public int nodeCount;
        public double density;
        public double avgClusteringCoeff;
        public long triangleCount;
    }

    public void computeHighComfortRateNumArray(Agent[] agentSet) {
        Arrays.fill(this.highComfortRateNumArray, 0.0);

        for (Agent agent : agentSet) {
            int classId = agent.getOpinionClass();
            double cRate = agent.getAverageComfortRate();
            if (cRate >= Const.OPINION_PREVALENCE) {
                this.highComfortRateNumArray[classId] += 1.0;
            }
        }
    }
}
