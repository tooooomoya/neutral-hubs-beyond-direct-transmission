package writer;

import agent.*;
import constants.Const;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Batched CSV writer for the three per-step streams.
 *
 * The metrics stream is a generic {@link MetricsRow}, so adding a metric requires no per-metric
 * field, setter, or switch case here. Each stream is one appended file per run
 * ({@code metrics/results.csv}, {@code posts/post_result.csv},
 * {@code opinion/opinion_result.csv}), and rows are batched in memory and flushed every
 * BATCH_SIZE steps.
 */
public class Writer {

    private int simulationStep;
    private final String folderPath;

    // Current per-step metrics row, set by OpinionDynamics before write().
    private MetricsRow currentMetrics;

    // posts / opinion bin streams
    private final int[] postBins = new int[Const.NUM_OF_BINS_OF_POSTS];
    private final double postBinWidth;
    private final int[] opinionBins = new int[Const.NUM_OF_BINS_OF_OPINION_FOR_WRITER];

    private static final int BATCH_SIZE = 1000;
    private int batchStartStep = 0;
    private final StringBuilder metricsBuf = new StringBuilder();
    private final StringBuilder postsBuf = new StringBuilder();
    private final StringBuilder opinionBuf = new StringBuilder();

    // Frozen headers; a metrics keyset that later differs from metricsHeader is a hard error.
    private String metricsHeader;
    private final String postsHeader;
    private final String opinionHeader;
    private boolean metricsFileStarted = false;
    private boolean postsFileStarted = false;
    private boolean opinionFileStarted = false;

    // Repost-cascade (relay-event) log, buffered and flushed with the batch.
    private final StringBuilder repostCascadeBuf = new StringBuilder();
    private boolean repostCascadeFileStarted = false;
    private static final String REPOST_CASCADE_HEADER =
            "step,rootPostId,parentPostId,postId,author,relayer,depth,conveyedOpinion,relayerOpinion,outOfBcRelay,viaAlgo";

    // Per-post lifespan log: one row per post at the moment it is evicted from AdminOptim's
    // For-You candidate window. Only posts that received at least one repost are logged, so this
    // file holds one row per post rather than one per repost event.
    private final StringBuilder postLifespanBuf = new StringBuilder();
    private boolean postLifespanFileStarted = false;
    private static final String POST_LIFESPAN_HEADER =
            "evictionStep,postId,author,postedStep,lastRepostStep,lifespan,receivedReposts,stillActiveAtEviction";

    public Writer(String folderPath) {
        this.folderPath = folderPath;
        this.postBinWidth = 2.0 / postBins.length;

        StringBuilder ph = new StringBuilder("step");
        for (int i = 0; i < postBins.length; i++) ph.append(",bin_").append(i);
        ph.append(",sumOfPosts");
        this.postsHeader = ph.toString();

        StringBuilder oh = new StringBuilder("step");
        for (int i = 0; i < opinionBins.length; i++) oh.append(",bin_").append(i);
        this.opinionHeader = oh.toString();
    }

    // ---- setters (only step, the metrics row, and the two bin streams remain) ----

    public void setSimulationStep(int step) {
        this.simulationStep = step;
    }

    /** Hand over the fully built metrics row for this step. */
    public void setMetrics(MetricsRow row) {
        this.currentMetrics = row;
    }

    // Append one relay event to the repost-cascade buffer. relayerOpinion is the relayer's own
    // opinion at the moment of the relay, recorded here rather than looked up from a final
    // snapshot, so that post-hoc camp classification does not depend on how long the run continued.
    public void logRepostCascade(int step, Post relayed, double relayerOpinion) {
        repostCascadeBuf.append(step).append(',')
                  .append(relayed.getRootPostId()).append(',')
                  .append(relayed.getParentPostId()).append(',')
                  .append(relayed.getPostId()).append(',')
                  .append(relayed.getPostUserId()).append(',')
                  .append(relayed.getRelayerId()).append(',')
                  .append(relayed.getDepth()).append(',')
                  .append(String.format("%.4f", relayed.getPostOpinion())).append(',')
                  .append(String.format("%.4f", relayerOpinion)).append(',')
                  .append(relayed.isOutOfBcRelay() ? 1 : 0).append(',')
                  .append(relayed.isViaAlgo() ? 1 : 0).append('\n');
    }

    // One row per post-eviction event (AdminOptim.drainLifespanEvents). The author column is the
    // post's attributed author at eviction time, which relay attribution may have changed from the
    // original poster, following the same convention as repost_cascades.csv.
    public void logPostLifespan(int evictionStep, Post post, boolean stillActiveAtEviction) {
        postLifespanBuf.append(evictionStep).append(',')
                  .append(post.getPostId()).append(',')
                  .append(post.getPostUserId()).append(',')
                  .append(post.getPostedStep()).append(',')
                  .append(post.getLastRepostStep()).append(',')
                  .append(post.getLastRepostStep() - post.getPostedStep()).append(',')
                  .append(post.getReceivedReposts()).append(',')
                  .append(stillActiveAtEviction ? 1 : 0).append('\n');
    }

    public void clearPostBins() {
        for (int i = 0; i < postBins.length; i++) postBins[i] = 0;
    }

    public void setPostBins(Post post) {
        double shiftedOpinion = post.getPostOpinion() + 1;
        int binIndex = (int) Math.min(shiftedOpinion / postBinWidth, postBins.length - 1);
        postBins[binIndex] += 1;
    }

    public void setOpinionBins(Agent[] agentSet) {
        double binWidth = 2.0 / Const.NUM_OF_BINS_OF_OPINION_FOR_WRITER;
        for (int i = 0; i < opinionBins.length; i++) opinionBins[i] = 0;
        for (Agent agent : agentSet) {
            if (!agent.getTarget()) {
                // Bin straight from the raw opinion value into NUM_OF_BINS_OF_OPINION_FOR_WRITER
                // equal-width bins over [-1,1] (independent of Agent.opinionClass).
                double shiftedOpinion = agent.getOpinion() + 1; // [-1,1] -> [0,2]
                int binIndex = (int) Math.min(shiftedOpinion / binWidth,
                        Const.NUM_OF_BINS_OF_OPINION_FOR_WRITER - 1);
                this.opinionBins[binIndex] += 1;
            }
        }
    }

    // ---- write ----

    public void write() {
        // metrics stream
        if (currentMetrics != null) {
            String header = "step," + currentMetrics.headerCsv();
            if (metricsHeader == null) {
                metricsHeader = header;
            } else if (!metricsHeader.equals(header)) {
                throw new IllegalStateException(
                        "MetricsRow schema changed mid-run.\n expected: "
                        + metricsHeader + "\n got:      " + header);
            }
            metricsBuf.append(simulationStep);
            for (Number v : currentMetrics.values()) {
                metricsBuf.append(',');
                appendNumber(metricsBuf, v);
            }
            metricsBuf.append('\n');
        }

        // posts stream
        postsBuf.append(simulationStep);
        int sumOfPosts = 0;
        for (int bin : postBins) {
            postsBuf.append(',').append(bin);
            sumOfPosts += bin;
        }
        postsBuf.append(',').append(sumOfPosts).append('\n');

        // opinion stream
        opinionBuf.append(simulationStep);
        for (int bin : opinionBins) opinionBuf.append(',').append(bin);
        opinionBuf.append('\n');

        if (simulationStep > 0 && (simulationStep - batchStartStep + 1) >= BATCH_SIZE) {
            flushBatch();
        }
    }

    public void flush() {
        if (metricsBuf.length() > 0 || postsBuf.length() > 0 || opinionBuf.length() > 0
                || repostCascadeBuf.length() > 0 || postLifespanBuf.length() > 0) {
            flushBatch();
        }
    }

    private void flushBatch() {
        appendToFile(metricsBuf, folderPath + "/metrics/results.csv", metricsHeader, metricsFileStarted);
        metricsFileStarted = true;
        appendToFile(postsBuf, folderPath + "/posts/post_result.csv", postsHeader, postsFileStarted);
        postsFileStarted = true;
        appendToFile(opinionBuf, folderPath + "/opinion/opinion_result.csv", opinionHeader, opinionFileStarted);
        opinionFileStarted = true;
        if (repostCascadeBuf.length() > 0) {
            appendToFile(repostCascadeBuf, folderPath + "/posts/repost_cascades.csv", REPOST_CASCADE_HEADER, repostCascadeFileStarted);
            repostCascadeFileStarted = true;
            repostCascadeBuf.setLength(0);
        }
        if (postLifespanBuf.length() > 0) {
            appendToFile(postLifespanBuf, folderPath + "/posts/post_lifespan.csv", POST_LIFESPAN_HEADER, postLifespanFileStarted);
            postLifespanFileStarted = true;
            postLifespanBuf.setLength(0);
        }

        metricsBuf.setLength(0);
        postsBuf.setLength(0);
        opinionBuf.setLength(0);
        batchStartStep = simulationStep + 1;
    }

    // First write to a stream truncates + emits the header; subsequent writes append rows only.
    private void appendToFile(StringBuilder buf, String filePath, String header, boolean started) {
        if (buf.length() == 0) return;
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, started))) {
            if (!started && header != null) pw.println(header);
            pw.print(buf);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Integer-valued metrics stay integers; everything else is written as a 4-decimal double.
    private void appendNumber(StringBuilder sb, Number v) {
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            sb.append(v.longValue());
        } else {
            sb.append(String.format("%.4f", v.doubleValue()));
        }
    }

    public void writeDegrees(double[][] adjacencyMatrix, String outputDirPath) {
        String filePath = outputDirPath + "/degrees/degree_result_" + simulationStep + ".csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, false))) {
            pw.println("agentId,inDegree,outDegree");
            int numAgents = adjacencyMatrix.length;
            for (int i = 0; i < numAgents; i++) {
                int outDegree = 0;
                int inDegree = 0;
                for (int j = 0; j < numAgents; j++) {
                    outDegree += (adjacencyMatrix[i][j] > 0) ? 1 : 0;
                }
                for (int j = 0; j < numAgents; j++) {
                    inDegree += (adjacencyMatrix[j][i] > 0) ? 1 : 0;
                }
                pw.printf("%d,%d,%d%n", i, inDegree, outDegree);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Opinion-bin breakdown of each pinned hub's CURRENT followers, written at the same periodic
    // cadence as writeDegrees. It answers whether a pinned hub's follower base is a
    // population-representative cross-section or skews toward a particular opinion bin. Only called
    // when a hub set is pinned, so it costs nothing in other experiments. The 5-bin convention
    // matches Analysis: shifted = opinion + 1.0, bin = floor(shifted / 0.4) clamped to [0,4].
    public void writeClassFollowerComposition(agent.Agent[] agentSet, double[][] adjacencyMatrix,
            java.util.Set<Integer> classIds, String outputDirPath) {
        String filePath = outputDirPath + "/degrees/class_follower_composition_" + simulationStep + ".csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, false))) {
            pw.println("classAgentId,nFollowers,bin0,bin1,bin2,bin3,bin4");
            int n = adjacencyMatrix.length;
            for (int id : classIds) {
                int[] binCounts = new int[5];
                int nFollowers = 0;
                for (int j = 0; j < n; j++) {
                    if (adjacencyMatrix[j][id] > 0) {
                        nFollowers++;
                        double shifted = agentSet[j].getOpinion() + 1.0;
                        int bin = Math.min((int) (shifted / 0.4), 4);
                        binCounts[bin]++;
                    }
                }
                pw.printf("%d,%d,%d,%d,%d,%d,%d%n", id, nFollowers,
                        binCounts[0], binCounts[1], binCounts[2], binCounts[3], binCounts[4]);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Per-opinion-class echo-chamber structure (see Analysis.computeEchoChamberByClass): one row
    // per opinion-class bin, one file per periodic checkpoint.
    public void writeEchoChamberByClass(analysis.Analysis.EchoChamberStats[] stats, String outputDirPath) {
        String filePath = outputDirPath + "/degrees/echo_chamber_by_class_" + simulationStep + ".csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, false))) {
            pw.println("class,nodeCount,density,avgClusteringCoeff,triangleCount");
            for (int c = 0; c < stats.length; c++) {
                pw.printf("%d,%d,%.6f,%.6f,%d%n", c, stats[c].nodeCount, stats[c].density,
                        stats[c].avgClusteringCoeff, stats[c].triangleCount);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Per-agent snapshot, so that hub-attributable cross-camp bridging can be computed post-hoc
    // from repost_cascades.csv. The cross-cutting metrics exclude every edge touching a pinned hub
    // by design and therefore cannot see the hubs' own bridging edges; this snapshot supplies the
    // per-agent opinion lookup needed to measure those separately.
    public void writeAgentSnapshot(agent.Agent[] agentSet, admin.AdminOptim admin, String outputDirPath, String label) {
        String filePath = outputDirPath + "/degrees/agent_snapshot_" + label + ".csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, false))) {
            // avgComfortRate is the trailing average the agent already maintains for its own
            // bc/postProb feedback loop. It is exported per agent because cRateMean_i in
            // metrics/results.csv mixes pinned hubs and ordinary agents within a bin, whereas the
            // isTarget column here lets the two be separated in post-hoc analysis.
            pw.println("agentId,intrinsicOpinion,opinion,bc,followerCount,followeeCount,isTarget,avgComfortRate");
            for (int i = 0; i < agentSet.length; i++) {
                agent.Agent a = agentSet[i];
                pw.printf("%d,%.4f,%.4f,%.4f,%d,%d,%d,%.4f%n",
                        i, a.getIntrinsicOpinion(), a.getOpinion(), a.getBc(),
                        admin.getFollowers(i).size(), admin.followeeCount(i),
                        a.getTarget() ? 1 : 0, a.getAverageComfortRate());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Sign-partition modularity, appended to a side CSV. Q_sign is measured on the follow graph and
    // Q_sign_repost on the repost graph (Analysis.computeSignModularityFromMatrix); the latter is
    // the one comparable to the ~0.7-0.8 reported by Conover et al. (2011), which is a
    // retweet-graph statistic.
    private boolean modularityFileStarted = false;
    public void writeModularity(int step, double qSign, double qSignRepost, String outputDirPath) {
        String filePath = outputDirPath + "/metrics/modularity.csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, modularityFileStarted))) {
            if (!modularityFileStarted) pw.println("step,Q_sign,Q_sign_repost");
            pw.printf("%d,%.6f,%.6f%n", step, qSign, qSignRepost);
            modularityFileStarted = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void writeClusteringCoefficients(double[] clustering, String outputDirPath) {
        String filePath = outputDirPath + "/clusterings/clustering_result_" + simulationStep + ".csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, false))) {
            pw.println("agentId,clusteringCoefficient");
            for (int i = 0; i < clustering.length; i++) {
                pw.printf("%d,%.6f%n", i, clustering[i]);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Repost-graph counterparts to writeDegrees/writeClusteringCoefficients, computed on the same
    // repost matrix that is exported at the same call site in OpinionDynamics, so that the repost
    // network's fitted statistics always describe exactly the graph that was exported.
    public void writeRepostDegrees(double[][] adjacencyMatrix, String outputDirPath) {
        String filePath = outputDirPath + "/degrees/repost_degree_result_" + simulationStep + ".csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, false))) {
            pw.println("agentId,inDegree,outDegree");
            int numAgents = adjacencyMatrix.length;
            for (int i = 0; i < numAgents; i++) {
                int outDegree = 0;
                int inDegree = 0;
                for (int j = 0; j < numAgents; j++) {
                    outDegree += (adjacencyMatrix[i][j] > 0) ? 1 : 0;
                }
                for (int j = 0; j < numAgents; j++) {
                    inDegree += (adjacencyMatrix[j][i] > 0) ? 1 : 0;
                }
                pw.printf("%d,%d,%d%n", i, inDegree, outDegree);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Empirical 7-bin calibration fit (Const.EMPIRICAL_OPINION_BIN_COUNTS and
    // EMPIRICAL_POST_BIN_WEIGHTS). postCountByBin is cumulative over the whole run and binned by
    // intrinsic rather than current opinion; popCountByBin is fixed at construction, since
    // intrinsic opinion never changes, and confirms that the initial-opinion sampler reproduces
    // the target proportions. Written once at the end of the run.
    public void writePostBin7(long[] postCountByBin, long[] popCountByBin, String outputDirPath) {
        writePostBin7ToFile(postCountByBin, popCountByBin, outputDirPath + "/metrics/post_bin7.csv");
    }

    // Step-indexed variant, written at the periodic checkpoints in OpinionDynamics.evolve(), so
    // that the evolution of the 7-bin posting-rate shape can be inspected and not only its final
    // cumulative value. postCountByBin is cumulative since t=0 at every call.
    public void writePostBin7(long[] postCountByBin, long[] popCountByBin, String outputDirPath, int step) {
        writePostBin7ToFile(postCountByBin, popCountByBin, outputDirPath + "/metrics/post_bin7_" + step + ".csv");
    }

    private void writePostBin7ToFile(long[] postCountByBin, long[] popCountByBin, String filePath) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, false))) {
            pw.println("bin,popCount,postCount");
            for (int i = 0; i < postCountByBin.length; i++) {
                pw.printf("%d,%d,%d%n", i, popCountByBin[i], postCountByBin[i]);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void writeRepostClusteringCoefficients(double[] clustering, String outputDirPath) {
        String filePath = outputDirPath + "/clusterings/repost_clustering_result_" + simulationStep + ".csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, false))) {
            pw.println("agentId,clusteringCoefficient");
            for (int i = 0; i < clustering.length; i++) {
                pw.printf("%d,%.6f%n", i, clustering[i]);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
