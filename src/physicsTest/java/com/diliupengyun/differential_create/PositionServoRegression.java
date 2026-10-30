package com.diliupengyun.differential_create;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.PositionServo;
import com.diliupengyun.differential_create.kinetics.ClockworkTargets;
import com.diliupengyun.differential_create.kinetics.TorsionSolver;
import com.diliupengyun.differential_create.kinetics.ShaftPotential;

/** Torque bounds, passive energy and target changes exercise the production solver. */
public final class PositionServoRegression {
    private static int checks;
    private static void require(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
    public static void main(String[] args) { run(); }
    public static void run() {
        clockTargets();
        for (float offset : new float[]{4, 128, 512}) {
            require(PositionServo.offsetError(offset, Math.nextDown(offset), 1200d / 256) == 0, "native float residue selects braking, not perpetual micro-motion");
            require(Math.abs(offset - Math.nextDown(offset)) < PositionServo.offsetTolerance(offset), "arrival accepts a one-ulp native offset");
            require(PositionServo.offsetError(offset, offset - .01, 1200d / 256) > 0, "arrival tolerance does not swallow a visible error");
        }
        for (double inertia : new double[]{.0001, .01, .5, 8}) for (int sign : new int[]{-1, 1}) {
            Rig rig = new Rig(inertia, sign * 64, 0, true);
            rig.target = .25;
            for (int i = 0; i < 25600; i++) {
                rig.step(); require(rig.position <= .25 + 2e-6, "unloaded approach brakes before crossing the target");
            }
            require(Math.abs(rig.position - .25) < 2e-6 && Math.abs(rig.speed[1]) < .02,
                "finite servo settles: J=" + inertia + " input=" + sign + " position=" + rig.position + " speed=" + rig.speed[1]);
            require(rig.heat >= 0 && Double.isFinite(rig.heat), "braking dissipates energy");
            rig.target = -.125;
            for (int i = 0; i < 25600; i++) rig.step();
            require(Math.abs(rig.position + .125) < 2e-6, "reverse target settles without assigning position");
        }
        Rig reverse = new Rig(.01, 64, 0, true); reverse.target = 3;
        for (int i = 0; i < 320; i++) reverse.step();
        double moving = reverse.speed[1]; require(moving > 10, "reversal starts in motion");
        reverse.target = -3; reverse.step();
        require(reverse.speed[1] > 0, "new target cannot flip velocity in one control sample");
        for (int i = 0; i < 2000; i++) reverse.step();
        require(reverse.speed[1] < 0, "bounded braking eventually permits reverse motion");

        Rig powerless = new Rig(.01, 0, 0, false); powerless.target = 1;
        for (int i = 0; i < 128; i++) powerless.step();
        require(powerless.position == 0 && powerless.speed[1] == 0, "target alone supplies no power");
        Rig heavy = new Rig(.5, 0, 2048, false); heavy.target = 0;
        for (int i = 0; i < 128; i++) heavy.step();
        require(heavy.speed[1] > 0 && heavy.position > 0, "overloaded parking brake slips rather than becoming a position lock");

        Rig passive = new Rig(.1, 64, 0, false); passive.target = 1;
        double previousEnergy = passive.energy();
        for (int i = 0; i < 4096; i++) {
            if (i % 256 == 0) passive.target = -passive.target;
            passive.step();
            double energy = passive.energy();
            require(energy <= previousEnergy + 1e-7, "target/gear changes cannot create kinetic energy");
            previousEnergy = energy;
        }
        System.out.println("Position servo regression: " + checks + " checks passed");
    }
    private static double wrapped(double angle) { return angle - 360 * Math.floor((angle + 180) / 360); }
    private static void clockTargets() {
        for (int direction : new int[]{-1, 1}) {
            require(ClockworkTargets.hour(500, false, direction) == -direction * 195,
                "at 06:30 the hour hand is halfway between 6 and 7");
            require(ClockworkTargets.hour(500, true, direction) == -direction * 97.5,
                "24-hour mode includes the same half-hour phase");
            require(ClockworkTargets.minute(125, direction) == -direction * 45,
                "the minute hand includes the half-minute at 06:07:30");
            for (long time = -24000; time <= 48000; time++) {
                require(Math.abs(wrapped(ClockworkTargets.hour(time + 1, false, direction)
                    - ClockworkTargets.hour(time, false, direction)) + direction * .03) < 4e-5,
                    "12-hour target advances uniformly across every minute, hour and day wrap");
                require(Math.abs(wrapped(ClockworkTargets.hour(time + 1, true, direction)
                    - ClockworkTargets.hour(time, true, direction)) + direction * .015) < 4e-5,
                    "24-hour target advances uniformly across every hour and midnight");
                require(Math.abs(wrapped(ClockworkTargets.minute(time + 1, direction)
                    - ClockworkTargets.minute(time, direction)) + direction * .36) < 4e-5,
                    "minute target has no whole-minute plateaus or jumps");
            }
        }
    }
    private static final class Rig {
        final double inertia, input, external;
        final boolean powered;
        double target, position, heat;
        double[] speed;
        Rig(double inertia, double input, double external, boolean powered) {
            this.inertia = inertia; this.input = input; this.external = external; this.powered = powered;
            speed = new double[]{input, 0};
        }
        double energy() { return .5 * speed[0] * speed[0] + .5 * inertia * speed[1] * speed[1]; }
        void step() {
            var command = PositionServo.plan(target - position, speed[1], speed[0], inertia, 0, external, false);
            var bodies = List.of(new TorsionSolver.Body(1, powered ? input * 1000 : 0, powered ? 1000 : 0, 0, 0),
                new TorsionSolver.Body(inertia, 0, 0, command.brake(), 0).withPotential(new ShaftPotential(-external, List.of())));
            var links = List.of(new TorsionSolver.Link(0, 1, command.gear(), 0, PositionServo.DAMPING, Double.POSITIVE_INFINITY, command.coupling()));
            var result = TorsionSolver.advance(bodies, links, speed, new double[1], PositionServo.CONTROL_INTERVAL, PositionServo.CONTROL_INTERVAL);
            double bound = (PositionServo.TORQUE + Math.abs(external)) * result.duration() / inertia;
            require(Math.abs(result.speed()[1] - speed[1]) <= bound + 1e-7, "output acceleration obeys finite torque bound");
            position += result.angle()[1]; heat += command.brake() * result.travel()[1]; speed = result.speed();
        }
    }
}
