package com.diliupengyun.differential_create;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.IdealFluidSolver;
import com.diliupengyun.differential_create.kinetics.QueuedFluidSolver;
import com.diliupengyun.differential_create.kinetics.RotationMath;

/** Facing-controlled pressure, passive flow losses and no shaft regeneration. */
public final class PumpDirectionRegression {
    private static long checks;
    private static final List<QueuedFluidSolver.Link> LINKS = List.of(
        new QueuedFluidSolver.Link(0, 1, .25, 0, -1, 0, true),
        new QueuedFluidSolver.Link(0, 2, .25, 0, 1, 0, true));

    private static void require(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    private static void near(double expected, double actual, double tolerance, String why) {
        require(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
            why + ": " + expected + " / " + actual);
    }

    private static QueuedFluidSolver.Result sample(double rpm, double flow, double pressure, double density, int facing) {
        return QueuedFluidSolver.sample(List.of(new QueuedFluidSolver.Cell(100, 0, density, 0, rpm, flow, facing)), LINKS,
            new QueuedFluidSolver.Boundary[]{
                new QueuedFluidSolver.Boundary(pressure / 2, true, true, density),
                new QueuedFluidSolver.Boundary(-pressure / 2, true, true, density)},
            new double[]{-flow, flow}, RotationMath.TICK_MINUTES, Long.MAX_VALUE, (e, forward) -> true);
    }

    public static void run() {
        shaftSignsAndPower();
        directionAndShutoff();
        reverseLoopStability();
        System.out.println("Pump direction regression: " + checks + " checks passed");
    }

    private static void shaftSignsAndPower() {
        for (int facing : new int[]{-1, 1}) for (double density : new double[]{1000, 3000})
            for (double rpm : new double[]{-256, -64, -1, -1e-6, 0, 1e-6, 1, 64, 256})
                for (double initial : new double[]{-128, -40, -8, 0, 8, 40, 128})
                    for (double pressure : new double[]{-12, -1, 0, 1, 12})
                        checkSymmetryAndPower(rpm, initial, pressure, density, facing);
    }

    private static void checkSymmetryAndPower(double rpm, double initial, double pressure, double density, int facing) {
        var a = sample(rpm, initial, pressure, density, facing);
        var b = sample(-rpm, initial, pressure, density, facing);
        var mirror = sample(rpm, -initial, -pressure, density, -facing);
        near(0, a.flow()[0] + a.flow()[1], 1e-10, "full pump preserves inventory");
        near(a.flow()[1], b.flow()[1], 1e-10, "shaft reversal preserves fluid direction and speed");
        near(a.head()[0], b.head()[0], 1e-9, "shaft reversal preserves pressure");
        near(-a.torque()[0], b.torque()[0], 1e-10, "shaft resistance opposes either rotation");
        near(-a.flow()[1], mirror.flow()[1], 1e-10, "wrench and boundary reversal mirror flow");
        near(-a.head()[0], mirror.head()[0], 1e-9, "wrench mirrors the pressure axis");
        near(a.torque()[0], mirror.torque()[0], 1e-10, "mirrored plumbing keeps shaft demand");

        double q = a.flow()[1], speed = Math.abs(rpm) / IdealFluidSolver.REFERENCE_RPM;
        double scale = new IdealFluidSolver.Material(density, false).pumpScale();
        double fluid = IdealFluidSolver.pressureForce(a.head()[0] * 1000 * IdealFluidSolver.GRAVITY) * q;
        double shaft = a.torque()[0] * rpm;
        double loss = scale * speed / IdealFluidSolver.REFERENCE_FLOW * q * q
            + scale * speed * speed * Math.max(0, -facing * q);
        require(shaft <= 0, "pump never supplies shaft power");
        near(0, shaft + fluid + loss, 1e-9 * Math.max(1, loss), "forward work and hydraulic losses balance");
        if (rpm == 0) {
            near(0, a.torque()[0], 0, "liquid cannot start a resting rotor");
            near(0, a.head()[0], 0, "resting pump adds no head");
        }
    }

    private static void directionAndShutoff() {
        for (int facing : new int[]{-1, 1}) for (double rpm : new double[]{-64, 64}) {
            require(sample(rpm, 0, 0, 1000, facing).flow()[1] * facing > 0,
                "both shaft directions pump towards the configured outlet");
            var shut = QueuedFluidSolver.sample(List.of(new QueuedFluidSolver.Cell(100, 0, 1000, 0, rpm, 0, facing)), LINKS,
                new QueuedFluidSolver.Boundary[]{
                    new QueuedFluidSolver.Boundary(0, false, false, 1000),
                    new QueuedFluidSolver.Boundary(0, false, false, 1000)},
                new double[2], RotationMath.TICK_MINUTES, Long.MAX_VALUE, (e, forward) -> true);
            near(16 * facing, shut.head()[0], 1e-10, "shutoff head follows the configured outlet");
            near(0, shut.flow()[1], 0, "shutoff has no flow");
            near(0, shut.torque()[0], 0, "shutoff has no ongoing transport work");
        }
    }

    private static void reverseLoopStability() {
        var loop = List.of(LINKS.get(0), LINKS.get(1), new QueuedFluidSolver.Link(1, 2, .25, 0, 0, 0, true));
        for (double capacity : new double[]{100, 1000000}) for (double rpm : new double[]{-64, 64}) {
            double q = -40;
            for (int i = 0; i < 100; i++) {
                var cells = List.of(new QueuedFluidSolver.Cell(capacity, 0, 1000, 0, rpm, q, 1),
                    new QueuedFluidSolver.Cell(capacity, 0, 1000, 0, 0, 0, 1),
                    new QueuedFluidSolver.Cell(capacity, 0, 1000, 0, 0, 0, 1));
                var result = QueuedFluidSolver.sample(cells, loop, new QueuedFluidSolver.Boundary[0],
                    new double[]{-q, q, -q}, RotationMath.TICK_MINUTES, Long.MAX_VALUE, (e, forward) -> true);
                q = result.flow()[1];
                require(Math.abs(q) <= 40 + 1e-9, "reverse flow cannot grow towards a capacity-dependent clamp");
            }
            near(32, q, 1e-8, "closed loop converges with either shaft direction");
        }
    }
}
