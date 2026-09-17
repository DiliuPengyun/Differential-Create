package com.diliupengyun.differential_create;

import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.kinetics.TorsionSolver;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Body;
import com.diliupengyun.differential_create.kinetics.TorsionSolver.Link;

/** A brass clutch hot-attaching a flywheel can reverse a second clutch's friction branch. */
public final class ClutchEngagementRegression {
    private static final double INF = Double.POSITIVE_INFINITY, DT = 1.0 / 1200;
    private static int checks;

    public static void main(String[] args) { run(); }

    public static void run() {
        releasedBypass();
        for (double ratio : new double[]{1, 4, -4, 16})
            for (int direction : new int[]{1, -1})
                for (boolean reversed : new boolean[]{false, true})
                    for (int substeps : new int[]{64, 128}) engage(ratio, direction, reversed, substeps);
        System.out.println("Clutch engagement regression: " + checks + " checks passed");
    }

    private static void releasedBypass() {
        // An open clutch may still belong to a connected component through another path.
        // Its zero torque has no slip direction, even when its shafts turn differently.
        var bodies = List.of(new Body(.5, 0, 0, 0, 0), new Body(.5, 0, 0, 0, 0));
        var shaft = new Link(0, 1, 1, 1e7, 0, INF, INF);
        double[] speed = {256, 0};
        var expected = TorsionSolver.advance(bodies, List.of(shaft), speed, new double[1], DT, DT / 64);
        var actual = TorsionSolver.advance(bodies,
            List.of(shaft, new Link(0, 1, 1, 1e7, 0, INF, 0)), speed, new double[2], DT, DT / 64);
        for (int i = 0; i < bodies.size(); i++) {
            near(expected.speed()[i], actual.speed()[i], 1e-10, "open bypass cannot change rotor speed");
            near(expected.angle()[i], actual.angle()[i], 1e-12, "open bypass cannot change shaft travel");
        }
        near(expected.twist()[0], actual.twist()[0], 1e-12, "open bypass preserves the live spring");
        near(0, actual.torque()[1], 0, "open bypass transmits no torque");
        near(0, actual.twist()[1], 0, "open bypass stores no twist");
    }

    private static void engage(double ratio, int direction, boolean reversed, int substeps) {
        // A rotating flywheel drives two branches through a massless junction: a newly
        // attached stationary flywheel behind the brass clutch, and a light rotor behind
        // the starter clutch. The latter is initially synchronized through a gear pair.
        var bodies = List.of(new Body(.5, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0),
            new Body(.5, 0, 0, 0, 0), new Body(0, 0, 0, 0, 0),
            new Body(0, 0, 0, 0, 0), new Body(.01f, 0, 0, 0, 0));
        var links = new ArrayList<>(List.of(link(0, 1, 1, INF), link(1, 2, 1, 1024),
            link(1, 3, ratio, INF), link(3, 4, 1, 32), link(4, 5, 1 / ratio, INF)));
        if (reversed) links.replaceAll(link -> new Link(link.b(), link.a(), 1 / link.ratio(),
            link.stiffness() * link.ratio() * link.ratio(), 0,
            link.strength() * Math.abs(link.ratio()), link.friction() * Math.abs(link.ratio())));
        double w = direction * 256;
        double[] speed = {w, w, 0, ratio * w, ratio * w, w}, twist = new double[links.size()];
        double momentum = momentum(bodies, speed), energy = energy(bodies, links, speed, twist);
        for (int tick = 0; tick < 20; tick++) {
            var result = TorsionSolver.advance(bodies, links, speed, twist, DT, DT / substeps);
            near(DT, result.duration(), 0, "engagement advances the full tick");
            check(result.broken() < 0, "friction limits protect both branches");
            near(direction * 1024 * DT * (tick + 1) / .5, result.speed()[2], 1e-9,
                "stationary flywheel receives the finite brass-clutch impulse");
            near(momentum, momentum(bodies, result.speed()), 1e-9, "gear pair preserves total rotor momentum");
            check(result.slipping()[1], "brass clutch continues slipping during spin-up");
            check(Math.abs(result.torque()[1]) <= 1024 && Math.abs(result.torque()[3]) <= 32,
                "each clutch retains its own torque ceiling during reversals");
            double nextEnergy = energy(bodies, links, result.speed(), result.twist());
            check(nextEnergy < energy, "passive slipping contacts dissipate stored energy");
            for (double value : result.speed()) check(Double.isFinite(value), "every shaft speed remains finite");
            speed = result.speed(); twist = result.twist(); energy = nextEnergy;
        }
    }

    private static Link link(int a, int b, double ratio, double friction) {
        return new Link(a, b, ratio, 2e7 / (1 + ratio * ratio), 0,
            Math.min(8192 / Math.abs(ratio), 8192), friction);
    }

    private static double momentum(List<Body> bodies, double[] speed) {
        double sum = 0;
        for (int i = 0; i < speed.length; i++) sum += bodies.get(i).inertia() * speed[i];
        return sum;
    }

    private static double energy(List<Body> bodies, List<Link> links, double[] speed, double[] twist) {
        double sum = 0;
        for (int i = 0; i < speed.length; i++) sum += .5 * bodies.get(i).inertia() * speed[i] * speed[i];
        for (int e = 0; e < twist.length; e++) sum += .5 * links.get(e).stiffness() * twist[e] * twist[e];
        return sum;
    }

    private static void near(double expected, double actual, double tolerance, String message) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
            message + ": " + expected + " != " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
