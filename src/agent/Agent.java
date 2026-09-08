package agent;

import admin.AdminOptim;
import constants.Const;
import experiment.SimParams;
import java.util.*;
import rand.randomGenerator;

public class Agent {
    // Which spreading channels an agent is allowed to use. Decouples the two ways a hub spreads
    // content (original posts vs. reposts) so that their effects can be isolated. NONE is the
    // silenced condition of Section "Modeling the Neutral Hub Intervention": the agent still reads
    // its feed and updates its ties, it simply emits nothing.
    public enum ChannelMode { BOTH, POST_ONLY, REPOST_ONLY, NONE }

    private int id;
    private double opinion;
    private double stubbornness;
    private double bc; // Bounded Confidence
    private double intrinsicOpinion;
    private final int NUM_OF_AGENTS; // set from SimParams.n() in the constructor
    private int toPost; // % of posts at a step
    private int numOfPosts; // maximum % of posts that an agent can read at a step
    private int opinionClass;
    private PostCash postCash; // posts in feeds shall be selected from cash
    private double postProb;
    private List<Post> feed = new ArrayList<>(); // timeline
 
    // which rootPostIds this step's feed delivered via the algorithmic For-You stream
    // (set fresh each step by AdminOptim.AdminFeedback, before repost() runs later the same step --
    // chrono and For-You are dedup'd against each other by rootPostId there, so a root is never in
    // both, making this membership check unambiguous). Everything else in feed this step is chrono.
    private java.util.Set<Integer> forYouRootIds = java.util.Set.of();
    private double accessProb; // set from SimParams in the constructor; see getAccessProb/setAccessProb
    private boolean target = false; // for further simulation like using bots
 
    // Hub-tier label used by the feed-composition metrics. -1 = not a pinned hub. For pinned hubs
    // it is a FROZEN snapshot of getOpinionClass() taken once when the hub is pinned, deliberately
    // not re-derived from the live opinion: pinned hubs are not fully stubborn, so their opinion
    // can drift slightly after t=0 and a live re-classification could relabel a hub mid-run.
    // Follows the 5-bin convention (bin 2 = neutral, bins 1/3 = moderate, bins 0/4 = extreme).
    private int hubTier = -1;
    private int timeStep;
    // the follow graph is owned solely by AdminOptim; agents keep only their own block list.
    private final boolean[] blockedUserList; // sized from NUM_OF_AGENTS in the constructor
    private boolean used; // whether agent uses platform or not
    private double repostProb;
    private boolean postEnabled = true;   // whether this agent broadcasts its own posts
    private boolean repostEnabled = true; // whether this agent emits reposts
    private static final int COMFORT_MEMORY_SIZE = 100;  // comfort-rate history length, in accesses
    private Deque<Double> comfortHistory = new ArrayDeque<>();
    private final SimParams p; // instance-scoped runtime parameters
    // Transient per-step behavioral-event counters, reset at the start of each action call and
    // read by OpinionDynamics after the call.
    private int lastReposts = 0;
    private int lastCrossReposts = 0;
    private int lastBlocks = 0;


    // constructor
    public Agent(int agentID, SimParams params) {
        this.id = agentID;
        this.p = params;
        this.NUM_OF_AGENTS = params.n();
        this.blockedUserList = new boolean[NUM_OF_AGENTS];
        // Intrinsic opinion O_i(0). The default is a Gaussian truncated to [-1,1] by rejection
        // sampling; initOpinionClip instead clips to the bounds, which piles ~4.8% of the mass on
        // the two atoms at exactly +-1. Two further distributions are available as robustness arms:
        // "empirical" samples the asymmetric 7-bin survey distribution
        // (Const.EMPIRICAL_OPINION_BIN_COUNTS) directly, since a symmetric Gaussian has no skew
        // parameter that could be fitted to that shape; "uniform" draws U[-1,1], which removes the
        // Gaussian's central concentration and hence the uneven camp sizes it induces.
        if (p.initOpinionDist().equals("empirical")) {
            this.intrinsicOpinion = sampleEmpiricalOpinion();
        } else if (p.initOpinionDist().equals("uniform")) {
            this.intrinsicOpinion = -1.0 + randomGenerator.dynamics().nextDouble() * 2.0;
        } else if (p.initOpinionClip()) {
            this.intrinsicOpinion = Math.max(-1.0, Math.min(1.0, randomGenerator.dynamics().nextGaussian() * Const.INITIAL_OPINION_STD));
        } else {
            double x;
            do {
                x = randomGenerator.dynamics().nextGaussian() * Const.INITIAL_OPINION_STD;
            } while (Math.abs(x) > 1.0);
            this.intrinsicOpinion = x;
        }
        
        // Stubbornness lambda. "uniform" draws it per agent from [stubMin, stubMax] (setting the
        // two equal gives the homogeneous population used in the reported experiments); "const"
        // uses Const.INITIAL_STUBBORNNESS. Fully pinned hubs override this to 1.0 after
        // construction, and the relay-distortion strength couples to the final value.
        if (p.stubDist().equals("uniform")) {
            this.stubbornness = p.stubMin() + randomGenerator.dynamics().nextDouble() * (p.stubMax() - p.stubMin());
        } else {
            this.stubbornness = Const.INITIAL_STUBBORNNESS;
        }
        this.opinion = this.intrinsicOpinion;
        this.bc = p.bcInit(); // dynamic threshold; its initial value is independent of bcCeiling
        this.postProb = p.initialPostProb();
        this.timeStep = 0;
        this.repostProb = p.repostProb();
        this.accessProb = p.accessProb(); // was Const.ACCESS_PROB, now sweepable
        // The per-agent post buffer and the feed delivered by AdminOptim must share one capacity
        // knob; sizing them independently silently bottlenecks the feed at the smaller of the two.
        setNumOfPosts(p.feedCapacity());
        setOpinionClass();
    }

    // 7 equal-width bins spanning [-1,1] (width 2/7 each), in ascending self-reported-lean order.
    // Shared by the empirical initial-opinion sampler below and by OpinionDynamics' cumulative
    // post-count-by-bin accumulator, so that both use exactly the same bin boundaries.
    public static int opinionToBin7(double opinion) {
        int bin = (int) Math.floor((opinion + 1.0) / (2.0 / Const.NUM_OF_BINS_EMPIRICAL_7));
        return Math.max(0, Math.min(Const.NUM_OF_BINS_EMPIRICAL_7 - 1, bin));
    }

    // Draws a bin proportional to Const.EMPIRICAL_OPINION_BIN_COUNTS, then samples uniformly
    // within that bin's continuous sub-interval of [-1,1] (not 7 point masses -- real respondents
    // sharing a self-reported label don't share one exact underlying opinion value).
    private double sampleEmpiricalOpinion() {
        int[] counts = Const.EMPIRICAL_OPINION_BIN_COUNTS;
        int total = 0;
        for (int c : counts) total += c;
        int r = randomGenerator.dynamics().nextInt(total);
        int bin = 0;
        int cum = 0;
        for (int i = 0; i < counts.length; i++) {
            cum += counts[i];
            if (r < cum) { bin = i; break; }
        }
        double width = 2.0 / Const.NUM_OF_BINS_EMPIRICAL_7;
        double lo = -1.0 + bin * width;
        return lo + randomGenerator.dynamics().nextDouble() * width;
    }

    // getter methods

    public int getId() {
        return this.id;
    }

    public double getOpinion() {
        return this.opinion;
    }

    public double getIntrinsicOpinion() {
        return this.intrinsicOpinion;
    }

    public double getStubbornness() {
        return this.stubbornness;
    }

    public int getNumOfPosts() {
        return this.numOfPosts;
    }

    public int getToPost() {
        return this.toPost;
    }

    public int getOpinionClass() {
        return this.opinionClass;
    }

    public double getBc() {
        return this.bc;
    }

    public double getPostProb() {
        return this.postProb;
    }

    public List<Post> getFeed() {
        return this.feed;
    }

    public double getAccessProb() {
        return this.accessProb;
    }

    public PostCash getPostCash() {
        return this.postCash;
    }

    public boolean[] getBlockedUserList() {
        return this.blockedUserList;
    }

    public boolean getTarget() {
        return this.target;
    }

    public int getHubTier() {
        return this.hubTier;
    }

    public double getAverageComfortRate() {
        if (comfortHistory.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double v : comfortHistory) sum += v;
        return sum / comfortHistory.size();
    }

    // transient counters from the most recent repost()/unfollow() call this step.
    public int getLastReposts() { return this.lastReposts; }
    public int getLastCrossReposts() { return this.lastCrossReposts; }
    public int getLastBlocks() { return this.lastBlocks; }

    // setter methods

    public void setOpinion(double value) {
        this.opinion = value;
        setOpinionClass();
    }

    public void setPostProb(double value) {
        this.postProb = value;
    }

    public void setAccessProb(double value) {
        this.accessProb = value;
    }

    public void setBoundedConfidence(double value) {
        this.bc = value;
    }

    public void setTimeStep(int time) {
        this.timeStep = time;
    }

    public void setStubbornness(double value) {
        this.stubbornness = value;
    }

    public void setIntrinsicOpinion(double value) {
        this.intrinsicOpinion = value;
    }

    public void setNumOfPosts(int value) {
        this.numOfPosts = value;
        setPostCash(this.numOfPosts);
    }

    public void setPostCash(int value) {
        this.postCash = new PostCash(value);
    }

    public void setToPost(int value) {
        this.toPost = value;
    }

    public void setOpinionClass() {
        double shiftedOpinion = this.opinion + 1; // [-1,1] → [0,2]
        double opinionBinWidth = 2.0 / Const.NUM_OF_BINS_OF_OPINION;
        this.opinionClass = (int) Math.min(shiftedOpinion / opinionBinWidth, Const.NUM_OF_BINS_OF_OPINION - 1);
    }

    public void setTarget() {
        this.target = true;
    }

    public void setHubTier(int tier) {
        this.hubTier = tier;
    }

    // Assign which spreading channels this agent is allowed to use.
    public void setChannelMode(ChannelMode mode) {
        this.postEnabled = (mode == ChannelMode.BOTH || mode == ChannelMode.POST_ONLY);
        this.repostEnabled = (mode == ChannelMode.BOTH || mode == ChannelMode.REPOST_ONLY);
    }

    public boolean isPostEnabled() {
        return this.postEnabled;
    }

    public boolean isRepostEnabled() {
        return this.repostEnabled;
    }

    public void addToPostCash(Post post) {
        if (post.getPostUserId() != this.id
                && !this.blockedUserList[post.getPostUserId()]) {
            this.postCash.addPost(post);
        }
    }

    public void setUsed() {
        this.used = true;
    }

    public void resetUsed() {
        this.used = false;
    }

    // other methods

    public void resetPostCash() {
        this.postCash.reset();
        this.toPost = 0;
    }

    public void addPostToFeed(Post post) {
        if (!this.blockedUserList[post.getPostUserId()]) {
            this.feed.add(post);
        }
    }

    public void resetFeed() {
        this.feed.clear();
    }

    public void setForYouRootIds(java.util.Set<Integer> ids) {
        this.forYouRootIds = ids;
    }

    public void shufflePostCash(){
        this.postCash.shuffle();
    }

    /**
     * One platform access by this agent: read the feed, then update bounded confidence, posting
     * probability and opinion from what was read.
     *
     * {@code agentSet} and {@code stepStats} are needed because this is the only place where the
     * reader's own comfort radius and each feed post's author are simultaneously in scope, so the
     * per-author-class comfort tally (StepStats.feedClassComfortCountSum) has to be taken here
     * rather than in AdminOptim.AdminFeedback, which builds the feed but does not know the
     * reader's per-post comfort verdict.
     */
    public void updateMyself(Agent[] agentSet, dynamics.StepStats stepStats) {
        int postNum = 0;
        int comfortPostNum = 0;
        
        // Friedkin-Johnsen assimilation averages only posts inside the agent's current bounded
        // confidence. 
        double assimSum = 0.0;
        int assimNum = 0;

        // read all posts in feed
        for (Post post : this.feed) {
            postNum++;

            double bcDist = Math.abs(post.getPostOpinion() - this.opinion);
            boolean inBcRange = bcDist < this.bc;
            if (inBcRange) {
                assimSum += post.getPostOpinion();
                assimNum++;
            }

            // vocal comfort tally 
            boolean withinComfort = Math.abs(post.getPostOpinion() - this.opinion) < p.vocalComfortRadius();
            if (withinComfort) {
                comfortPostNum++;
            }

            if (!this.target && withinComfort) {
                int authorClass = agentSet[post.getPostUserId()].getOpinionClass();
                stepStats.feedClassComfortCountSum[this.opinionClass][authorClass]++;
            }

        }

        // bounded confidece update 
        if (postNum > 0) {
            double inBcRate = (double) assimNum / postNum;
            double targetBC = p.bcFloor() + (p.bcCeiling() - p.bcFloor()) * inBcRate;
            if (targetBC < this.bc) {
                this.bc += p.bcDecRate() * (targetBC - this.bc);
            } else {
                this.bc += p.bcRecoveryRate() * (targetBC - this.bc);
            }
        }

        // posting probability update
        if (postNum > 0) {
            double comfortPostRate = (double) comfortPostNum / postNum;

            comfortHistory.addLast(comfortPostRate);
            if (comfortHistory.size() > COMFORT_MEMORY_SIZE) {
                comfortHistory.removeFirst();
            }
            
            // social reinforcement mechanism 
            double targetPostProb = p.minPostProb() + (p.maxPostProb() - p.minPostProb()) * comfortPostRate;
            this.postProb += p.postProbRelaxRate() * (targetPostProb - this.postProb);
        }

        //// Social influence
        // Update only when at least one in-tolerance post is available: an empty average would be
        // 0/0 = NaN and would corrupt the opinion irrecoverably. A feed with nothing in tolerance
        // leaves the opinion unchanged for this access, as specified in the paper.
        if (assimNum > 0) {
            this.opinion = this.stubbornness * this.intrinsicOpinion + (1 - this.stubbornness) * (assimSum / assimNum);
        }

        //// clipping

        // opinion is in [-1, 1]
        this.opinion = Math.max(-1.0, Math.min(this.opinion, 1.0));

        // postProb is in [minPostProb, maxPostProb]
        this.postProb = Math.max(p.minPostProb(), Math.min(this.postProb, p.maxPostProb()));

        // bc is in [p.bcFloor(), p.bcCeiling()]; the upper clamp matters because recovery can push
        // bc back up, not only decline toward the floor.
        this.bc = Math.max(this.bc, p.bcFloor());
        this.bc = Math.min(this.bc, p.bcCeiling());

        setOpinionClass();
    }

    public List<Post> repost(Agent[] agents) {
        this.lastReposts = 0;
        this.lastCrossReposts = 0;
        // A silenced agent emits no reposts, but still reads its feed and updates normally.
        if (!this.repostEnabled) {
            return Collections.emptyList();
        }
        if (this.feed.isEmpty()) {
            return Collections.emptyList();
        }

        // Reposting is gated by bounded confidence
        List<Post> repostedPostList = new ArrayList<>();
        for (Post post : this.feed) {
            boolean inBc = Math.abs(post.getPostOpinion() - this.opinion) < this.bc;
            double pr = inBc ? this.repostProb : p.outOfBCRepostProb();
            if (randomGenerator.dynamics().nextDouble() < pr) {
                // Boost the velocity signal on the object the For-You ranking tracks (the feed
                // post itself), before constructing the relayed copy.
                post.receiveRepost(this.timeStep);
                // A cross-cutting repost is one where the reposter's opinion and the post content
                // have opposite signs, judged on the pre-relay opinion the reposter endorsed.
                this.lastReposts++;
                if (this.opinion * post.getPostOpinion() < 0) this.lastCrossReposts++;
                boolean viaAlgo = this.forYouRootIds.contains(post.getRootPostId());
                Post relayed = relay(post, !inBc, viaAlgo);
                repostedPostList.add(relayed);
            }
        }
        return repostedPostList;
    }

    /**
     * Build the relayed copy of {@code original} that this agent's repost puts into its followers'
     * feeds: verbatim opinion, source author keeps attribution, matching the repost rule stated in
     * the paper.
     *
     * Relay lineage (rootPostId/parentPostId/relayerId/depth) is always recorded, for cascade
     * logging. {@code outOfBc} records whether the relay crossed the reposter's own tolerance
     * window at read time; it is only a tag on the Post and never changes the conveyed opinion.
     */
    private Post relay(Post original, boolean outOfBc, boolean viaAlgo) {
        return Post.relayOf(original, this.id, original.getPostUserId(), original.getPostOpinion(), outOfBc, viaAlgo);
    }

    /**
     * Follow action for one access: collect in-tolerance authors from the feed that this agent
     * neither follows nor blocks, and with probability P_f follow one of them chosen uniformly at
     * random, evicting a current followee first if the follow capacity is already reached.
     *
     * The follow graph is owned solely by AdminOptim; both queries and mutations go through it, and
     * mutations are applied inline here. Eviction iterates followees in ascending id order, which
     * fixes the tie-breaking.
     *
     * @param agents the agent array
     * @return {@code {newly followed id, evicted id}}, each -1 when no such action occurred
     */
    public int[] follow(Agent[] agents, AdminOptim admin) {
        // 1. Collect follow candidates.
        List<Integer> candidates = new ArrayList<>();
        for (Post post : this.feed) {
            // Skip authors this agent already follows or has blocked.
            if (Math.abs(post.getPostOpinion() - this.opinion) < this.bc
                    && !admin.follows(this.id, post.getPostUserId())
                    && !this.blockedUserList[post.getPostUserId()]) {
                candidates.add(post.getPostUserId());
            }
        }

        // No candidate, or the follow action does not fire this access.
        if (candidates.isEmpty() || randomGenerator.dynamics().nextDouble() >= p.followProb()) {
            return new int[]{-1, -1};
        }

        // 2. Choose the new followee uniformly at random.
        int newFollowId = candidates.get(randomGenerator.dynamics().nextInt(candidates.size()));

        // 3. Enforce the follow capacity, evicting one current followee if it is reached.
        int removeTargetId = -1; // -1 = nobody evicted

        if (admin.followeeCount(this.id) >= p.maxFollowCapacity()) {
            if (p.evictBeta() < 0) {
                // Default: deterministically evict the most opinion-distant followee (argmax,
                // ascending-id tie-break), as specified in the paper.
                double maxDiff = -1.0;
                for (int f : admin.sortedFollowees(this.id)) {
                    double diff = Math.abs(agents[f].getOpinion() - this.opinion);
                    if (diff > maxDiff) {
                        maxDiff = diff;
                        removeTargetId = f;
                    }
                }
            } else {
                // Optional stochastic variant: P(evict k) proportional to exp(beta*|o_k - o_i|),
                // with beta=0 giving uniform eviction.
                removeTargetId = softmaxEvict(agents, admin);
            }
            if (removeTargetId != -1) {
                admin.removeFollowEdge(this.id, removeTargetId);
            }
        }

        // 4. Commit the new follow edge.
        admin.addFollowEdge(this.id, newFollowId);

        // [0] = followed id, [1] = evicted id; consumed only by metrics/logging.
        return new int[]{newFollowId, removeTargetId};
    }

    // Pick a followee to evict with P(k) proportional to exp(beta*|o_k - o_i|); beta=0 is uniform.
    // Iterates followees in ascending id order so that a given random draw selects deterministically.
    private int softmaxEvict(Agent[] agents, AdminOptim admin) {
        double beta = p.evictBeta();
        List<Integer> fs = admin.sortedFollowees(this.id);
        double total = 0.0;
        double[] w = new double[fs.size()];
        for (int k = 0; k < fs.size(); k++) {
            w[k] = Math.exp(beta * Math.abs(agents[fs.get(k)].getOpinion() - this.opinion));
            total += w[k];
        }
        if (total <= 0.0) return -1;
        double r = randomGenerator.dynamics().nextDouble() * total;
        double cum = 0.0;
        for (int k = 0; k < w.length; k++) {
            cum += w[k];
            if (r <= cum) return fs.get(k);
        }
        return -1;
    }

    /**
     * Unfollow/block action for one access: with probability P_u, act on out-of-tolerance content.
     * The feed is scanned in order; the first out-of-tolerance post from a followed author causes
     * that author to be unfollowed and blocked. Failing that, one out-of-tolerance post's author
     * that is not followed is blocked at random. At most one tie is severed or blocked per access.
     *
     * @return the unfollowed author's id, or -1 if no follow edge was removed
     */
    public int unfollow(AdminOptim admin) {
        this.lastBlocks = 0;
        if (this.feed.isEmpty()) {
            return -1;
        }

        List<Integer> dislikeUser = new ArrayList<>();
        if (randomGenerator.dynamics().nextDouble() > p.pU()) {
            return -1;
        }
        for (Post post : this.feed) {
            if (Math.abs(post.getPostOpinion() - this.opinion) > this.bc && admin.follows(this.id, post.getPostUserId())) {
                block(post.getPostUserId());
                admin.removeFollowEdge(this.id, post.getPostUserId());
                return post.getPostUserId();
            }
            if (Math.abs(post.getPostOpinion() - this.opinion) > this.bc && !admin.follows(this.id, post.getPostUserId())) {
                dislikeUser.add(post.getPostUserId());
            }
        }
        if (dislikeUser.size() > 0) { // nobody to unfollow, so block an out-of-tolerance stranger
            block(dislikeUser.get(randomGenerator.dynamics().nextInt(dislikeUser.size())));
        }
        return -1;
    }

    // Mark id as blocked, counting only transitions into the blocked state.
    private void block(int id) {
        if (!this.blockedUserList[id]) {
            this.lastBlocks++;
        }
        this.blockedUserList[id] = true;
    }

    public Post makePost(int step) {

        Post post;
        post = new Post(this.id, this.opinion, step);

        this.toPost = 1;

        this.postProb -= Const.POST_COST;
        if (this.postProb < p.minPostProb()) {
            this.postProb = p.minPostProb();
        }

        return post;
    }

}
