package com.diliupengyun.differential_create;

import java.util.List;
import com.diliupengyun.differential_create.trains.TrainMechanics;
import com.diliupengyun.differential_create.trains.TrainAerodynamics;
import com.diliupengyun.differential_create.kinetics.RatioScale;

/** Force, energy and gearbox tests against physical expectations, independent of the game. */
public final class TrainRegression {
    private static int checks;
    public static void run() {
        TrainQuantitativeRegression.run();
        aerodynamics();
        for (int gear = 2; gear <= TrainMechanics.FORWARD_GEARS; gear++)
            near(2 * TrainMechanics.GEARS[gear - 1], TrainMechanics.GEARS[gear], 0, "each forward gear doubles wheel/input RPM ratio");
        int selected = TrainMechanics.REVERSE_GEAR;
        for (int next : new int[]{0, 1, 2, 3, 4, 5, 5}) {
            selected = TrainMechanics.stepGear(selected, 1, false);
            check(selected == next, "five-speed keyboard shift order and upper stop");
        }
        check(TrainMechanics.stepGear(5, 1, true) == TrainMechanics.REVERSE_GEAR, "physical lever wraps fifth to reverse");
        for (int octave = -4; octave <= 4; octave++) {
            int raw = 1 << (octave + 4);
            check(RatioScale.raw(40 + octave * 10) == raw, "octave marking");
            check(RatioScale.column(raw) == 40 + octave * 10, "inverse octave marking");
        }
        int previous = 0;
        for (int c = 0; c <= RatioScale.MAX; c++) { int raw = RatioScale.raw(c); check(raw >= previous && raw >= 1 && raw <= 256, "monotonic valid dial"); previous = raw; }
        for (int raw = 1; raw <= 256; raw++) check(RatioScale.column(raw) >= 0 && RatioScale.column(raw) <= RatioScale.MAX, "every native ratio value has a cursor");
        var coast = TrainMechanics.advance(100, 10_000, 1, 0, 0, List.of(), .05);
        check(coast.speed() > 90 && coast.speed() < 100, "above original speed caps, coasting remains continuous");
        var park = TrainMechanics.advance(0, 10_000, 2, .7071, 1, List.of(), 1);
        near(0, park.speed(), 0, "parking on steep supported track");
        for (double grade : new double[]{-.124, .124}) for (double ratio : new double[]{-.5, .5})
            for (double rotation : new double[]{-.4, .4}) {
                var residual = new TrainMechanics.Engine(); residual.ratio = ratio; residual.omega = rotation;
                double stored = energy(25_000, 0, residual);
                var held = TrainMechanics.advance(0, 25_000, 2, grade, 1, List.of(residual), .05);
                near(0, held.speed(), 0, "a finite static brake holds against a small residual rotor impulse");
                near(0, held.distance(), 0, "a warm stopped shaft cannot creep the parked wheels");
                check(energy(25_000, held.speed(), residual) < stored, "parking dissipates residual shaft energy without resetting its momentum");
            }
        var overpower = new TrainMechanics.Engine(); overpower.omega = 100; overpower.ratio = .5;
        var slip = TrainMechanics.advance(0, 1000, 0, 0, 1, List.of(overpower), .05);
        check(slip.speed() > 0 && slip.distance() > 0, "static braking cannot infinitely lock an input impulse above its finite capacity");
        var slope = TrainMechanics.advance(0, 10_000, 0, -.1, 0, List.of(), 1);
        check(slope.speed() > .9, "gravity accelerates an unbraked sourceless train");
        var brake = TrainMechanics.advance(1, 10_000, 0, 0, 1, List.of(), 1);
        near(0, brake.speed(), 0, "braking cannot reverse velocity");
        var low = engine(.5); var high = engine(2);
        double lowSpeed = TrainMechanics.advance(0, 25_000, 0, 0, 0, List.of(low), 1).speed();
        double highSpeed = TrainMechanics.advance(0, 25_000, 0, 0, 0, List.of(high), 1).speed();
        check(lowSpeed > highSpeed * 2, "low gear multiplies finite starting force");
        var reverse = engine(TrainMechanics.GEARS[TrainMechanics.REVERSE_GEAR]);
        double reverseSpeed = TrainMechanics.advance(0, 25_000, 0, 0, 0, List.of(reverse), 1).speed();
        near(-lowSpeed, reverseSpeed, 1e-10, "reverse gear mirrors first-gear traction with the same input torque");
        near(low.omega, reverse.omega, 1e-10, "reverse gearing preserves the engine's input rotation direction");
        reverse.stallTorque = reverse.power = 0;
        for (int i = 0; i < 40; i++) {
            reverseSpeed = TrainMechanics.advance(reverseSpeed, 25_000, 0, 0, 1, List.of(reverse), .05).speed();
            check(reverseSpeed <= 1e-12, "service braking a coupled reverse gear cannot launch forwards");
        }
        near(0, reverseSpeed, 1e-8, "continued service brake stops a reversing train");
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
    private static void aerodynamics() {
        var narrow = java.util.Set.of(TrainAerodynamics.cell(0, 0));
        var front = new java.util.HashSet<Long>();
        for (int x = -1; x <= 1; x++) for (int y = 0; y < 4; y++) front.add(TrainAerodynamics.cell(x, y));
        near(12, TrainAerodynamics.area(List.of(front, narrow, narrow)), 0, "aligned trailing carriages remain inside the leading silhouette");
        near(12, TrainAerodynamics.area(List.of(narrow, front)), 0, "a wider rear carriage exposes its own area");
        near(13, TrainAerodynamics.area(List.of(front, java.util.Set.of(TrainAerodynamics.cell(2, 0)))), 0, "lateral overhang is exposed");
        near(13, TrainAerodynamics.area(List.of(front, java.util.Set.of(TrainAerodynamics.cell(0, 4)))), 0, "vertical overhang is exposed");
        near(1, TrainAerodynamics.area(List.of(narrow, narrow, narrow)), 0, "longitudinal gaps never multiply a shared shadow");
        near(2, TrainAerodynamics.area(List.of(narrow, java.util.Set.of(TrainAerodynamics.cell(0, 2)))), 0, "a real hole in a silhouette stays empty");
        for (int x : new int[]{Integer.MIN_VALUE, -278, -1, 0, 1, 278, Integer.MAX_VALUE})
            for (int y : new int[]{Integer.MIN_VALUE, -58, -1, 0, 1, 58, Integer.MAX_VALUE}) {
                long cell=TrainAerodynamics.cell(x,y);
                check(TrainAerodynamics.lateral(cell)==x && TrainAerodynamics.height(cell)==y,"projection coordinates retain signed values");
            }
        var inverted=new java.util.HashSet<Long>(); TrainAerodynamics.include(inverted,narrow,true);
        check(inverted.contains(TrainAerodynamics.cell(0,-2)),"inverted carriages are projected below the rail");
        double shaded=TrainMechanics.advance(30,10_000,.55*12,0,0,List.of(),1).speed();
        double unshaded=TrainMechanics.advance(30,10_000,.55*24,0,0,List.of(),1).speed();
        check(shaded>unshaded,"shadowing reduces the actual deceleration");
        near(-shaded,TrainMechanics.advance(-30,10_000,.55*12,0,0,List.of(),1).speed(),1e-10,"reverse running uses the same geometric silhouette");
    }
    private static double energy(double mass, double speed, TrainMechanics.Engine e) { return .5 * mass * speed * speed + .5 * e.inertia * e.omega * e.omega; }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void near(double expected, double actual, double tolerance, String label) { check(Math.abs(expected - actual) <= tolerance, label + ": expected " + expected + ", got " + actual); }
}
