package pt.johnny.bump;

/** Pure-Java RSSI heuristic: fast repeated samples; no physical distance guarantee. */
final class ProximityGate {
    private final int threshold;
    private int consecutive;
    private long startMs;
    private boolean fired;
    ProximityGate(int threshold) { this.threshold = threshold; }
    boolean sample(int rssi, long whenMs) {
        if (fired) return false;
        if (rssi >= threshold) {
            if (consecutive == 0 || whenMs - startMs > 5500) {
                startMs = whenMs;
                consecutive = 0;
            }
            consecutive++;
            if (consecutive >= 3) { fired = true; return true; }
        } else if (rssi < threshold - 6) {
            consecutive = 0;
        }
        return false;
    }
}
