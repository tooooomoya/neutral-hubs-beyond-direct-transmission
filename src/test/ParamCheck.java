package test;

import constants.Const;
import experiment.ExperimentConfig;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints every {@link ExperimentConfig} default next to the value stated in
 * the paper, one row per parameter, so a value mismatch between the code
 * and the paper is visible at a glance rather than buried in a sequence of {@code check(x == y)}
 * assertions. Run with
 *   java -cp bin test.ParamCheck
 * Exits non-zero if any row whose paper value is a single number does not match the code default.
 * Rows without a single paper number (an experimental factor swept over several levels, or a
 * quantity not held as a config field) are informational and never fail the run.
 */
public class ParamCheck {

    private enum Status { OK, MISMATCH, INFO }

    private record Row(String symbol, String field, String paperValue, String source, Status status,
                        String codeValue) {}

    private static final List<Row> ROWS = new ArrayList<>();

    // Compares a code default against a single paper number within a small relative tolerance,
    // since the paper prints values rounded to 2-3 significant figures (e.g. 0.25537 in code is
    // "0.26" in the paper).
    private static void num(String symbol, String field, double codeVal, double paperVal, String source) {
        numTol(symbol, field, codeVal, paperVal, Math.max(1e-9, Math.abs(paperVal) * 0.03), source);
    }

    // Same, with an explicit absolute tolerance for a value whose paper rounding is otherwise
    // outside the default relative tolerance.
    private static void numTol(String symbol, String field, double codeVal, double paperVal, double tol, String source) {
        boolean ok = Math.abs(codeVal - paperVal) <= tol;
        ROWS.add(new Row(symbol, field, fmt(paperVal), source, ok ? Status.OK : Status.MISMATCH, fmt(codeVal)));
    }

    private static void str(String symbol, String field, String codeVal, String paperVal, String source) {
        boolean ok = codeVal.equals(paperVal);
        ROWS.add(new Row(symbol, field, paperVal, source, ok ? Status.OK : Status.MISMATCH, codeVal));
    }

    // A row with no single paper number to check against (a swept factor, or a quantity not held
    // as one ExperimentConfig field). Always shown, never fails the run.
    private static void info(String symbol, String field, String codeVal, String note, String source) {
        ROWS.add(new Row(symbol, field, note, source, Status.INFO, codeVal));
    }

    private static String fmt(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    private static Object get(ExperimentConfig c, String field) {
        try {
            Field f = ExperimentConfig.class.getField(field);
            return f.get(c);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("ParamCheck references a field that no longer exists: " + field, e);
        }
    }

    private static double d(ExperimentConfig c, String field) { return (Double) get(c, field); }
    private static int i(ExperimentConfig c, String field) { return (Integer) get(c, field); }

    public static void main(String[] args) {
        ExperimentConfig c = ExperimentConfig.fromArgs(new String[]{});

        // ---- Table 1: agent state variables (initial value / range) ----
        num("O_i(0) std", "Const.INITIAL_OPINION_STD", Const.INITIAL_OPINION_STD, 0.6, "Table 1");
        numTol("p_i(0)", "initialPostProb", d(c, "initialPostProb"), 0.26, 0.006, "Table 1");
        num("p_min", "minPostProb", d(c, "minPostProb"), 0.01, "Table 1 range");
        num("p_max", "maxPostProb", d(c, "maxPostProb"), 1.0, "Table 1 range");
        num("BC(0)", "bcInit", d(c, "bcInit"), 0.8, "Table 1");
        num("BC_floor", "bcFloor", d(c, "bcFloor"), 0.2, "Table 1 range");
        num("BC_ceil", "bcCeiling", d(c, "bcCeiling"), 1.0, "Table 1 range");

        // ---- Table 2: model parameters ----
        num("P_a", "accessProb", d(c, "accessProb"), 0.1, "Table 2");
        num("P_f", "followProb", d(c, "followProb"), 0.01, "Table 2");
        num("P_r", "repostProb", d(c, "repostProb"), 0.1, "Table 2");
        num("P_r^out", "outOfBCRepostProb", d(c, "outOfBCRepostProb"), 0.01, "Table 2");
        info("P_u", "pU", fmt(d(c, "pU")), "swept: 0.05, 0.08, 0.11, 0.15 (0.05 is the low level)", "Experimental Design");
        num("maxfollower", "maxFollowCapacity", d(c, "maxFollowCapacity"), 43.9, "Table 2");
        num("maxfeed (S)", "feedCapacity", i(c, "feedCapacity"), 15, "Table 2");
        num("delta_BC", "bcDecRate", d(c, "bcDecRate"), 0.5, "Table 2");
        num("gamma_BC", "bcRecoveryRate", d(c, "bcRecoveryRate"), 0.0001, "Table 2");
        info("VCR", "vocalComfortRadius", fmt(d(c, "vocalComfortRadius")), "swept: 0.10, 0.13, 0.17, 0.20 (0.10 is the low level)", "Experimental Design");
        num("eta", "postProbRelaxRate", d(c, "postProbRelaxRate"), 0.1, "Table 2");
        num("lambda (stubbornness)", "stubMin", d(c, "stubMin"), 0.82, "Table 2");
        if (d(c, "stubMin") != d(c, "stubMax")) {
            ROWS.add(new Row("", "stubMax", "= stubMin (homogeneous population)", "Table 2",
                    Status.MISMATCH, fmt(d(c, "stubMax"))));
        }

        // ---- Network generation (Sec. Experimental Design) ----
        str("network", "networkType", c.networkType, "hk", "Experimental Design");
        num("m", "hkM", i(c, "hkM"), 15, "Experimental Design");
        num("A_0", "hkA", i(c, "hkA"), 4, "Experimental Design");
        num("p_t", "hkPt", d(c, "hkPt"), 0.05, "Experimental Design");

        // ---- Scale & duration ----
        num("N", "n", i(c, "n"), 1000, "Experimental Design");
        num("T (steps)", "steps", i(c, "steps"), 40000, "Experimental Design");

        // ---- Camp thresholds (derived, not a single field) ----
        info("neutral/moderate boundary", "neutralBandHalfWidth", fmt(d(c, "neutralBandHalfWidth")),
                "0.2, matches the paper's neutral-camp boundary |O|<0.2", "Agent-Based Model");
        info("moderate/extreme boundary", "(derived, see note)", "0.6",
                "derived from 5 equal-width bins over [-1,1]; not a field of its own", "Agent-Based Model");

        // ---- Hub-pool design (external ID list, not a single default field) ----
        info("N_hub (total)", "(pinOpinionIds, supplied per run)", "n/a",
                "60: 10 each at +-1.0, 10 each at +-0.4, 20 at 0.0; via pin_opinion_ids, not a default",
                "Neutral Hub Intervention");

        // ---- Feed / For-You (off by default; not part of any reported run) ----
        num("alpha", "feedAlgoShare", d(c, "feedAlgoShare"), 0.0, "off-paper mechanism, default off");

        printTable();
    }

    private static void printTable() {
        System.out.println("== Parameter check: ExperimentConfig defaults vs. the paper ==");
        int wSym = 24, wField = 34, wCode = 14, wPaper = 20;
        String header = pad("Symbol", wSym) + pad("Config field", wField) + pad("Code default", wCode)
                + pad("Paper value", wPaper) + "Source / note";
        System.out.println(header);
        System.out.println("-".repeat(120));
        int mismatches = 0;
        for (Row r : ROWS) {
            String tag = switch (r.status()) {
                case OK -> "  OK  ";
                case MISMATCH -> " MISM ";
                case INFO -> " info ";
            };
            if (r.status() == Status.MISMATCH) mismatches++;
            // Never truncate: a long note is allowed to break column alignment for its own row
            // rather than silently drop information.
            System.out.println(tag + pad(r.symbol(), wSym) + pad(r.field(), wField)
                    + pad(r.codeValue(), wCode) + pad(r.paperValue(), wPaper) + r.source());
        }
        System.out.println("-".repeat(120));
        System.out.println(mismatches == 0 ? "ALL MATCH" : (mismatches + " MISMATCH(ES)"));
        if (mismatches > 0) System.exit(1);
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s + " ";
        return s + " ".repeat(width - s.length());
    }
}
