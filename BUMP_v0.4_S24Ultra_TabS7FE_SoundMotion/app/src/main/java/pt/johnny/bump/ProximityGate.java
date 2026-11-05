package pt.johnny.bump;

import java.util.ArrayDeque;

/**
 * BLE RSSI is noisy and cannot establish physical contact or a real distance.
 * A short rolling window tolerates occasional weak packets without making
 * a single RSSI spike initiate a transfer.
 */
final class ProximityGate {
    private static final long WINDOW_MS = 4500L;
    private static final int MAX_SAMPLES = 6;
    private final int threshold;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private boolean fired;
    private static final class Sample {
        final int rssi;
        final long timestamp;
        Sample(int rssi, long timestamp) { this.rssi = rssi; this.timestamp = timestamp; }
    }
    ProximityGate(int threshold) { this.threshold = threshold; }
    boolean sample(int rssi, long whenMs) {
        if (fired) return false;
        // Discard malformed readings and samples outside the recent window.
        if (rssi > 0 || rssi < -127) return false;
        while (!samples.isEmpty() &&
                (whenMs - samples.peekFirst().timestamp > WINDOW_MS ||
                 whenMs < samples.peekLast().timestamp)) {
            if (whenMs < samples.peekLast().timestamp) samples.clear();
            else samples.removeFirst();
        }
        samples.addLast(new Sample(rssi, whenMs));
        while (samples.size() > MAX_SAMPLES) samples.removeFirst();
        int positive = 0;
        for (Sample s : samples) if (s.rssi >= threshold) positive++;
        // Three strong packets inside a rolling window: tolerates RF dropouts.
        if (positive >= 3) { fired = true; return true; }
        return false;
    }
}
