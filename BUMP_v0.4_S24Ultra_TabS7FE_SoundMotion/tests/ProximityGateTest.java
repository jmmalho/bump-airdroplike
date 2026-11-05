package pt.johnny.bump;

/** Run with bash tests/run.sh without Android SDK. */
public final class ProximityGateTest {
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    public static void main(String[] args) {
        ProximityGate gate = new ProximityGate(-58);
        check(!gate.sample(-72, 1000), "far signal must not trigger");
        check(!gate.sample(-57, 1800), "first strong sample");
        check(!gate.sample(-56, 2400), "second strong sample");
        check(gate.sample(-55, 2800), "third strong sample triggers once");
        check(!gate.sample(-54, 3100), "trigger cannot be repeated");
        gate = new ProximityGate(-58);
        gate.sample(-50, 1000);
        gate.sample(-78, 1200);
        check(!gate.sample(-50, 1400), "weak signal resets series");
        check(!gate.sample(-50, 1800), "only two strong samples");
        gate = new ProximityGate(-58);
        gate.sample(-53, 1000);
        gate.sample(-53, 8000);
        check(!gate.sample(-53, 8200), "time window resets after 5.5s");
        check(gate.sample(-53, 8400), "third in renewed window triggers");
        System.out.println("PASS ProximityGate: 8 checks");
    }
}
