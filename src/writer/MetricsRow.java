package writer;

import java.util.Collection;
import java.util.LinkedHashMap;

/**
 * A single per-step metrics row.
 *
 * Adding a metric is one {@code row.put("name", value)}; binned metrics loop over
 * {@code row.put("cRateMean_" + i, v)}. Insertion order defines CSV column order. Integer-valued
 * metrics keep integer formatting and everything else is written by {@link Writer} as a
 * fixed-precision double. A key set that differs between rows is a hard error in
 * {@link Writer#write()}, so a mistyped key fails immediately rather than producing empty cells.
 */
public class MetricsRow {

    private final LinkedHashMap<String, Number> map = new LinkedHashMap<>();

    /** Insert or overwrite a metric; returns this for chaining. */
    public MetricsRow put(String key, Number value) {
        map.put(key, value);
        return this;
    }

    public Collection<String> keys() {
        return map.keySet();
    }

    public Collection<Number> values() {
        return map.values();
    }

    /** Comma-joined key list (no leading "step"). */
    public String headerCsv() {
        return String.join(",", map.keySet());
    }
}
