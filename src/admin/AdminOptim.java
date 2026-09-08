package admin;

import agent.*;
import constants.Const;
import dynamics.StepStats;
import experiment.SimParams;
import java.util.*;
import rand.randomGenerator;

public class AdminOptim {
    // ---- [FOLLOW GRAPH] shared substrate: read by chronological delivery (via PostCash/follow
    // ties), by the For-You reach BFS below, and by Agent.follow()/unfollow() ----
    private int n;
    private final SimParams p; // instance-scoped runtime params
    private Set<Integer>[] followees; // adjacency list: users that userId follows
    private Set<Integer>[] followers; // reverse adjacency list: users that follow userId
    private int[] followerNumArray;

    // ---- [FOR-YOU] velocity-ranked recommender state (populated/used only when alpha > 0) ----
    private final Deque<Post> recentPosts = new ArrayDeque<>();  // candidate pool: recent original posts (see velocityScore)
    private List<Post> cachedRanked = null;                      // ranking cache, refreshed every velocityRankInterval() steps
    private int lastRankStep = Integer.MIN_VALUE;
    private Map<Integer, Integer>[] reachDistCache;              // [src].get(v) = hop distance src->v (see computeReachable)
    private int reachCachedStep = -1, reachCachedH = Integer.MIN_VALUE; // refreshed every REACH_REFRESH_INTERVAL steps
    // Post-lifespan diagnostics, taken as posts age out of the candidate window (pruneRecentPosts):
    // whether virality dies out naturally, or is still climbing when the window forcibly evicts it
    // (self-reinforcement risk).
    private long lifespanSum = 0;               // sum of (lastRepostStep - postedStep), reposted+evicted posts
    private int lifespanCount = 0;               // denominator for lifespanSum
    private int stillActiveAtEvictionCount = 0;  // of those, last repost was in the window's final 10%
    private int evictedRepostedCount = 0;        // denominator for stillActiveAtEvictionCount

    @SuppressWarnings("unchecked")
    public AdminOptim(int userNum, double[][] W, SimParams params) {
        this.n = userNum;
        this.p = params;
        this.followees = new HashSet[n];
        this.followers = new HashSet[n];
        this.followerNumArray = new int[n];
        for (int i = 0; i < n; i++) {
            followees[i] = new HashSet<>();
            followers[i] = new HashSet<>();
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (W[i][j] > 0.0) {
                    followees[i].add(j);
                    followers[j].add(i);
                    followerNumArray[j]++;
                }
            }
        }
    }

    // ---- [FOLLOW GRAPH] adjacency queries/mutations: AdminOptim is the sole owner of the follow
    // graph. Used by chronological delivery (PostCash population, below), by the For-You reach
    // BFS (computeReachable, below), and by Agent.follow()/unfollow() -- none of this is
    // feed-channel-specific itself. ----
    public double[][] getAdjacencyMatrix() {
        double[][] matrix = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j : followees[i]) {
                matrix[i][j] = 1.0;
            }
        }
        return matrix;
    }

    public Set<Integer> getFollowees(int userId) {
        return followees[userId];
    }

    public boolean follows(int follower, int followee) {
        return followees[follower].contains(followee);
    }

    public int followeeCount(int userId) {
        return followees[userId].size();
    }

    /** Followees of userId in ascending id order, giving deterministic iteration for eviction. */
    public List<Integer> sortedFollowees(int userId) {
        List<Integer> list = new ArrayList<>(followees[userId]);
        Collections.sort(list);
        return list;
    }

    public void addFollowEdge(int follower, int followee) {
        if (followees[follower].add(followee)) {
            followers[followee].add(follower);
            followerNumArray[followee]++;
        }
    }

    public void removeFollowEdge(int follower, int followee) {
        if (followees[follower].remove(followee)) {
            followers[followee].remove(follower);
            followerNumArray[followee]--;
        }
    }

    public Set<Integer> getFollowers(int userId) {
        return followers[userId];
    }

    public int[] getFollowerList() {
        return this.followerNumArray.clone();
    }

    public List<Map.Entry<Integer, Integer>> getFollowerRanking() {
        List<Map.Entry<Integer, Integer>> rankingList = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            rankingList.add(new AbstractMap.SimpleEntry<>(i, followerNumArray[i]));
        }

        rankingList.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        return rankingList;
    }

    public void setFollowerNumArray() {
        Arrays.fill(this.followerNumArray, 0);
        for (int i = 0; i < n; i++) {
            followerNumArray[i] = followers[i].size();
        }
    }

    public void updateAdjacencyMatrix(int userId, int[] followedIds, int unfollowedId) {
        if (followedIds[0] >= 0) {
            followees[userId].add(followedIds[0]);
            followers[followedIds[0]].add(userId);
            followerNumArray[followedIds[0]]++;
        }
        if (followedIds[1] >= 0) {
            followees[userId].remove(followedIds[1]);
            followers[followedIds[1]].remove(userId);
            followerNumArray[followedIds[1]]--;
        }
        if (unfollowedId >= 0) {
            followees[userId].remove(unfollowedId);
            followers[unfollowedId].remove(userId);
            followerNumArray[unfollowedId]--;
        }
    }

    // ================= [FOR-YOU] velocity-ranked recommender =================
    // Everything from here down through acceptProbability() builds and scores the For-You
    // candidate list; none of it runs the chronological stream, which is instead assembled
    // inline inside AdminFeedback() below (see that method's [CHRONO]/[FOR-YOU] sub-sections).

    /** Register a freshly-created original post into the For-You candidate window. */
    public void addRecentPost(Post post) {
        recentPosts.addLast(post);
    }

    /** Drop posts older than the velocity window from the candidate pool. */
    private void pruneRecentPosts(int step) {
        int cutoff = step - Const.VELOCITY_WINDOW;
        while (!recentPosts.isEmpty() && recentPosts.peekFirst().getPostedStep() < cutoff) {
            recordLifespan(recentPosts.removeFirst(), step);
        }
    }

    /** One per-post eviction event, for diagnostic logging; see drainLifespanEvents. */
    public record LifespanEvent(Post post, boolean stillActive) {}
    private final List<LifespanEvent> pendingLifespanEvents = new ArrayList<>();

    // Recorded once per post, at the moment it ages out of the candidate window. Only posts that
    // were reposted at least once are counted: a post that never took off has no lifespan to
    // measure here, and its existence is already visible in the volume metrics.
    private void recordLifespan(Post post, int evictionStep) {
        if (post.getReceivedReposts() <= 0) return;
        lifespanSum += (post.getLastRepostStep() - post.getPostedStep());
        lifespanCount++;
        evictedRepostedCount++;
        int quietSteps = evictionStep - post.getLastRepostStep();
        boolean stillActive = quietSteps <= Const.VELOCITY_WINDOW / 10;
        if (stillActive) stillActiveAtEvictionCount++;
        pendingLifespanEvents.add(new LifespanEvent(post, stillActive));
    }

    /**
     * Drain and clear the lifespan-eviction events recorded since the last call. OpinionDynamics
     * logs these to posts/post_lifespan.csv and polls once per step, alongside rankByVelocity, so
     * that no event is dropped.
     */
    public List<LifespanEvent> drainLifespanEvents() {
        if (pendingLifespanEvents.isEmpty()) return List.of();
        List<LifespanEvent> out = new ArrayList<>(pendingLifespanEvents);
        pendingLifespanEvents.clear();
        return out;
    }

    /** Mean (lastRepostStep - postedStep) over all evicted posts that received >=1 repost, cumulative to date. */
    public double getPostLifespanMean() {
        return lifespanCount > 0 ? lifespanSum / (double) lifespanCount : 0.0;
    }

    /**
     * Of evicted, reposted posts, the fraction still receiving reposts in the final 10% of
     * VELOCITY_WINDOW when forcibly evicted -- i.e. cut off mid-virality rather than having
     * organically gone quiet. High values here mean VELOCITY_WINDOW is truncating genuine ongoing
     * self-reinforcement, not just bounding candidate-pool memory.
     */
    public double getPostStillActiveAtEvictionFrac() {
        return evictedRepostedCount > 0 ? stillActiveAtEvictionCount / (double) evictedRepostedCount : 0.0;
    }

    // Per-post snapshot for windowed velocity: {repostsAtLastRank, stepAtLastRank}.
    private final Map<Integer, int[]> velSnapshot = new HashMap<>();

    // Cumulative velocity: reposts received per unit time. Opinion-blind, and deliberately NOT
    // normalised by the author's follower count, because engagement-ranked feeds do not divide by
    // poster reach: a small account's post surfaces when its raw engagement is large relative to
    // its peers, not because the ranking discounts large accounts. Dividing by (followers+1) was
    // found to suppress the neutral hub's own content in the algorithmic slots, the opposite of the
    // intended role. The accepted tradeoff is that the extreme hubs, being the largest accounts in
    // the network, are no longer throttled either.
    private double velocityScore(Post post, int step) {
        int age = step - post.getPostedStep();
        return post.getReceivedReposts() / (age + 1.0) * timeDecayFactor(age);
    }

    // Optional recency dampener (SimParams.feedTimeDecayTau); tau <= 0 disables it. This is
    // independent of the age normalisation already present in the velocity scores: dividing by age
    // makes the score a rate, which does not by itself demote an old but still active post, since a
    // sustained repost rate stays high indefinitely. Multiplying by exp(-age/tau) makes raw age
    // count against a post regardless of its current rate, giving a direct knob on the
    // self-reinforcement risk.
    private double timeDecayFactor(int age) {
        double tau = p.feedTimeDecayTau();
        return tau > 0.0 ? Math.exp(-age / tau) : 1.0;
    }

    // Windowed velocity: reposts gained since the previous ranking pass, per elapsed step. Unlike
    // the cumulative score, this demotes a stalled early burst below a currently rising post.
    // Snapshots are updated by the caller after scoring.
    private double windowedVelocityScore(Post post, int step) {
        int[] snap = velSnapshot.get(post.getPostId());
        int lastLikes = (snap == null) ? 0 : snap[0];
        int lastStep = (snap == null) ? post.getPostedStep() : snap[1];
        int gained = post.getReceivedReposts() - lastLikes;
        int dt = Math.max(1, step - lastStep);
        return gained / (double) dt * timeDecayFactor(step - post.getPostedStep());
    }

    // Ranking refresh cadence, deliberately separate from VELOCITY_WINDOW, which is the
    // candidate-pool lifetime. Refreshing once per window would give each post roughly one scoring
    // pass over its whole eligible lifetime, defeating windowed mode, and would freeze the
    // delivered For-You list for the entire window. It is instead derived from 1/accessProb, the
    // model's population-turnover timescale, so that the chronological channel's implicit
    // accumulation window and the For-You ranking cadence advance at the same tempo.
    private int velocityRankInterval() {
        double ap = p.accessProb();
        return ap > 0.0 ? Math.max(1, (int) Math.round(1.0 / ap)) : 1;
    }

    /**
     * Return recent posts sorted by descending velocity (mode per config). Rescored/resorted only
     * every {@link #velocityRankInterval()} steps; intervening calls return the cached ranking
     * unchanged, which is what gives windowedVelocityScore's dt a non-unit value.
     */
    public List<Post> rankByVelocity(int step) {
        if (cachedRanked != null && step - lastRankStep < velocityRankInterval()) {
            return cachedRanked;
        }
        pruneRecentPosts(step);
        List<Post> ranked = new ArrayList<>(recentPosts);
        boolean windowed = p.velMode().equals("windowed");
        // Precompute scores once (safe with the comparator; and windowed updates snapshots after).
        Map<Integer, Double> score = new HashMap<>();
        for (Post post : ranked) {
            score.put(post.getPostId(), windowed ? windowedVelocityScore(post, step)
                                                 : velocityScore(post, step));
        }
        if (windowed) {
            for (Post post : ranked) {
                velSnapshot.put(post.getPostId(), new int[]{post.getReceivedReposts(), step});
            }
            velSnapshot.keySet().retainAll(score.keySet()); // drop snapshots for expired posts
        }
        ranked.sort((a, b) -> Double.compare(score.get(b.getPostId()), score.get(a.getPostId())));
        cachedRanked = ranked;
        lastRankStep = step;
        return ranked;
    }

    // Reach is measured along OUT-edges only: an agent's For-You neighbourhood is who it
    // transitively follows, not an undirected blob that also includes followers-of-followers.
    // Under an undirected definition a hub's large in-degree would place every one of its own
    // followers' followers inside an unrelated agent's reach, merely because they all touch the hub.
    // h <= 0 means global reach, which also disables distance decay (see acceptProbability).
    private void ensureReachable(int step, int h, Set<Integer> excludeIds) {
        if (h <= 0) return; // global reach: no cache needed
        boolean stale = (reachDistCache == null) || (reachCachedH != h)
                || (step - reachCachedStep >= Const.REACH_REFRESH_INTERVAL);
        if (!stale) return;
        computeReachable(h, excludeIds);
        reachCachedStep = step;
        reachCachedH = h;
    }

    // excludeIds are skipped entirely during BFS expansion: they are never added to any agent's
    // reachable set, so their posts cannot surface in anyone else's For-You feed, and they are
    // never traversed through, so they cannot bridge two other agents' h-hop neighbourhoods. This
    // is exclusion from the network for hop-reachability purposes only; the follow graph itself is
    // untouched, and an excluded agent's own reachable set is still computed normally, so its feed
    // as a reader is unaffected.
    //
    // The BFS follows out-edges only (src follows u, u follows v, ...), capped at depth h. The
    // 1-based BFS layer is recorded per node rather than a flat boolean, feeding the distance decay
    // in acceptProbability.
    @SuppressWarnings("unchecked")
    private void computeReachable(int h, Set<Integer> excludeIds) {
        reachDistCache = new HashMap[n];
        for (int src = 0; src < n; src++) {
            Map<Integer, Integer> dist = new HashMap<>();
            Deque<Integer> frontier = new ArrayDeque<>();
            frontier.add(src);
            dist.put(src, 0);
            for (int depth = 0; depth < h && !frontier.isEmpty(); depth++) {
                int sz = frontier.size();
                for (int f = 0; f < sz; f++) {
                    int u = frontier.poll();
                    for (int v : followees[u]) {
                        if (!excludeIds.contains(v) && !dist.containsKey(v)) {
                            dist.put(v, depth + 1);
                            frontier.add(v);
                        }
                    }
                }
            }
            dist.remove(src);
            reachDistCache[src] = dist;
        }
    }

    /**
     * Probability that a For-You candidate at hop distance {@code d} from {@code userId} is
     * accepted. Global reach and unreachability within h hops are handled by the caller and the
     * cache. Modes (see ExperimentConfig.feedDecayMode): "hard" is a binary cutoff, and since
     * presence in reachDistCache already means d <= h it always returns 1.0 here; "exp" and
     * "inverse" apply distance decay, both degenerating to "hard" at lambda = 0.
     */
    private double acceptProbability(int userId, int authorId, int h) {
        if (h <= 0) return 1.0; // global: no spatial restriction, no decay
        if (reachDistCache == null) return 0.0;
        Integer d = reachDistCache[userId].get(authorId);
        if (d == null) return 0.0; // beyond the search-depth cap => treated as unreachable
        double lambda = p.feedDecayLambda();
        return switch (p.feedDecayMode()) {
            case "exp" -> Math.exp(-lambda * (d - 1));
            case "inverse" -> 1.0 / (1.0 + lambda * (d - 1));
            default -> 1.0; // "hard": presence within the depth-h cache already means d<=h
        };
    }

    /**
     * [BOTH] Build user {@code userId}'s feed for this step: floor(alpha*S) [FOR-YOU] slots
     * (velocity-ranked, opinion-blind, reachable within h hops, scored above) + remainder
     * [CHRONO] slots from follows, assembled inline below (there is no separate chrono-only
     * method -- the whole channel is these ~15 lines). {@code velocityRanked} is the per-step
     * global For-You ranking from rankByVelocity(), and is null when alpha = 0, in which case
     * this reduces to a purely chronological feed. {@code stepStats} accumulates
     * feed-composition-by-camp counts (chrono/For-You x repost/original, split by reader's
     * 5-bin opinionClass) for population agents.
     */
    public void AdminFeedback(int userId, Agent[] agentSet, int step, List<Post> velocityRanked, StepStats stepStats) {
        Agent user = agentSet[userId];
        boolean[] blocked = user.getBlockedUserList();

        int capacity = p.feedCapacity();
        int algoSlots = (int) Math.floor(p.feedAlgoShare() * capacity);
        int chronoSlots = capacity - algoSlots;

        // ---- [CHRONO] chronological-from-follows stream ----
        // A uniform sample of the postCash window; PostCash is a fixed-size FIFO, so recency is
        // already bounded there regardless of how it is sampled here. Candidates are deduplicated
        // by rootPostId: several followees relaying the same story each broadcast a distinct Post
        // object sharing one rootPostId, so without this a widely relayed story would occupy
        // several slots and receive multiplicity-weighted selection probability under the shuffle
        // below, leaking a popularity signal into the popularity-blind chronological channel.
        List<Post> chrono = new ArrayList<>();
        List<Post> chronoCandidates = new ArrayList<>();
        for (Post post : user.getPostCash().getAllPosts()) {
            if (!blocked[post.getPostUserId()]) chronoCandidates.add(post);
        }
        Collections.shuffle(chronoCandidates, randomGenerator.dynamics());
        Set<Integer> seenRoots = new HashSet<>();
        for (Post post : chronoCandidates) {
            if (chrono.size() >= chronoSlots) break;
            if (seenRoots.add(post.getRootPostId())) chrono.add(post);
        }

        // ---- [FOR-YOU] velocity-ranked stream (uses rankByVelocity's output; scoring/reach
        // logic itself lives in the [FOR-YOU] section above) ----
        List<Post> forYou = new ArrayList<>();
        if (algoSlots > 0 && velocityRanked != null && !velocityRanked.isEmpty()) {
            int h = p.feedHopH();
            ensureReachable(step, h, p.algoExcludeIds());
            // velocityRanked candidates are always originals (rootPostId == postId, see
            // AdminOptim.addRecentPost), so comparing against chrono's rootPostIds catches a
            // relayed copy of the same story occupying a chrono slot too.
            for (Post post : velocityRanked) {
                if (forYou.size() >= algoSlots) break;
                int author = post.getPostUserId();
                if (author == userId) continue;            // not your own post
                if (blocked[author]) continue;             // respect blocks (self-limiting filter)
                // Author excluded from algorithmic surfacing only; chronological reach to them,
                // if the reader follows them, is untouched.
                if (p.algoExcludeIds().contains(author)) continue;
                if (seenRoots.contains(post.getRootPostId())) continue; // avoid duplicate slot
                // Distance enters as an acceptance probability rather than a hard cutoff: the scan
                // stays in velocity order, so higher-velocity candidates are still preferred, but
                // more distant ones are less likely to fill a slot instead of being excluded
                // outright. The draw is skipped at p = 1.0 to avoid consuming random numbers in the
                // common case.
                double pAccept = acceptProbability(userId, author, h);
                if (pAccept <= 0.0) continue;
                if (pAccept < 1.0 && randomGenerator.dynamics().nextDouble() >= pAccept) continue;
                forYou.add(post);
            }
        }

        // [BOTH] feed-composition-by-camp bookkeeping, over population agents only (reads both the
        // chrono and forYou lists just built above). It must happen here,
        // before chrono and forYou are merged: channel provenance is not retained on Post or
        // Agent.feed once delivered, so this is the only point at which it is knowable. The camp is
        // the reader's 5-bin opinionClass, matching the other per-camp feed metrics.
        if (!user.getTarget()) {
            int cls = user.getOpinionClass();
            stepStats.feedClassAgentCount[cls]++;
            stepStats.feedForYouCountSum[cls] += forYou.size();
            stepStats.feedChronoCountSum[cls] += chrono.size();
            int repostCount = 0, originalCount = 0;
            // Author-hub-tier tally. Under the default attribution, postUserId stays the original
            // creator, so this counts "authored by" rather than "relayed by".
            int neutCount = 0, hubCount = 0;
            for (Post post : chrono) {
                if (post.isRelay()) repostCount++; else originalCount++;
                Agent author = agentSet[post.getPostUserId()];
                int authorTier = author.getHubTier();
                if (authorTier >= 0) { hubCount++; if (authorTier == 2) neutCount++; }
                stepStats.feedAuthorClassCountSum[cls][author.getOpinionClass()]++;
            }
            for (Post post : forYou) {
                if (post.isRelay()) repostCount++; else originalCount++;
                Agent author = agentSet[post.getPostUserId()];
                int authorTier = author.getHubTier();
                if (authorTier >= 0) { hubCount++; if (authorTier == 2) neutCount++; }
                stepStats.feedAuthorClassCountSum[cls][author.getOpinionClass()]++;
            }
            stepStats.feedRepostCountSum[cls] += repostCount;
            stepStats.feedOriginalCountSum[cls] += originalCount;
            stepStats.feedNeutCountSum[cls] += neutCount;
            stepStats.feedHubCountSum[cls] += hubCount;
        }

        // [FOR-YOU] record which rootPostIds the For-You stream delivered this step, so that a repost() call
        // later in the same step can tag the relay's channel provenance. chrono and forYou are
        // mutually exclusive by rootPostId, so membership is unambiguous.
        Set<Integer> forYouRoots = new HashSet<>();
        for (Post post : forYou) forYouRoots.add(post.getRootPostId());
        user.setForYouRootIds(forYouRoots);

        // ---- [BOTH] deliver: chronological + For-You merged, order-neutral for reading ----
        List<Post> feed = new ArrayList<>(chrono);
        feed.addAll(forYou);
        Collections.shuffle(feed, randomGenerator.dynamics());
        for (Post post : feed) {
            user.addPostToFeed(post); // re-checks blockedUserList
        }
    }

    // ---- [FOLLOW GRAPH] not part of either feed channel ----
    public List<Integer> getTopInfluencers(int topK) {
        List<Map.Entry<Integer, Integer>> rankingList = getFollowerRanking();
        List<Integer> topInfluencers = new ArrayList<>();

        for (int i = 0; i < Math.min(topK, rankingList.size()); i++) {
            topInfluencers.add(rankingList.get(i).getKey());
        }

        return topInfluencers;
    }

}
