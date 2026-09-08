package experiment;

/**
 * Immutable, instance-scoped runtime parameters for one run. Built once from an
 * {@link ExperimentConfig} and passed by reference into Agent, AdminOptim and OpinionDynamics, so
 * that no runtime parameter is held in mutable global state. Values that are not runtime-tunable
 * stay {@code final} on {@code constants.Const}.
 */
public record SimParams(
        int n,                  // agent count
        double feedAlgoShare,   // alpha: For-You share of the feed
        int feedHopH,           // h: For-You reach in hops (<=0 = global), also the BFS depth cap
        String feedDecayMode,   // hard|exp|inverse -- see ExperimentConfig.feedDecayMode
        double feedDecayLambda, // decay steepness; 0 = no decay in any mode
        double feedTimeDecayTau, // optional extra exp(-age/tau) dampener on velocity score; 0=off
        double bcRecoveryRate,  // gamma_bc: relaxation rate when the bc target is above current bc
        double pU,              // P_u: unfollow/block probability per access
        double maxFollowCapacity,
        double outOfBCRepostProb,          // P_r^out: out-of-tolerance repost probability
        double bcDecRate,       // delta_bc: relaxation rate when the bc target is below current bc
        double bcFloor,         // dynamic-bc clamp floor
        double bcCeiling,       // dynamic-bc clamp ceiling
        double bcInit,          // bc's initial value, and the target bcRecoveryRate reverts toward
        double vocalComfortRadius, // population-wide comfort window driving postProb
        boolean initOpinionClip,// true = clip the initial Gaussian; false = truncate by resampling
        String initOpinionDist, // gaussian|empirical|uniform
        int assertInterval,     // invariant-check cadence in steps
        double repostProb,      // P_r: in-tolerance repost probability
        String stubDist,        // const|uniform
        double stubMin,
        double stubMax,
        double evictBeta,       // <0 = evict the most distant followee; >=0 = softmax temperature
        String velMode,         // cumulative|windowed
        int feedCapacity,       // S: max posts delivered to a feed per access
        double followProb,      // P_f: per-access probability the follow action executes
        double initialPostProb,
        double maxPostProb,
        double minPostProb,
        double postProbRelaxRate, // eta: postProb's relaxation rate toward its comfort-rate target
        double neutralBandHalfWidth, // |opinion| below this excluded from sign-based camp metrics
        double accessProb,      // P_a: per-step probability an agent's whole action set, including
                                 // the opinion update, executes; the model's global timescale knob
        java.util.Set<Integer> algoExcludeIds, // excluded from the For-You candidate pool only;
                                 // chronological reach to these agents is untouched
        String resultFolder) {

    public static SimParams from(ExperimentConfig c) {
        return new SimParams(
                c.n,
                c.feedAlgoShare,
                c.feedHopH,
                c.feedDecayMode,
                c.feedDecayLambda,
                c.feedTimeDecayTau,
                c.bcRecoveryRate,
                c.pU,
                c.maxFollowCapacity,
                c.outOfBCRepostProb,
                c.bcDecRate,
                c.bcFloor,
                c.bcCeiling,
                c.bcInit,
                c.vocalComfortRadius,
                c.initOpinionClip,
                c.initOpinionDist,
                Math.max(1, c.assertInterval),
                c.repostProb,
                c.stubDist,
                c.stubMin,
                c.stubMax,
                c.evictBeta,
                c.velMode,
                c.feedCapacity,
                c.followProb,
                c.initialPostProb,
                c.maxPostProb,
                c.minPostProb,
                c.postProbRelaxRate,
                c.neutralBandHalfWidth,
                c.accessProb,
                c.algoExcludeIds,
                c.resultFolder());
    }
}
