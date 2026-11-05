package pt.johnny.bump;

/** Run with bash tests/run.sh without Android SDK. */
public final class ProximityGateTest {
    private static int count;
    private static void check(boolean condition, String reason) {
        count++;
        if (!condition) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        ProximityGate gate = new ProximityGate(-68);
        check(!gate.sample(-77, 1000), "weak packet");
        check(!gate.sample(-66, 1600), "one good packet");
        check(!gate.sample(-80, 2000), "RF dropout tolerated");
        check(!gate.sample(-67, 2500), "two good packets");
        check(gate.sample(-65, 3200), "three good packets even with dropout");
        check(!gate.sample(-48, 3400), "one shot only");

        gate = new ProximityGate(-68);
        check(!gate.sample(-61, 1000), "first good");
        check(!gate.sample(-62, 7000), "window expires");
        check(!gate.sample(-62, 7300), "only two recent good");
        check(gate.sample(-62, 7600), "third recent good");

        gate = new ProximityGate(-68);
        check(!gate.sample(-90, 1000), "distant");
        check(!gate.sample(-87, 1100), "distant 2");
        check(!gate.sample(-82, 1200), "distant 3");
        check(!gate.sample(-70, 1300), "below threshold");
        check(!gate.sample(-67, 1400), "only one good");
        check(!gate.sample(-66, 1500), "only two good");
        check(gate.sample(-66, 1600), "third good after noise");
        gate = new ProximityGate(-68);
        check(!gate.sample(-65, 1000), "good packet");
        check(!gate.sample(-65, 900), "time anomaly clears old packets");
        check(!gate.sample(-65, 2000), "second good after anomaly");
        check(gate.sample(-65, 2400), "third valid packet after anomaly");
        System.out.println("PASS ProximityGate: " + count + " checks");
    }
}
