package com.diliupengyun.differential_create;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.IdealFluidSolver;
import com.diliupengyun.differential_create.kinetics.QueuedFluidSolver;
import com.diliupengyun.differential_create.kinetics.RotationMath;

/** Direction reversal, standstill and mechanical/fluid power invariants. */
public final class PumpDirectionRegression {
    private static long checks;
    private static void require(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static void near(double expected, double actual, double tolerance, String why) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance, why + ": " + actual + " / " + expected);
    }
    private static final List<QueuedFluidSolver.Link> LINKS = List.of(
        new QueuedFluidSolver.Link(0, 1, .25, 0, -1, 0, true),
        new QueuedFluidSolver.Link(0, 2, .25, 0, 1, 0, true));
    private static QueuedFluidSolver.Result sample(double rpm, double oldFlow, double difference, double density, int polarity) {
        var cell = new QueuedFluidSolver.Cell(100, 0, density, 0, rpm, oldFlow, polarity);
        var ports = new QueuedFluidSolver.Boundary[]{new QueuedFluidSolver.Boundary(difference / 2, true, true, density),
            new QueuedFluidSolver.Boundary(-difference / 2, true, true, density)};
        return QueuedFluidSolver.sample(List.of(cell), LINKS, ports, new double[]{-oldFlow, oldFlow},
            RotationMath.TICK_MINUTES, Long.MAX_VALUE, (e, forward) -> true);
    }
    public static void run() {
        for (int polarity : new int[]{-1, 1}) for (double density : new double[]{1000, 3000}) for (double rpm : new double[]{-256, -64, -1, -1e-6, 0, 1e-6, 1, 64, 256})
            for (double oldFlow : new double[]{-40, -8, 0, 8, 40}) for (double pressure : new double[]{-12, -1, 0, 1, 12}) {
                var a = sample(rpm, oldFlow, pressure, density, polarity);
                var b = sample(-rpm, -oldFlow, -pressure, density, polarity);
                var flipped = sample(rpm, -oldFlow, -pressure, density, -polarity);
                near(0, a.flow()[0] + a.flow()[1], 1e-10, "one full pump has no net inventory change");
                near(-a.flow()[1], b.flow()[1], 1e-10, "reversing all physical inputs reverses actual flow");
                near(-a.torque()[0], b.torque()[0], 1e-10, "reaction follows the fixed axis, not an absolute speed");
                near(-a.head()[0], b.head()[0], 1e-9, "head uses the same signed axis");
                near(-a.flow()[1], flipped.flow()[1], 1e-10, "flipping the arrow and external flow/pressure reverses delivery at unchanged rotation");
                near(-a.head()[0], flipped.head()[0], 1e-9, "arrow reversal changes pressure polarity at unchanged rotation");
                near(a.torque()[0], flipped.torque()[0], 1e-10, "arrow and flow reversal preserve the load on an unchanged shaft");
                double waterPressure = a.head()[0] * 1000 * IdealFluidSolver.GRAVITY;
                double fluidPower = IdealFluidSolver.pressureForce(waterPressure) * a.flow()[1];
                double shaftPower = a.torque()[0] * rpm;
                near(0, shaftPower + fluidPower, 1e-10 * Math.max(1, Math.abs(fluidPower)), "mechanical and fluid powers are equal and opposite in every quadrant");
                require(a.solves() > 0 && a.solves() <= 3, "signed coupling retains the pressure pass limit");
            }
        for (int polarity : new int[]{-1, 1}) for (double direction : new double[]{-1, 1}) {
            var stopped = sample(0, 0, direction, 1000, polarity);
            require(stopped.flow()[1] * direction > 0 && stopped.torque()[0] * direction * polarity > 0,
                "flow and arrow polarity start a stopped shaft without a previous rotation sign");
            near(0, stopped.head()[0], 0, "standstill has no active pump head");
            for (double epsilon : new double[]{1e-3, 1e-5, 1e-7, 1e-9}) for (double sign : new double[]{-1, 1}) {
                var approaching = sample(sign * epsilon, 0, direction, 1000, polarity);
                near(stopped.torque()[0], approaching.torque()[0], 2 * epsilon,
                    "startup torque has the same two-sided limit at zero shaft speed");
            }
        }
        for (int polarity : new int[]{-1, 1}) for (double rpm : new double[]{-64, 64}) {
            var moving = sample(rpm, 0, 0, 1000, polarity);
            require(moving.flow()[1] * rpm * polarity > 0, "positive rotation pumps along the arrow, negative rotation against it");
            var blocked = QueuedFluidSolver.sample(List.of(new QueuedFluidSolver.Cell(100, 0, 1000, 0, rpm, 0, polarity)),
                LINKS, new QueuedFluidSolver.Boundary[]{new QueuedFluidSolver.Boundary(0, false, false, 1000),
                    new QueuedFluidSolver.Boundary(0, false, false, 1000)}, new double[2], RotationMath.TICK_MINUTES,
                Long.MAX_VALUE, (e, forward) -> true);
            require(blocked.head()[0] * rpm * polarity > 0, "shutoff pressure follows the arrow and actual rotation sign");
            near(0, blocked.flow()[1], 0, "a blocked pump has no continued delivery");
            near(0, blocked.torque()[0], 0, "shutoff pressure requires no ideal continued shaft power");
        }
        System.out.println("Pump direction regression: " + checks + " checks passed");
    }
}
