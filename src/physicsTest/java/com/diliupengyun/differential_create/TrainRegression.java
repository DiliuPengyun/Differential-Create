package com.diliupengyun.differential_create;

import java.util.List;
import com.diliupengyun.differential_create.trains.TrainMechanics;
import com.diliupengyun.differential_create.kinetics.RatioScale;

/** Force, energy and gearbox tests against physical expectations, independent of the game. */
public final class TrainRegression {
    private static int checks;
    public static void run() {
        for (int octave = -4; octave <= 4; octave++) {
            int raw = 1 << (octave + 4);
            check(RatioScale.raw(40 + octave * 10) == raw, "octave marking");
            check(RatioScale.column(raw) == 40 + octave * 10, "inverse octave marking");
        }
        int previous = 0;
        for (int c = 0; c <= RatioScale.MAX; c++) { int raw = RatioScale.raw(c); check(raw >= previous && raw >= 1 && raw <= 256, "monotonic valid dial"); previous = raw; }
        for (int raw = 1; raw <= 256; raw++) check(RatioScale.column(raw) >= 0 && RatioScale.column(raw) <= RatioScale.MAX, "legacy value has a cursor");
        var coast = TrainMechanics.advance(100, 10_000, 1, 0, 0, List.of(), .05);
        check(coast.speed() > 90 && coast.speed() < 100, "above original speed caps, coasting remains continuous");
        var park = TrainMechanics.advance(0, 10_000, 2, .7071, 1, List.of(), 1);
        near(0, park.speed(), 0, "parking on steep supported track");
        var slope = TrainMechanics.advance(0, 10_000, 0, -.1, 0, List.of(), 1);
        check(slope.speed() > .9, "gravity accelerates an unbraked sourceless train");
        var brake = TrainMechanics.advance(1, 10_000, 0, 0, 1, List.of(), 1);
        near(0, brake.speed(), 0, "braking cannot reverse velocity");
        var low = engine(.5); var high = engine(2);
        double lowSpeed = TrainMechanics.advance(0, 25_000, 0, 0, 0, List.of(low), 1).speed();
        double highSpeed = TrainMechanics.advance(0, 25_000, 0, 0, 0, List.of(high), 1).speed();
        check(lowSpeed > highSpeed * 2, "low gear multiplies finite starting force");
        var light = engine(1); var heavy = engine(1);
        double lightSpeed = TrainMechanics.advance(0, 10_000, 0, 0, 0, List.of(light), 1).speed();
        double heavySpeed = TrainMechanics.advance(0, 100_000, 0, 0, 0, List.of(heavy), 1).speed();
        check(lightSpeed > heavySpeed * 5 && heavySpeed > 0, "heavy train accelerates more slowly, without arbitrary load cap");
        var neutral = engine(1); neutral.omega = 12; neutral.shift(0);
        var neutralMotion = TrainMechanics.advance(3, 10_000, 0, 0, 0, List.of(neutral), .1);
        near(3 - TrainMechanics.ROLLING_ACCELERATION * .1, neutralMotion.speed(), 1e-9, "neutral engine cannot propel train");
        var change = engine(1); change.stallTorque = change.power = 0; change.omega = 30;
        double before = energy(25_000, 10, change); change.shift(2);
        near(before, energy(25_000, 10, change), 0, "shift command itself adds no energy");
        var shifted = TrainMechanics.advance(10, 25_000, 0, 0, 0, List.of(change), .05);
        near(10 - TrainMechanics.ROLLING_ACCELERATION * .05, shifted.speed(), 1e-8, "initial shift interval is disconnected");
        shifted = TrainMechanics.advance(shifted.speed(), 25_000, 0, 0, 0, List.of(change), .6);
        check(energy(25_000, shifted.speed(), change) <= before && change.clutchHeat > 0, "re-engagement dissipates mismatch energy");
        var backdrive = engine(1); backdrive.omega = 100;
        var fast = TrainMechanics.advance(50, 25_000, 0, 0, 0, List.of(backdrive), .05);
        check(backdrive.rpm() > 512 && fast.speed() > 40, "wheel backdrive is allowed above the governor");
        var cruisingLow = engine(.5); var cruisingHigh = engine(2);
        double vLow = 0, vHigh = 0;
        for (int tick = 0; tick < 2400; tick++) {
            vLow = TrainMechanics.advance(vLow, 25_000, .5, 0, 0, List.of(cruisingLow), .05).speed();
            vHigh = TrainMechanics.advance(vHigh, 25_000, .5, 0, 0, List.of(cruisingHigh), .05).speed();
            check(Double.isFinite(vLow) && Double.isFinite(vHigh), "long drive remains finite");
        }
        check(vHigh > vLow * 2.5 && vHigh > 40, "high gear reaches speed beyond Create's old maximum");
        System.out.println("Train regression: " + checks + " checks");
    }
    private static TrainMechanics.Engine engine(double ratio) {
        var e = new TrainMechanics.Engine(); e.ratio = ratio; e.stallTorque = 1024 * 16; e.power = e.stallTorque * 256 * Math.PI / 30; return e;
    }
    private static double energy(double mass, double speed, TrainMechanics.Engine e) { return .5 * mass * speed * speed + .5 * e.inertia * e.omega * e.omega; }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void near(double expected, double actual, double tolerance, String label) { check(Math.abs(expected - actual) <= tolerance, label + ": expected " + expected + ", got " + actual); }
}
