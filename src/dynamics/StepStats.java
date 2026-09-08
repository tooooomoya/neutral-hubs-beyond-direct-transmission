package dynamics;

import constants.Const;
import java.util.Arrays;

/**
 * Per-step behavioral-event accumulators. Reset at the start of each step, incremented
 * during the agent loop, read into the metrics row at step end.
 *
 * Most fields are population-scoped (exclude pinned intervention hubs — see OpinionDynamics'
 * {@code population} flag), so hub behavior (exogenously fixed, not organic) doesn't bias
 * population-level behavioral metrics. Fields marked "all agents" are the exception.
 */
public class StepStats {
    public int activeCount;             // population agents processed this step
    public int outGroupExposureCount;   // population: feed posts read with opposite-sign opinion
    public int repostCount;             // population: reposts performed (Agent.getLastReposts())
    public int crossRepostCount;        // population: of the above, opposite-sign reposts
    public int outGroupSanctionCount;   // population: evictions/unfollows targeting an opposite-sign agent
    public int blockCount;              // population: new blocks (Agent.getLastBlocks())
    public int unfollowCount;           // population: successful unfollow() calls
    public int followCount;             // population: successful follow() calls (the all-agents
                                         // counterpart is followActionNum in OpinionDynamics)
    public int evictionCount;           // population: capacity-eviction events

    public int originalPostCount;       // all agents (including pinned hubs)
    public int allRepostCount;          // all agents (including pinned hubs)

    // Feed-composition-by-camp: population agents only, camp = reader's opinionClass (5-bin,
    // Const.NUM_OF_BINS_OF_OPINION) at feed-build time. Counted in AdminOptim.AdminFeedback()
    // before chrono/forYou are merged (channel provenance is otherwise lost once merged into
    // Agent.feed). Sums are divided by feedClassAgentCount[cls] in buildMetricsRow to get the
    // per-agent average count, mirroring the feedPostOpinionMean_i / cRateMean_i convention.
    public double[] feedForYouCountSum = new double[Const.NUM_OF_BINS_OF_OPINION];   // delivered via algorithmic For-You slot
    public double[] feedChronoCountSum = new double[Const.NUM_OF_BINS_OF_OPINION];   // delivered via chronological-from-follows
    public double[] feedRepostCountSum = new double[Const.NUM_OF_BINS_OF_OPINION];   // post is a relay (Post.isRelay())
    public double[] feedOriginalCountSum = new double[Const.NUM_OF_BINS_OF_OPINION]; // post is an original (not relayed)
    public int[] feedClassAgentCount = new int[Const.NUM_OF_BINS_OF_OPINION];        // population agents processed, per class (denominator)
    // Feed composition by AUTHOR tier (Agent.hubTier), same reader-camp indexing as above.
    // feedNeutCountSum counts posts authored by the pinned neutral hub tier (hubTier==2);
    // feedHubCountSum counts posts authored by any pinned hub tier (hubTier>=0). Together they
    // separate "the neutral hub's own content is not comfortable to an extreme reader but still
    // occupies feed slots" from "the neutral hub's absence changes who else fills those slots."
    public double[] feedNeutCountSum = new double[Const.NUM_OF_BINS_OF_OPINION];
    public double[] feedHubCountSum = new double[Const.NUM_OF_BINS_OF_OPINION];
    // Full author-opinion-class feed composition, [readerClass][authorClass], over all authors
    // (hub and non-hub). Uses the author's LIVE opinionClass rather than hubTier, which is frozen
    // at pin time: a non-hub agent that is currently neutral must be distinguishable from the
    // pinned neutral hub inside author-class 2. Gives the share of each opinion class's posts in a
    // given camp's feed directly.
    public double[][] feedAuthorClassCountSum =
            new double[Const.NUM_OF_BINS_OF_OPINION][Const.NUM_OF_BINS_OF_OPINION];
    // Subset of feedAuthorClassCountSum's [readerClass][authorClass] cells that also satisfy the
    // READER's own vocal-comfort-radius check (Agent.updateMyself()'s withinComfort, the same
    // threshold that drives comfortPostRate/postProb). Tallied in Agent.updateMyself(), the only
    // place where a reader's own radius/opinion and each feed post are simultaneously in scope,
    // using the same agentSet[post.getPostUserId()].getOpinionClass() lookup as AdminFeedback's
    // feedAuthorClassCountSum tally within the same agent's turn (feed build and feed read happen
    // back-to-back), so both arrays rest on an identical author classification. The ratio
    // feedClassComfortCountSum[r][a] / feedAuthorClassCountSum[r][a] is therefore the fraction of
    // author-class-a content that is comfortable to reader-class-r.
    public double[][] feedClassComfortCountSum =
            new double[Const.NUM_OF_BINS_OF_OPINION][Const.NUM_OF_BINS_OF_OPINION];

    public void reset() {
        activeCount = 0;
        outGroupExposureCount = 0;
        repostCount = 0;
        crossRepostCount = 0;
        outGroupSanctionCount = 0;
        blockCount = 0;
        unfollowCount = 0;
        followCount = 0;
        evictionCount = 0;
        originalPostCount = 0;
        allRepostCount = 0;
        Arrays.fill(feedForYouCountSum, 0.0);
        Arrays.fill(feedChronoCountSum, 0.0);
        Arrays.fill(feedRepostCountSum, 0.0);
        Arrays.fill(feedOriginalCountSum, 0.0);
        Arrays.fill(feedClassAgentCount, 0);
        Arrays.fill(feedNeutCountSum, 0.0);
        Arrays.fill(feedHubCountSum, 0.0);
        for (double[] row : feedAuthorClassCountSum) Arrays.fill(row, 0.0);
        for (double[] row : feedClassComfortCountSum) Arrays.fill(row, 0.0);
    }
}
