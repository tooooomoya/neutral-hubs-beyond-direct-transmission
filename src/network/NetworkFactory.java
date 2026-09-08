package network;

import constants.Const;
import experiment.ExperimentConfig;

/**
 * Resolves the initial network from the run configuration.
 *
 * The directed Holme-Kim generator ("hk") is the default and the calibration baseline; its
 * parameters are exposed as the CLI keys hk_m, hk_a and hk_pt. DC-SBM likewise exposes sbm_k,
 * sbm_pin, sbm_pout, sbm_gamma and sbm_deg. The remaining generators keep the hardcoded parameters
 * documented at their call sites below, and would need CLI keys added before being varied.
 */
public final class NetworkFactory {

    private NetworkFactory() {}

    public static Network create(ExperimentConfig cfg, int n) {
        switch (cfg.networkType) {
            case "dcsbm":
                // targetDeg=50, rather than the class docstring's 10, places the rank-50 and
                // rank-100 agents in the intended mid-tier follower band.
                return new DCSBMNetwork(n, cfg.sbmK, cfg.sbmPin, cfg.sbmPout, cfg.sbmGamma, cfg.sbmDeg);
            case "ba":
                return new BarabasiAlbertNetwork(n, 3);
            case "hk":
                // m=15, A=4, pt=0.05, calibrated against Kwak et al. (2010); see
                // ExperimentConfig's Holme-Kim comment.
                return new HolmeKimNetwork(n, cfg.hkM, cfg.hkA, cfg.hkPt);
            case "ws":
                return new WattsStrogatzNetwork(n, 4, 0.1);
            case "dms":
                return new DMSNetwork(n, 3, 2);
            case "cnn":
                return new ConnectingNearestNeighborNetwork(n, 0.3);
            case "lfr":
                return new LFRNetwork(n, 10, 50, 0.2, 2.5, 1.5, 10, 50);
            case "random":
                return new RandomNetwork(n, Const.CONNECTION_PROB_OF_RANDOM_NW);
            case "read":
                return new ReadNetwork(n, cfg.readPath);
            default:
                throw new IllegalArgumentException("Unknown network type: '" + cfg.networkType
                        + "' (expected dcsbm|ba|hk|ws|dms|cnn|lfr|random|read)");
        }
    }
}
