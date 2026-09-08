package test;

import admin.AdminOptim;
import agent.Agent;
import agent.Post;
import analysis.Analysis;
import analysis.SparseIntMatrix;
import dynamics.StepStats;
import experiment.ExperimentConfig;
import experiment.SimParams;
import rand.randomGenerator;

/**
 * Lightweight assertion harness for the model's pure and near-pure logic (behavior, not parameter
 * values -- for a table of every {@link ExperimentConfig} default against the value stated in the
 * paper, see {@link ParamCheck}). No JUnit dependency; run with
 *   java -cp bin test.ModelTests
 * It exits non-zero if any check fails. End-to-end behavior is not covered here.
 */
public class ModelTests {

    private static int failures = 0;

    private static void check(boolean cond, String name) {
        if (cond) {
            System.out.println("  PASS  " + name);
        } else {
            System.out.println("  FAIL  " + name);
            failures++;
        }
    }

    private static SimParams defaultParams() {
        return SimParams.from(ExperimentConfig.fromArgs(new String[]{}));
    }

    public static void main(String[] args) {
        randomGenerator.init(0, 0);
        System.out.println("== ModelTests ==");

        testConfigRoundTrip();
        testTag();
        testPostLineage();
        testMechanismDefaults();
        testTruncatedInit();
        testRelayAttribution();
        testVelocityRankingCache();
        testChronoRootDedup();
        testBcGatedAssimilationOnly();
        testBcGatedAssimilationFreezesWhenNothingInBc();
        testRepostBcGatedInBcOnly();
        testRepostBcGatedOutOfBcUsesSeparateRate();
        testNeutralBandDefault();
        testNeutralBandExcludesEdgesEntirely();

        System.out.println(failures == 0 ? "ALL PASS" : (failures + " FAILURE(S)"));
        if (failures > 0) System.exit(1);
    }

    // fromArgs must round-trip every new key.
    private static void testConfigRoundTrip() {
        System.out.println("[config round-trip]");
        ExperimentConfig c = ExperimentConfig.fromArgs(new String[]{
            "seed=7", "net_seed=42", "bc_recovery=0.002", "bc_dec=0.98", "init_op=clip",
            "assert_int=250", "network=ba", "repost_prob=0.3", "stub_dist=uniform", "stub_min=0.2",
            "stub_max=0.7", "evict_beta=2", "init_prune=trim", "vel_mode=windowed",
            "n=500",
            "steps=1234", "log_repost_cascade=false", "log_gexf=false",
            "out_of_bc_repost_prob=0.03"
        });
        check(c.seed == 7 && c.resolvedNetSeed() == 42, "seed / net_seed");
        check(c.bcRecoveryRate == 0.002 && c.bcDecRate == 0.98, "bc_recovery + bc_dec");
        check(c.initOpinionClip && c.assertInterval == 250, "init_op / assert_int");
        check(c.networkType.equals("ba") && c.repostProb == 0.3, "network / repost_prob");
        check(c.stubDist.equals("uniform") && c.stubMin == 0.2 && c.stubMax == 0.7, "stub dist");
        check(c.evictBeta == 2 && c.initPrune.equals("trim"), "evict/prune");
        check(c.velMode.equals("windowed"), "vel_mode");
        check(c.n == 500 && c.steps == 1234, "n/steps");
        check(!c.logRepostCascade && !c.logGexf, "toggles");
        check(c.outOfBCRepostProb == 0.03, "out-of-bc repost prob");
    }

    // tag() emits a token only when non-default; distinct configs give distinct folders.
    private static void testTag() {
        System.out.println("[tag]");
        ExperimentConfig def = ExperimentConfig.fromArgs(new String[]{});
        // tag() emits a token for a field if and only if it differs from DEFAULTS, so a
        // fully-default config tags to exactly the identity prefix with nothing appended.
        check(def.tag().equals(""), "default tag is empty");
        ExperimentConfig a = ExperimentConfig.fromArgs(new String[]{"p_u=0.11"});
        check(a.tag().contains("_pu-") && !def.tag().contains("_pu-"), "p_u tagged only when non-default");
        ExperimentConfig b = ExperimentConfig.fromArgs(new String[]{"bc_recovery=0.002", "n=500"});
        check(b.tag().contains("_bcrecovery-") && b.tag().contains("_n-500"), "multiple knobs tagged");
        check(!a.tag().equals(b.tag()) && !a.tag().equals(def.tag()), "distinct configs => distinct tags");
    }

    // relay lineage.
    private static void testPostLineage() {
        System.out.println("[post lineage]");
        Post orig = new Post(5, 0.8, 100);
        check(!orig.isRelay() && orig.getDepth() == 0 && orig.getRootPostId() == orig.getPostId()
                && orig.getParentPostId() == -1 && orig.getRelayerId() == -1, "original defaults");
        Post r1 = Post.relayOf(orig, 7, 5, 0.6, false, false);   // default attribution: author stays 5
        check(r1.isRelay() && r1.getDepth() == 1 && r1.getRootPostId() == orig.getPostId()
                && r1.getParentPostId() == orig.getPostId() && r1.getRelayerId() == 7
                && r1.getPostUserId() == 5, "depth-1 relay lineage + original attribution");
        Post r2 = Post.relayOf(r1, 9, 9, 0.4, false, false);     // reposter attribution: author becomes 9
        check(r2.getDepth() == 2 && r2.getRootPostId() == orig.getPostId()
                && r2.getParentPostId() == r1.getPostId() && r2.getPostUserId() == 9,
                "depth-2 relay keeps root, chains parent, reposter attribution");
    }

    // Which mechanism variant is default, where the choice is a sentinel/sign convention rather
    // than a numeric parameter value (numeric defaults are checked in ParamCheck instead).
    private static void testMechanismDefaults() {
        System.out.println("[mechanism defaults]");
        SimParams p = defaultParams();
        check(p.evictBeta() < 0, "capacity eviction defaults to the argmax-disagreement rule, not softmax (evict_beta<0)");
        check(!p.initOpinionClip(), "initial opinion defaults to truncated resampling, not clipping");
    }

    // truncated init has no exact ±1 atoms and stays in range.
    private static void testTruncatedInit() {
        System.out.println("[truncated init]");
        randomGenerator.init(123, 123);
        SimParams p = defaultParams();
        boolean anyAtom = false, outOfRange = false;
        for (int i = 0; i < 2000; i++) {
            Agent a = new Agent(i, p);
            double o = a.getIntrinsicOpinion();
            if (o == 1.0 || o == -1.0) anyAtom = true;
            if (o < -1.0 || o > 1.0) outOfRange = true;
        }
        check(!anyAtom, "no exact +-1 atoms under truncate");
        check(!outOfRange, "all intrinsic opinions within [-1,1]");
    }

    // attribution switch changes the relayed post's attributed author.
    private static void testRelayAttribution() {
        System.out.println("[attribution]");
        Post orig = new Post(3, 0.5, 0);
        Post asOriginal = Post.relayOf(orig, 8, orig.getPostUserId(), 0.4, false, false);
        Post asReposter = Post.relayOf(orig, 8, 8, 0.4, false, false);
        check(asOriginal.getPostUserId() == 3, "original attribution keeps source author");
        check(asReposter.getPostUserId() == 8, "reposter attribution targets the relayer");
        check(asOriginal.getRelayerId() == 8 && asReposter.getRelayerId() == 8, "relayer recorded either way");
    }

    // rankByVelocity should cache between refreshes (interval = round(1/accessProb))
    // and, once it does refresh, windowed mode should demote a stalled early burst below a post that's
    // gaining reposts right now -- the behavior the dt-always-1 bug prevented.
    private static void testVelocityRankingCache() {
        System.out.println("[velocity ranking cache]");
        // access_prob=0.2 => velocityRankInterval() = round(1/0.2) = 5.
        SimParams p = SimParams.from(ExperimentConfig.fromArgs(new String[]{
            "access_prob=0.2", "vel_mode=windowed", "n=5"
        }));
        double[][] w = new double[5][5];
        AdminOptim admin = new AdminOptim(5, w, p);

        Post stalledBurst = new Post(0, 0.1, 0);   // born step 0
        Post currentlyRising = new Post(1, -0.1, 0); // born step 0, no reposts yet
        admin.addRecentPost(stalledBurst);
        admin.addRecentPost(currentlyRising);
        for (int i = 0; i < 20; i++) stalledBurst.receiveRepost(0); // early burst, then goes quiet

        java.util.List<Post> r0 = admin.rankByVelocity(0);
        check(r0.get(0).getPostId() == stalledBurst.getPostId(),
                "initial ranking: early burst leads (20 reposts vs 0)");

        // Steps 1-4 are within the cache interval (5): ranking must stay frozen even though
        // currentlyRising is now accumulating reposts underneath it.
        for (int i = 0; i < 6; i++) currentlyRising.receiveRepost(3);
        java.util.List<Post> r3 = admin.rankByVelocity(3);
        check(r3 == r0, "cached ranking is the same list instance within the refresh interval");
        check(r3.get(0).getPostId() == stalledBurst.getPostId(),
                "cached ranking unchanged mid-interval despite currentlyRising's gains");

        // Step 5 crosses the refresh interval: windowed score = reposts gained since last rank / dt.
        // stalledBurst gained 0 since step 0's snapshot; currentlyRising gained 6. Must flip order.
        java.util.List<Post> r5 = admin.rankByVelocity(5);
        check(r5 != r0, "ranking recomputes once the refresh interval elapses");
        check(r5.get(0).getPostId() == currentlyRising.getPostId(),
                "windowed mode demotes the stalled burst below the now-rising post");
    }

    // Chronological selection must deduplicate relay copies of the same underlying story by
    // rootPostId (each relay gets a fresh postId but shares its rootPostId, see Post.relayOf), so
    // that a story relayed by several followees cannot occupy several chronological slots.
    private static void testChronoRootDedup() {
        System.out.println("[chrono rootPostId dedup]");
        SimParams p = SimParams.from(ExperimentConfig.fromArgs(new String[]{
            "alpha=0", "n=5"
        }));
        double[][] w = new double[5][5];
        AdminOptim admin = new AdminOptim(5, w, p);
        admin.addFollowEdge(2, 0); // reader 2 follows authors 0 and 1
        admin.addFollowEdge(2, 1);

        Agent[] agentSet = new Agent[5];
        Agent reader = new Agent(2, p);
        agentSet[2] = reader;
        // AdminFeedback now looks up the author's Agent (getHubTier(), for feed-
        // composition-by-author-tier tallying) for every post in the feed, not just the reader's --
        // agentSet must be fully populated like production's, not just the reader slot.
        agentSet[0] = new Agent(0, p);
        agentSet[1] = new Agent(1, p);

        Post original = new Post(9, 0.3, 0);                        // some author outside the follow set
        Post relayByA = Post.relayOf(original, 0, 0, 0.3, false, false);    // author 0 relays it
        Post relayByB = Post.relayOf(original, 1, 1, 0.3, false, false);    // author 1 relays it independently
        check(relayByA.getPostId() != relayByB.getPostId() && relayByA.getRootPostId() == relayByB.getRootPostId(),
                "two relays of the same story: distinct postId, shared rootPostId");
        reader.addToPostCash(relayByA);
        reader.addToPostCash(relayByB);

        admin.AdminFeedback(2, agentSet, 0, null, new StepStats());
        long distinctRoots = reader.getFeed().stream().map(Post::getRootPostId).distinct().count();
        check(reader.getFeed().size() == 1, "only one of the two same-root relays is delivered");
        check(distinctRoots == 1, "delivered feed has no duplicate rootPostId");
    }

    // bc-gated FJ assimilation is now the only behavior (bcGateAssim toggle
    // removed) -- out-of-bc posts still count for bc-decline/comfort but never move the stated
    // opinion. stubbornness=0 isolates the assimilation average from the intrinsic anchor.
    private static void testBcGatedAssimilationOnly() {
        System.out.println("[bc-gated assimilation is the only behavior]");
        SimParams p = defaultParams();
        Agent a = new Agent(0, p);
        a.setOpinion(0.0);
        a.setBoundedConfidence(0.2);
        a.setStubbornness(0.0);

        a.addPostToFeed(new Post(1, 0.1, 0));  // in-bc: |0.1-0.0| < 0.2
        a.addPostToFeed(new Post(2, 0.9, 0));  // out-of-bc: |0.9-0.0| > 0.2, must be ignored

        // updateMyself() now looks up each feed post's author in agentSet (for
        // StepStats.feedClassComfortCountSum) -- must be fully populated like production's, not
        // just the acting agent's own slot (see testChronoRootDedup's identical fix above).
        Agent[] agentSet = new Agent[3];
        agentSet[0] = a;
        agentSet[1] = new Agent(1, p);
        agentSet[2] = new Agent(2, p);
        a.updateMyself(agentSet, new dynamics.StepStats());
        check(Math.abs(a.getOpinion() - 0.1) < 1e-9,
                "opinion assimilates only toward the in-bc post, ignoring the out-of-bc one");
    }

    private static void testBcGatedAssimilationFreezesWhenNothingInBc() {
        System.out.println("[bc-gated assimilation: all-out-of-bc feed freezes opinion]");
        SimParams p = defaultParams();
        Agent a = new Agent(0, p);
        a.setOpinion(0.0);
        a.setBoundedConfidence(0.05);
        a.setStubbornness(0.0);

        a.addPostToFeed(new Post(1, 0.9, 0)); // out-of-bc only

        Agent[] agentSet = new Agent[2];
        agentSet[0] = a;
        agentSet[1] = new Agent(1, p);
        a.updateMyself(agentSet, new dynamics.StepStats());
        check(a.getOpinion() == 0.0, "opinion frozen for the step when nothing in the feed is in-bc");
    }

    // repost is now permanently bc-gated (Agent.repost(), mirroring the
    // bcGateAssim precedent above) -- in-bc posts repost at repostProb, out-of-bc posts at the
    // separate outOfBCRepostProb, no coupling toggle. repost_prob/out_of_bc_repost_prob set to
    // 1.0/0.0 (or vice versa) make the Bernoulli draw deterministic, no RNG seeding needed.
    private static void testRepostBcGatedInBcOnly() {
        System.out.println("[repost bc-gated: in-bc reposts, out-of-bc does not]");
        SimParams p = SimParams.from(ExperimentConfig.fromArgs(
                new String[]{"repost_prob=1.0", "out_of_bc_repost_prob=0.0"}));
        Agent a = new Agent(0, p);
        a.setOpinion(0.0);
        a.setBoundedConfidence(0.2);
        a.addPostToFeed(new Post(1, 0.1, 0));  // in-bc: |0.1-0.0| < 0.2
        a.addPostToFeed(new Post(2, 0.9, 0));  // out-of-bc: |0.9-0.0| > 0.2

        java.util.List<Post> reposted = a.repost(new Agent[]{a});
        check(reposted.size() == 1, "only the in-bc post is reposted when out-of-bc prob is 0");
    }

    private static void testRepostBcGatedOutOfBcUsesSeparateRate() {
        System.out.println("[repost bc-gated: out-of-bc rate is independent, not coupled to repostProb]");
        SimParams p = SimParams.from(ExperimentConfig.fromArgs(
                new String[]{"repost_prob=0.0", "out_of_bc_repost_prob=1.0"}));
        Agent a = new Agent(0, p);
        a.setOpinion(0.0);
        a.setBoundedConfidence(0.2);
        a.addPostToFeed(new Post(1, 0.1, 0));  // in-bc, but repostProb=0 so never reposted
        a.addPostToFeed(new Post(2, 0.9, 0));  // out-of-bc, outOfBCRepostProb=1 so always reposted

        java.util.List<Post> reposted = a.repost(new Agent[]{a});
        check(reposted.size() == 1 && reposted.get(0).getPostOpinion() == 0.9,
                "only the out-of-bc post is reposted when its own rate is 1 and repostProb is 0");
    }

    // sign-based camp metrics (Q_sign, Q_sign_repost, crossCuttingFraction,
    // interactionCrossCuttingFraction) exclude agents with |opinion| < neutralBandHalfWidth from
    // the two-camp partition entirely, mirroring how hub/"target" agents are already excluded.
    private static void testNeutralBandDefault() {
        System.out.println("[neutral band: default width]");
        SimParams p = defaultParams();
        check(p.neutralBandHalfWidth() == 0.2, "neutralBandHalfWidth defaults to 0.2");
    }

    private static void testNeutralBandExcludesEdgesEntirely() {
        System.out.println("[neutral band: excludes neutral agents' edges from sign modularity]");
        Analysis analyzer = new Analysis(5);
        Agent[] agents = new Agent[5];
        SimParams p = defaultParams();
        for (int i = 0; i < 5; i++) agents[i] = new Agent(i, p);
        agents[0].setOpinion(0.5);   // positive camp
        agents[1].setOpinion(0.6);   // positive camp
        agents[2].setOpinion(-0.5);  // negative camp
        agents[3].setOpinion(-0.6);  // negative camp
        agents[4].setOpinion(0.05);  // neutral: |0.05| < 0.2

        SparseIntMatrix onlyNeutralEdges = new SparseIntMatrix();
        onlyNeutralEdges.increment(4, 0);
        onlyNeutralEdges.increment(1, 4);
        double qOnlyNeutral = analyzer.computeSignModularityFromMatrix(agents, onlyNeutralEdges, 0.2);
        check(qOnlyNeutral == 0.0,
                "network with only neutral-touching edges has no countable edges (Q=0, not NaN/garbage)");

        SparseIntMatrix mixed = new SparseIntMatrix();
        mixed.increment(0, 1); // same-camp (positive)
        mixed.increment(2, 3); // same-camp (negative)
        mixed.increment(0, 2); // cross-camp
        double qWithoutNeutral = analyzer.computeSignModularityFromMatrix(agents, mixed, 0.2);
        mixed.increment(4, 0); // add neutral agent's edge into the same graph
        mixed.increment(1, 4);
        double qWithNeutralEdgesAdded = analyzer.computeSignModularityFromMatrix(agents, mixed, 0.2);
        check(Math.abs(qWithoutNeutral - qWithNeutralEdgesAdded) < 1e-9,
                "adding a neutral agent's edges does not change Q_sign_repost once excluded");
    }
}
