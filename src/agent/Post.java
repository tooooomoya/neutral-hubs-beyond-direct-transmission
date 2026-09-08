package agent;

public class Post {
    private int postUserId;      // attributed author (relayer if rel_attrib=reposter)
    private double postOpinion;
    private int postedStep;
    private int receivedReposts;
    // step of the most recent repost, for lifespan/self-reinforcement diagnostics
    // (AdminOptim.pruneRecentPosts) -- distinct from postedStep, which never changes after creation.
    private int lastRepostStep;
    private final int postId;
    private static int postIdCounter = 0;

    // relay lineage (originals: relayerId=-1, depth=0, parentPostId=-1, rootPostId=postId).
    private final int rootPostId;
    private final int parentPostId;
    private final int relayerId;
    private final int depth;
    // Edge-type tag: whether the relayer's bounded-confidence window excluded this content at the
    // moment they relayed it. false for original posts (not a relay). Drives the homophily/hostile
    // repost-edge split in the repost network log -- a mechanism-level classification (which gate,
    // repostProb vs outOfBCRepostProb, fired), not a measured affect/sentiment quantity.
    private final boolean outOfBcRelay;
    // Which feed channel delivered the post this relay event acted on: true if the reposter's feed
    // slot for this rootPostId came from the algorithmic For-You stream, false if it came from the
    // chronological-from-follows stream. false for original posts. Retained so that cross-camp
    // cascades can be attributed to a delivery channel (see AdminOptim.AdminFeedback).
    private final boolean viaAlgo;

    /** Original post. */
    public Post(int postUserId, double postOpinion, int postedStep){
        this.postUserId = postUserId;
        this.postOpinion = postOpinion;
        this.postedStep = postedStep;
        this.receivedReposts = 0;
        this.lastRepostStep = postedStep;
        this.postId = postIdCounter++;
        this.rootPostId = this.postId;
        this.parentPostId = -1;
        this.relayerId = -1;
        this.depth = 0;
        this.outOfBcRelay = false;
        this.viaAlgo = false;
    }

    /** Relay/repost of {@code parent} by {@code relayerId}, attributed to {@code attribUserId}. */
    private Post(Post parent, int relayerId, int attribUserId, double conveyedOpinion, boolean outOfBcRelay, boolean viaAlgo) {
        this.postUserId = attribUserId;
        this.postOpinion = conveyedOpinion;
        this.postedStep = parent.postedStep;
        this.receivedReposts = 0;
        this.lastRepostStep = parent.postedStep;
        this.postId = postIdCounter++;
        this.rootPostId = parent.rootPostId;
        this.parentPostId = parent.postId;
        this.relayerId = relayerId;
        this.depth = parent.depth + 1;
        this.outOfBcRelay = outOfBcRelay;
        this.viaAlgo = viaAlgo;
    }

    public static Post relayOf(Post parent, int relayerId, int attribUserId, double conveyedOpinion, boolean outOfBcRelay, boolean viaAlgo) {
        return new Post(parent, relayerId, attribUserId, conveyedOpinion, outOfBcRelay, viaAlgo);
    }

    // Getter
    public int getPostUserId() {
        return postUserId;
    }

    public double getPostOpinion() {
        return postOpinion;
    }

    public int getPostedStep() {
        return postedStep;
    }

    public int getReceivedReposts(){
        return this.receivedReposts;
    }

    public int getPostId(){
        return this.postId;
    }

    public int getLastRepostStep(){ return this.lastRepostStep; }
    public int getRootPostId(){ return this.rootPostId; }
    public int getParentPostId(){ return this.parentPostId; }
    public int getRelayerId(){ return this.relayerId; }
    public int getDepth(){ return this.depth; }
    public boolean isRelay(){ return this.relayerId >= 0; }
    public boolean isOutOfBcRelay(){ return this.outOfBcRelay; }
    public boolean isViaAlgo(){ return this.viaAlgo; }

    // Setter
    public void setPostUserId(int postUserId) {
        this.postUserId = postUserId;
    }

    public void setPostOpinion(double postOpinion) {
        this.postOpinion = postOpinion;
    }

    public void setPostedStep(int postedStep) {
        this.postedStep = postedStep;
    }

    // other
    public Post copyPost(){
        Post copiedPost = new Post(this.postUserId, this.postOpinion, this.postedStep);
        return copiedPost;
    }

    public void receiveRepost(int step){
        this.receivedReposts++;
        this.lastRepostStep = step;
    }
}
