package com.diliupengyun.differential_create;

import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.kinetics.SequencerMotion;
import com.diliupengyun.differential_create.kinetics.SequencerMotion.*;
import static com.diliupengyun.differential_create.kinetics.SequencerMotion.*;
import com.diliupengyun.differential_create.kinetics.TorsionSolver;
import com.diliupengyun.differential_create.kinetics.RotationMath;

/** Mechanical invariants and declared program/load partitions, not planner mocks. */
public final class SequencerRegression {
    public static void main(String[] args) { run(); }
    private static int checks, cases;
    private static void require(boolean ok, String why) {
        checks++; if (!ok) throw new AssertionError(why);
    }
    private static Segment move(int value, int ratio) { return new Segment(Kind.ANGLE, value, ratio); }
    public static void run() {
        planning();
        for (int sign : new int[]{-1, 1}) for (int ratio : new int[]{-2, -1, 1, 2})
            for (double j : new double[]{.01, .5, 8}) for (double load : new double[]{0, 64, 512})
                for (int gap : new int[]{0, 1, 3}) {
                    simulate(List.of(move(90, ratio), new Segment(Kind.END, 0, 0)), sign * 64, j, load, gap, 0, false);
                }
        for (int a : new int[]{-2, -1, 1, 2}) for (int b : new int[]{-2, -1, 1, 2})
            for (int tail : new int[]{1, 5, 90}) for (double j : new double[]{.5, 8})
                simulate(List.of(move(90, a), move(tail, b), new Segment(Kind.END, 0, 0)), 128, j, 0, 1, 0, false);
        for (Kind barrier : new Kind[]{Kind.DELAY, Kind.AWAIT, Kind.END}) for (int ratio : new int[]{-2, -1, 1, 2}) {
            simulate(List.of(move(30, ratio), new Segment(barrier, 10, 0)), 16, .5, 0, 0, 0, false);
        }
        for (int ratio : new int[]{-2, -1, 1, 2}) for (double rpm : new double[]{1, 16, 256})
            simulate(List.of(new Segment(Kind.DISTANCE, 1, ratio), new Segment(Kind.END, 0, 0)), rpm, .5, 0, 1, 0, true);
        for (double force : new double[]{-512, -64, 64, 512}) for (int ratio : new int[]{-1, 1})
            simulate(List.of(move(90, ratio), new Segment(Kind.END, 0, 0)), 64, .5, 0, 1, force, false);
        sourceLoss();
        pendingStart();
        overload();
        savedMasslessSpan();
        System.out.println("Sequencer regression: " + cases + " cases, " + checks + " checks passed");
    }
    private static void planning() {
        var p = List.of(move(90, 1), move(90, 1), new Segment(Kind.END, 0, 0));
        require(SequencerMotion.exitSpeed(p, 0, 64, 2048) > 0, "compatible moves blend");
        require(SequencerMotion.exitSpeed(p, 1, 64, 2048) == 0, "last move parks");
        var longChain = List.of(move(360, 1), move(360, 1), new Segment(Kind.END, 0, 0));
        var cruise = new SequencerMotion(); cruise.begin(longChain, 0, 64, false); cruise.angle = 1; cruise.speed = 64;
        var through = cruise.plan(longChain, new Plant(.1, 0, 0, 64), CONTROL_INTERVAL);
        require(through.exitSpeed() == 64 && Math.abs(through.velocity() - 64) < 1e-9 && through.brake() < 1e-9, "long compatible rotations need no braking at their common boundary");
        var empty = new SequencerMotion(); empty.begin(List.of(move(0, 1)), 0, 0, false); empty.observe(RotationMath.TICK_MINUTES);
        require(empty.complete(), "an empty move does not require drive power");
        require(SequencerMotion.exitSpeed(List.of(move(90, 1), move(90, -1)), 0, 64, 2048) == 0, "reverse parks");
        require(SequencerMotion.exitSpeed(List.of(move(90, 2), move(90, 1)), 0, 64, 1e6) == 64, "partial braking preserves slower exit speed");
        require(SequencerMotion.exitSpeed(List.of(move(90, 1), move(1, 1)), 0, 64, 2048)
            < SequencerMotion.exitSpeed(p, 0, 64, 2048), "short final move affects previous boundary");
        var m = new SequencerMotion(); m.begin(p, 0, 64, false); m.speed = 20; m.angle = .1;
        double light = m.plan(p, new Plant(.1, 0, 0, 64), CONTROL_INTERVAL).deceleration();
        double heavy = m.plan(p, new Plant(1, 0, 0, 64), CONTROL_INTERVAL).deceleration();
        require(light > heavy, "larger reflected inertia brakes earlier");
        require(m.plan(p, new Plant(1, 64, 0, 64), CONTROL_INTERVAL).deceleration() > heavy, "resistance aids braking");
        require(m.plan(p, new Plant(1, 0, 64, 64), CONTROL_INTERVAL).deceleration() < heavy, "overhauling force delays stopping");
        m.angle = .25; m.speed = 20; m.command = new Command(1, 0, 1024, 0, 0, 0, true);
        require(!m.complete(), "position alone cannot complete a stop");
        m.angle = .1; m.speed = 0; require(!m.complete(), "speed alone cannot complete a stop");
        double initial = m.progress(); m.angle += .02; m.angle -= .02; require(Math.abs(m.progress() - initial) < 1e-15, "oscillation grants no net progress");
        m.angle = 0; m.target = .25;
        var powerless = m.plan(p, new Plant(.5, 0, 0, 0), CONTROL_INTERVAL);
        require(powerless.coupling() == 0, "no free drive without input rotation");
        var overloaded = m.plan(p, new Plant(.5, 0, 2048, 64), CONTROL_INTERVAL);
        require(overloaded.coupling() == 0 && overloaded.brake() == TORQUE, "overhauling load beyond brake authority remains physical");
    }
    private static void simulate(List<Segment> p, double rpm, double inertia, double resistance, int gap, double force, boolean loss) {
        cases++;
        Rig rig = new Rig(p, rpm, inertia, resistance, gap, force);
        int stage = 0, settled = 0; double boundarySpeed = Double.NaN;
        for (int step = 0; step < 320000; step++) {
            if (loss && step >= 100 && step < 600) rig.power = false; else rig.power = true;
            rig.step();
            var m = rig.motion;
            if (m.index >= 0 && p.get(m.index).moving() && m.complete()) {
                int old = m.index;
                if (old == 0 && p.size() > 2) boundarySpeed = m.speed;
                if (m.command.exitSpeed() <= SPEED_EPSILON) require(m.parked(), "stop transition settles at the target");
                rig.motion.begin(p, old + 1, rig.speed[0], true); stage++;
            }
            if (m.index < 0 || !p.get(m.index).moving()) {
                if (++settled < 80) continue;
                double goal = 0;
                for (var s : p) { if (!s.moving()) break; goal += Math.signum(rpm) * Math.signum(s.ratio()) * s.turns(); }
                require(Math.abs(m.angle - goal) <= POSITION_EPSILON * 3, "position target: " + p + " rpm=" + rpm + " J=" + inertia + " load=" + resistance + " got=" + m.angle + " expected=" + goal);
                require(Math.abs(m.speed) <= SPEED_EPSILON, "output remains stopped");
                require(Math.abs(rig.speed[rig.last]) <= .2, "driven load also settles: " + p + " J=" + inertia + " load=" + resistance + " gap=" + gap + " w=" + rig.speed[rig.last]);
                require(m.brakeHeat >= 0 && Double.isFinite(m.brakeHeat), "brake dissipates finite nonnegative energy");
                if (p.size() > 2 && p.get(1).moving()) {
                    require(stage == 2, "every motion boundary remains observable");
                    if (p.get(0).ratio() * p.get(1).ratio() < 0) require(Math.abs(boundarySpeed) <= SPEED_EPSILON, "reversal parks at turning point");
                    else require(Math.abs(boundarySpeed) > SPEED_EPSILON, "continuous moves cross without full braking");
                }
                return;
            }
        }
        throw new AssertionError("Servo did not complete: " + p + " rpm=" + rpm + " J=" + inertia + " load=" + resistance + " force=" + force + " angle=" + rig.motion.angle + " speed=" + rig.motion.speed + " target=" + rig.motion.target + " command=" + rig.motion.command);
    }
    private static void sourceLoss() {
        cases++;
        var p = List.of(move(90, 1), new Segment(Kind.END, 0, 0));
        Rig rig = new Rig(p, 0, .5, 0, 1, 0);
        rig.power = false;
        for (int i = 0; i < 256; i++) rig.step();
        require(rig.motion.index == 0 && rig.motion.angle == 0 && rig.motion.speed == 0, "powerless command remains pending");
        rig.motion.angle = .24; rig.motion.speed = 128; rig.speed[1] = 128; rig.speed[rig.last] = 128;
        for (int i = 0; i < 20; i++) rig.step();
        require(rig.motion.index == 0, "overshoot cannot falsely finish with power absent");
    }
    private static void pendingStart() {
        for (int sign : new int[]{-1, 1}) for (int ratio : new int[]{-2, -1, 1, 2}) {
            cases++;
            var p = List.of(move(30, ratio), new Segment(Kind.END, 0, 0));
            var rig = new Rig(p, sign * 64, .5, 0, 1, 0);
            rig.speed[0] = 0; rig.motion.begin(p, 0, 0, false); rig.power = false;
            for (int i = 0; i < 80; i++) rig.step();
            require(rig.motion.index == 0 && !rig.motion.complete() && rig.motion.angle == 0 && rig.motion.progress() == 0, "unpowered start is a pending command, not a completed empty target");
            rig.power = true;
            int step = 0;
            while (!rig.motion.complete() && step++ < 32000) rig.step();
            require(step < 32000 && Math.abs(rig.motion.angle - sign * Math.signum(ratio) / 12) <= POSITION_EPSILON,
                "first real input direction defines the waiting program's forward/backward target");
        }
    }
    private static void overload() {
        for (int sign : new int[]{-1, 1}) {
            cases++;
            var rig = new Rig(List.of(move(90, sign), new Segment(Kind.END, 0, 0)), 64, .5, 0, 1, sign * 2048);
            for (int i = 0; i < 2048; i++) rig.step();
            require(!rig.motion.complete() && Math.abs(rig.motion.speed) > SPEED_EPSILON,
                "external driving beyond the brake rating cannot be disguised as completed positioning");
        }
    }
    /** Exact nine-node speeds and spring preload from the reported crash.
     * A new physical output spindle is appended; none of the saved springs
     * or neighbouring rotor speeds are equilibrated or overwritten. */
    private static void savedMasslessSpan() {
        cases++;
        double[] inertia = {0, 0, .5, 0, 0, 0, .01f, 0, .5, OUTPUT_INERTIA};
        double[] speed = {34.68918411000795, 2.168074006875497, 2.168074006875497,
            4.827850382510183, 7.487626758144868, 10.147403133779555, 12.80717950941424,
            7.387178990585392, 1.9671784717565444, 0};
        double[] twist = {1.0908122581317958e-18, -8.86454779818253e-19, -5.413302684799192e-5,
            .0001010258971059603, .00010102589694326542, .00010102589706290904,
            -.00012212147881587157, -3.17255607083371e-5, 0};
        var program = List.of(move(90, 1), new Segment(Kind.END, 0, 0));
        var m = new SequencerMotion(); m.begin(program, 0, speed[6], false);
        m.start = -89.87379073472506 / 360; m.target += m.start;
        double initialEnergy = Double.NaN;
        for (int step = 0; step < 12800; step++) {
            double j = .5 + OUTPUT_INERTIA;
            var c = m.plan(program, new Plant(j, 0, 0, speed[6], (.5 * speed[8] + OUTPUT_INERTIA * speed[9]) / j,
                Math.max(Math.abs(speed[8]), Math.abs(speed[9]))), CONTROL_INTERVAL);
            var bodies = new ArrayList<TorsionSolver.Body>();
            for (int i = 0; i < inertia.length; i++) bodies.add(new TorsionSolver.Body(inertia[i], 0, 0, i == 9 ? c.brake() : 0, 0));
            var links = new ArrayList<TorsionSolver.Link>();
            for (int edge = 0; edge < 8; edge++) {
                double q = edge == 0 ? .0625 : 1;
                links.add(new TorsionSolver.Link(edge == 6 ? 9 : edge, edge + 1, q, 2e7 / (1 + q * q),
                    edge == 6 ? OUTPUT_DAMPING : 0, 65536, Double.POSITIVE_INFINITY));
            }
            links.add(new TorsionSolver.Link(6, 9, c.gear(), 0, COUPLING_DAMPING, Double.POSITIVE_INFINITY, c.coupling()));
            if (step == 0) initialEnergy = storedEnergy(inertia, speed, links, twist);
            var result = TorsionSolver.advance(bodies, links, speed, twist, CONTROL_INTERVAL, CONTROL_INTERVAL);
            checkSaved(result.broken() < 0, "saved span cannot be fixed by removing its shaft");
            speed = result.speed(); twist = result.twist();
            m.angle += result.angle()[9]; m.speed = speed[9]; m.settlingSpeed = Math.max(Math.abs(speed[8]), Math.abs(speed[9]));
            m.brakeHeat += c.brake() * result.travel()[9]; m.observe(result.duration());
            checkSaved(storedEnergy(inertia, speed, links, twist) + m.brakeHeat <= initialEnergy + 1e-7,
                "saved massless residual is resolved by passive motion, not energy injection");
            if (m.complete()) {
                require(Math.abs(m.angle - m.target) <= POSITION_EPSILON && Math.abs(speed[8]) <= SPEED_EPSILON,
                    "original fractional target and actual flywheel settle together");
                return;
            }
        }
        throw new AssertionError("Saved sequence did not settle: angle=" + m.angle + " target=" + m.target + " speed=" + m.speed);
    }
    private static void checkSaved(boolean ok, String label) { require(ok, label); }
    private static double storedEnergy(double[] inertia, double[] speed, List<TorsionSolver.Link> links, double[] twist) {
        double energy = 0;
        for (int i = 0; i < inertia.length; i++) energy += .5 * inertia[i] * speed[i] * speed[i];
        for (int i = 0; i < links.size(); i++) energy += .5 * links.get(i).stiffness() * twist[i] * twist[i];
        return energy;
    }
    private static final class Rig {
        final List<Segment> program; final double rpm, inertia, resistance, force;
        final int last; final SequencerMotion motion = new SequencerMotion();
        double[] speed, twist; boolean power = true;
        Rig(List<Segment> p, double rpm, double j, double r, int gap, double force) {
            this.program = p; this.rpm = rpm; inertia = j; resistance = r; this.force = force;
            last = 2 + gap; speed = new double[last + 1]; twist = new double[last + 1];
            speed[0] = rpm; motion.begin(p, 0, rpm, false);
        }
        void step() {
            double j = OUTPUT_INERTIA + inertia;
            var c = motion.plan(program, new Plant(j, resistance, force, speed[0], (OUTPUT_INERTIA * speed[1] + inertia * speed[last]) / j,
                Math.max(Math.abs(speed[1]), Math.abs(speed[last]))), CONTROL_INTERVAL);
            var bodies = new ArrayList<TorsionSolver.Body>();
            bodies.add(new TorsionSolver.Body(.4, power ? rpm * 4096 : 0, power ? 4096 : 0, 0, 0));
            bodies.add(new TorsionSolver.Body(OUTPUT_INERTIA, 0, 0, c.brake(), 0));
            for (int i = 2; i < last; i++) bodies.add(new TorsionSolver.Body(0, 0, 0, 0, 0));
            bodies.add(new TorsionSolver.Body(inertia, force, 0, resistance, 0));
            var links = new ArrayList<TorsionSolver.Link>();
            for (int i = 1; i < last; i++) links.add(new TorsionSolver.Link(i, i + 1, 1, 1e7, i == 1 ? OUTPUT_DAMPING : 0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
            links.add(new TorsionSolver.Link(0, 1, c.gear(), 0, COUPLING_DAMPING, Double.POSITIVE_INFINITY, c.coupling()));
            twist = java.util.Arrays.copyOf(twist, links.size());
            double before = energy(links, speed, twist);
            var captured = TorsionSolver.prepare(bodies, links, speed, twist);
            double distance = motion.direction * (motion.target - motion.angle);
            var result = c.exitSpeed() > SPEED_EPSILON && distance > POSITION_EPSILON
                ? captured.advanceToEvent(CONTROL_INTERVAL, CONTROL_INTERVAL, List.of(new TorsionSolver.EventLimit(1, Double.POSITIVE_INFINITY,
                    motion.direction > 0 ? distance : Double.POSITIVE_INFINITY, motion.direction < 0 ? distance : Double.POSITIVE_INFINITY)))
                : captured.advance(CONTROL_INTERVAL, CONTROL_INTERVAL);
            double sourceWork = (power ? rpm * 4096 : 0) * result.angle()[0] + force * result.angle()[last];
            double brakeWork = c.brake() * result.travel()[1];
            double after = energy(links, result.speed(), result.twist());
            require(after <= before + sourceWork - brakeWork + 1e-6 * (1 + before + Math.abs(sourceWork)), "passive servo energy and input reaction");
            require(Math.abs(result.torque()[links.size() - 1]) <= TORQUE + 1e-7, "finite coupling torque");
            speed = result.speed(); twist = result.twist();
            motion.angle += result.angle()[1]; motion.speed = speed[1]; motion.brakeHeat += brakeWork;
            motion.settlingSpeed = Math.max(Math.abs(speed[1]), Math.abs(speed[last]));
            motion.observe(result.duration());
            require(Double.isFinite(motion.angle) && Double.isFinite(motion.speed), "finite physical motion");
        }
        double energy(List<TorsionSolver.Link> links, double[] w, double[] x) {
            double energy = .5 * .4 * w[0] * w[0] + .5 * OUTPUT_INERTIA * w[1] * w[1] + .5 * inertia * w[last] * w[last];
            for (int i = 0; i < links.size(); i++) energy += .5 * links.get(i).stiffness() * x[i] * x[i];
            return energy;
        }
    }
}
