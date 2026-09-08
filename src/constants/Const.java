package constants;

/**
 * Fixed structural constants of the model.
 *
 * Invariant: every field here is NOT runtime-tunable and has no corresponding
 * {@link experiment.ExperimentConfig} field or CLI key. A value that becomes tunable moves to
 * {@code ExperimentConfig} in full (field default + CLI case + {@code tag()} entry) and is
 * deleted from here, so that no parameter is defined in two places.
 */
public class Const {
    // ---- Velocity-primary "For You" recommender ----
    // The For-You share (alpha) and hop reach (h) are per-run knobs, held in ExperimentConfig/SimParams.
    public static final int VELOCITY_WINDOW = 100;         // W: posts older than W steps leave the For-You candidate pool
    public static final int REACH_REFRESH_INTERVAL = 100;  // recompute cached h-hop reachable sets every R steps

    // network parameter
    public static final double CONNECTION_PROB_OF_RANDOM_NW = 0.01;

    // ---- Agent parameters ----
    // The vocal comfort radius, the bounded-confidence floor/ceiling, the stubbornness
    // distribution, and the per-step access probability P_a are all runtime knobs and therefore
    // live in ExperimentConfig/SimParams rather than here.
    public static final double VOCAL_COMFORT_RADIUS = 0.2;
    public static final double POST_COST = 0.0;
    // Comfort-rate threshold above which an agent is counted as "high comfort" in the binned
    // diagnostics (Analysis.computeHighComfortRateNumArray). Diagnostic only: no dynamics read it.
    public static final double OPINION_PREVALENCE = 0.5;
    // Standard deviation of the Gaussian from which intrinsic opinions O_i(0) are drawn.
    public static final double INITIAL_OPINION_STD = 0.6;
    public static final double INITIAL_STUBBORNNESS = 0.7;

    // ---- Result-data binning ----
    // CSV column names/order come from MetricsRow insertion order in
    // OpinionDynamics.buildMetricsRow(); only the bin counts are fixed here.
    public static final int NUM_OF_BINS_OF_POSTS = 5; // % of bins of opinions in posts for analysis
    public static final int NUM_OF_BINS_OF_OPINION = NUM_OF_BINS_OF_POSTS;
    public static final int NUM_OF_BINS_OF_OPINION_FOR_WRITER = NUM_OF_BINS_OF_OPINION;

    // ---- Empirical 7-bin calibration/init target ----
    public static final int NUM_OF_BINS_EMPIRICAL_7 = 7;
    public static final int[] EMPIRICAL_OPINION_BIN_COUNTS = {160, 131, 85, 208, 46, 71, 64};
    public static final double[] EMPIRICAL_POST_BIN_WEIGHTS =
            {4328.725, 2682.863, 1503.682, 2135.798, 691.065, 1156.761, 3742.547};
}
