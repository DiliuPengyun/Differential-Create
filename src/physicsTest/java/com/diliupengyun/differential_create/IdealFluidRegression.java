package com.diliupengyun.differential_create;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Random;
import com.diliupengyun.differential_create.kinetics.IdealFluidSolver;
import com.diliupengyun.differential_create.kinetics.IdealFluidSolver.Edge;
import com.diliupengyun.differential_create.kinetics.IdealFluidSolver.Graph;
import com.diliupengyun.differential_create.kinetics.IdealFluidSolver.Material;

/** Analytical momentum, hydrostatic and energy expectations, independent of Minecraft. */
public final class IdealFluidRegression {
    private static int checks;
    private static final double H = 1.0 / 1200 / 64;
    private static final Material WATER = new Material(1000, false);

    public static void run() {
        inertance();
        pressureConstraints();
        mixedPipeInertances();
        connectedDryBranches();
        workIdentity();
        coupledRotor();
        timestepRefinement();
        System.out.println("Ideal fluid regression: " + checks + " checks passed");
    }
    private static Graph pipe(double length) {
        return new Graph(2, List.of(new Edge(0, 1, WATER.inertia(length))), new boolean[]{true, true}, new boolean[]{true});
    }
    private static void inertance() {
        double force = WATER.gravityForce(16);
        var shortPipe = pipe(4).sample(new double[]{0}, new double[]{force}, new double[2], H);
        var longPipe = pipe(16).sample(new double[]{0}, new double[]{force}, new double[2], H);
        near(shortPipe.flow()[0], 4 * longPipe.flow()[0], 1e-13, "four times mass accelerates four times less");
        double expected = H * force / WATER.inertia(4);
        near(expected, shortPipe.flow()[0], 1e-13, "Newton impulse for a uniform column");
        var coast = pipe(16).sample(new double[]{17}, new double[]{0}, new double[2], 1);
        near(17, coast.flow()[0], 0, "no hidden pipe drag");
        Material gas = new Material(1000, true);
        near(WATER.inertia(16), gas.inertia(16), 0, "gravity switch preserves mass");
        near(0, gas.gravityForce(100), 0, "gas ignores height");
        var up = pipe(8).sample(new double[]{0}, new double[]{0}, new double[]{0, WATER.gravityForce(8)}, H);
        var down = pipe(8).sample(new double[]{0}, new double[]{0}, new double[]{WATER.gravityForce(8), 0}, H);
        near(-up.flow()[0], down.flow()[0], 1e-13, "gravity reverses with height");
        var commonTank = pipe(8).sample(new double[]{0}, new double[]{0}, new double[]{force, force}, H);
        near(0, commonTank.flow()[0], 0, "same tank reference removes free height gain");
    }
    private static void pressureConstraints() {
        var edges = List.of(new Edge(0, 1, WATER.inertia(2)), new Edge(1, 2, WATER.inertia(4)), new Edge(1, 3, WATER.inertia(8)));
        Graph branch = new Graph(4, edges, new boolean[]{true, false, true, true}, new boolean[]{true, true, true});
        var trial = branch.sample(new double[3], new double[]{10, 0, 0}, new double[4], H);
        near(trial.flow()[0], trial.flow()[1] + trial.flow()[2], 1e-13, "junction conserves volume");
        near(2 * trial.flow()[2], trial.flow()[1], 1e-13, "initial branch acceleration follows mass, not equal split");
        Graph ring = new Graph(3, List.of(new Edge(0, 1, .01), new Edge(1, 2, .02), new Edge(2, 0, .03)),
            new boolean[3], new boolean[]{true, true, true});
        double[] motion = {9, 9, 9};
        for (int i = 0; i < 1000; i++) {
            var coast = ring.sample(motion, new double[3], new double[3], H);
            motion = coast.flow();
        }
        for (double speed : motion) near(9, speed, 0, "closed frictionless ring retains circulation");
        Graph blocked = new Graph(2, List.of(new Edge(0, 1, .01)), new boolean[]{true, true}, new boolean[]{false});
        var stop = blocked.sample(new double[]{5}, new double[]{10}, new double[2], H);
        near(0, stop.flow()[0], 0, "closed port stops flow");
        near(.125, stop.projectionLoss(), 1e-13, "boundary impulse accounts for removed motion");
    }
    private static void mixedPipeInertances() {
        // The saved-world crash: a pressure-response probe beside two wet arms
        // and one dry arm. Its raw dry-arm impulse is about 1e10 mB/tick;
        // the physical result is only about 1e3 and must still conserve volume.
        mixedBranch(new double[]{1.2420830652771467e-5, 1.787437178166065e-6, 7.339338676852757e-14},
            new double[]{8.123363575303479, .6446518389977431, 9.548981098824112e-16},
            new double[]{0, 0, 15.3036}, new double[3], 9.088507055356709e-5);
        Random random = new Random(0xD1F10A26);
        for (int sample = 0; sample < 240; sample++) {
            double[] mass = new double[3], force = new double[3], datum = new double[3];
            for (int i = 0; i < 3; i++) {
                mass[i] = WATER.inertia(.25 + random.nextDouble() * 30);
                force[i] = random.nextDouble() * 40 - 20; datum[i] = random.nextDouble() * 40 - 20;
            }
            mass[sample % 3] *= 1e-12;
            double[] initial = sample % 2 == 0 ? new double[3] : new double[]{3, -8, 5};
            double minutes = sample % 4 == 0 ? 1e-14 : IdealFluidSolver.MAX_STEP;
            mixedBranch(mass, force, datum, initial, minutes);
        }
    }

    private static void mixedBranch(double[] mass, double[] force, double[] datum, double[] initial, double minutes) {
        var edges = List.of(new Edge(0, 1, mass[0]), new Edge(0, 2, mass[1]), new Edge(0, 3, mass[2]));
        var trial = new Graph(4, edges, new boolean[]{false, true, true, true}, new boolean[]{true, true, true})
            .sample(initial, force, new double[]{0, datum[0], datum[1], datum[2]}, minutes);
        // Independent high-precision solution of the three-arm momentum and
        // continuity equations, including the small difference at the dry arm.
        MathContext precision = new MathContext(50);
        BigDecimal weight = BigDecimal.ZERO, weightedPressure = BigDecimal.ZERO;
        for (int i = 0; i < 3; i++) {
            BigDecimal inverse = BigDecimal.ONE.divide(BigDecimal.valueOf(mass[i]), precision);
            weight = weight.add(inverse, precision);
            weightedPressure = weightedPressure.add(BigDecimal.valueOf(datum[i]).subtract(BigDecimal.valueOf(force[i]))
                .multiply(inverse, precision), precision);
        }
        BigDecimal junction = weightedPressure.divide(weight, precision);
        double sum = 0, scale = 0;
        for (int i = 0; i < 3; i++) {
            double exact = BigDecimal.valueOf(minutes).multiply(BigDecimal.valueOf(force[i]).add(junction, precision)
                .subtract(BigDecimal.valueOf(datum[i]), precision), precision).divide(BigDecimal.valueOf(mass[i]), precision)
                .add(BigDecimal.valueOf(initial[i]), precision).doubleValue();
            near(exact, trial.flow()[i], 2e-12 * Math.max(1, Math.abs(exact)), "wet/dry branch momentum matches an independent solution");
            sum += trial.flow()[i]; scale += Math.abs(trial.flow()[i]);
        }
        near(0, sum, 8 * Math.ulp(scale), "wet/dry junction conserves flow at the scale of its actual motion");
        near(trial.energyChange(), trial.forceWork() + trial.boundaryWork(), 1e-12
            + 1e-10 * (Math.abs(trial.energyChange()) + Math.abs(trial.forceWork()) + Math.abs(trial.boundaryWork())),
            "wet/dry pressure response retains the original work tolerance");
    }

    private static void connectedDryBranches() {
        Random random = new Random(0xD1F10A);
        int[] from = {0, 1, 2, 0, 1, 2}, to = {1, 2, 0, 3, 4, 5};
        MathContext precision = new MathContext(50);
        for (int sample = 0; sample < 300; sample++) {
            var edges = new java.util.ArrayList<Edge>();
            double[] force = new double[6], pressure = new double[6], initial = new double[6];
            BigDecimal[][] matrix = new BigDecimal[3][3]; BigDecimal[] rhs = new BigDecimal[3];
            for (int i = 0; i < 3; i++) { java.util.Arrays.fill(matrix[i], BigDecimal.ZERO); rhs[i] = BigDecimal.ZERO; }
            for (int i = 0; i < 6; i++) {
                double mass = 1e-4 * (1 + random.nextDouble());
                boolean dry = switch (sample % 4) {
                    case 0 -> (sample + i) % 3 == 0;
                    case 1 -> i < 2;
                    case 2 -> i == 0;
                    default -> i < 3;
                };
                if (dry) mass *= 1e-12;
                edges.add(new Edge(from[i], to[i], mass));
                force[i] = random.nextDouble() * 30 - 15;
                if (dry && i < 3) force[i] = 0; // Empty internal arms carry no liquid gravity.
                if (i > 2) pressure[i] = random.nextDouble() * 30 - 15;
                if (sample % 2 == 0 && i < 3) initial[i] = 7; // Compatible circulating motion.
            }
            double minutes = sample % 5 == 0 ? 1e-14 : IdealFluidSolver.MAX_STEP;
            var trial = new Graph(6, edges, new boolean[]{false, false, false, true, true, true},
                new boolean[]{true, true, true, true, true, true}).sample(initial, force, pressure, minutes);
            for (int e = 0; e < edges.size(); e++) {
                int a = from[e], b = to[e];
                BigDecimal inverse = BigDecimal.ONE.divide(BigDecimal.valueOf(edges.get(e).inertia()), precision);
                BigDecimal drive = BigDecimal.valueOf(force[e]).add(BigDecimal.valueOf(pressure[a]))
                    .subtract(BigDecimal.valueOf(pressure[b])).multiply(inverse, precision);
                matrix[a][a] = matrix[a][a].add(inverse, precision); rhs[a] = rhs[a].subtract(drive, precision);
                if (b < 3) {
                    matrix[b][b] = matrix[b][b].add(inverse, precision); rhs[b] = rhs[b].add(drive, precision);
                    matrix[a][b] = matrix[a][b].subtract(inverse, precision); matrix[b][a] = matrix[b][a].subtract(inverse, precision);
                }
            }
            BigDecimal[] exactPressure = decimalSolve(matrix, rhs, precision);
            double[] sum = new double[3], scale = new double[3];
            for (int e = 0; e < edges.size(); e++) {
                int a = from[e], b = to[e];
                BigDecimal pTo = b < 3 ? exactPressure[b] : BigDecimal.valueOf(pressure[b]);
                double exact = BigDecimal.valueOf(force[e]).add(exactPressure[a], precision).subtract(pTo, precision)
                    .multiply(BigDecimal.valueOf(minutes), precision).divide(BigDecimal.valueOf(edges.get(e).inertia()), precision)
                    .add(BigDecimal.valueOf(initial[e]), precision).doubleValue();
                near(exact, trial.flow()[e], 2e-11 * Math.max(1, Math.abs(exact)), "connected dry branches match independent momentum equations");
                sum[a] += trial.flow()[e]; scale[a] += Math.abs(trial.flow()[e]);
                if (b < 3) { sum[b] -= trial.flow()[e]; scale[b] += Math.abs(trial.flow()[e]); }
            }
            for (int i = 0; i < 3; i++) near(0, sum[i], 8 * Math.ulp(scale[i]), "each adjacent dry junction conserves actual flow");
            near(trial.energyChange(), trial.forceWork() + trial.boundaryWork(), 1e-12
                + 1e-10 * (Math.abs(trial.energyChange()) + Math.abs(trial.forceWork()) + Math.abs(trial.boundaryWork())),
                "connected dry branches preserve the original work tolerance");
        }
    }

    private static BigDecimal[] decimalSolve(BigDecimal[][] matrix, BigDecimal[] rhs, MathContext precision) {
        for (int column = 0; column < rhs.length; column++) {
            for (int row = column + 1; row < rhs.length; row++) {
                BigDecimal factor = matrix[row][column].divide(matrix[column][column], precision);
                for (int i = column + 1; i < rhs.length; i++) matrix[row][i] = matrix[row][i].subtract(factor.multiply(matrix[column][i], precision), precision);
                rhs[row] = rhs[row].subtract(factor.multiply(rhs[column], precision), precision);
            }
        }
        BigDecimal[] result = new BigDecimal[rhs.length];
        for (int row = rhs.length - 1; row >= 0; row--) {
            BigDecimal value = rhs[row];
            for (int i = row + 1; i < rhs.length; i++) value = value.subtract(matrix[row][i].multiply(result[i], precision), precision);
            result[row] = value.divide(matrix[row][row], precision);
        }
        return result;
    }

    private static void workIdentity() {
        Random random = new Random(0xF10D26);
        for (int i = 0; i < 1000; i++) {
            double rpm = random.nextDouble() * 512 - 256, flow = random.nextDouble() * 128 - 64;
            double gain = IdealFluidSolver.coupling(WATER, rpm, flow);
            near((gain * rpm) * flow, (gain * flow) * rpm, 1e-10, "signed pump power equals signed shaft power");
            var trial = pipe(2 + random.nextDouble() * 40).sample(new double[]{flow}, new double[]{gain * rpm},
                new double[]{WATER.gravityForce(3), WATER.gravityForce(9)}, H);
            near(trial.energyChange(), trial.forceWork() + trial.boundaryWork(), 1e-10, "column work and kinetic energy");
        }
        near(0, IdealFluidSolver.coupling(WATER, 0, 12) * 0, 0, "stopped pump has finite zero pressure power");
        check(IdealFluidSolver.coupling(WATER, 0, 12) * 12 < 0, "moving fluid can start a stopped rotor");
        check(IdealFluidSolver.coupling(WATER, 64, 64) * 64 < 0, "overspeed fluid returns energy to a rotor");
    }
    private static void coupledRotor() {
        // The midpoint equations have an analytical two-by-two solve when their
        // shared coupling is frozen. No independent torque or efficiency fit is used.
        double mass = WATER.inertia(12), inertia = .01, rpm = 128, flow = 0;
        double initialEnergy = .5 * inertia * rpm * rpm + .5 * mass * flow * flow;
        for (int step = 0; step < 4000; step++) {
            double guessRpm = rpm, guessFlow = flow, nextRpm = rpm, nextFlow = flow;
            for (int iteration = 0; iteration < 100; iteration++) {
                double g = IdealFluidSolver.coupling(WATER, guessRpm, guessFlow);
                double a = H * g / (2 * inertia), b = H * g / (2 * mass);
                double midpointRpm = (rpm - a * flow) / (1 + a * b);
                double midpointFlow = flow + b * midpointRpm;
                nextRpm = 2 * midpointRpm - rpm; nextFlow = 2 * midpointFlow - flow;
                if (Math.abs(midpointRpm - guessRpm) + Math.abs(midpointFlow - guessFlow) < 1e-12) break;
                guessRpm = midpointRpm; guessFlow = midpointFlow;
            }
            rpm = nextRpm; flow = nextFlow;
            near(initialEnergy, .5 * inertia * rpm * rpm + .5 * mass * flow * flow, 1e-9, "isolated pump and fluid conserve joint energy");
        }
        check(flow > 0 && rpm < 128, "initial rotor energy accelerates water and slows the shaft");
    }

    private static void timestepRefinement() {
        // With a controlled 64-RPM shaft the positive-flow equation is a Riccati
        // equation. Compare integration against its closed-form transient, rather
        // than checking only that two executions agree with one another.
        double mass = WATER.inertia(8), a = WATER.pumpScale();
        double b = a / IdealFluidSolver.REFERENCE_FLOW, c = WATER.outletForce(1);
        double discriminant = Math.sqrt(b * b + 4 * a * c);
        double positive = 2 * a / (b + discriminant), negative = -(b + discriminant) / (2 * c);
        double time = 10.0 / 1200, decay = Math.exp(-discriminant * time / mass);
        double exact = positive * (1 - decay) / (1 - positive / negative * decay);
        double coarse = transientFlow(8, time), fine = transientFlow(64, time);
        near(exact, coarse, .003, "eight fluid substeps per tick resolve the analytical transient");
        check(Math.abs(fine - exact) < Math.abs(coarse - exact) / 20, "smaller steps reduce midpoint integration error");
        near(positive, transientFlow(8, 1), 1e-9, "controlled pump reaches the analytical boundary-energy equilibrium");
    }
    private static double transientFlow(int substeps, double time) {
        double step = 1.0 / 1200 / substeps, flow = 0;
        var graph = pipe(8);
        int count = (int) Math.round(time / step);
        for (int i = 0; i < count; i++) {
            double midpoint = flow, next = flow;
            for (int iteration = 0; iteration < 100; iteration++) {
                double force = IdealFluidSolver.coupling(WATER, 64, midpoint) * 64 - WATER.outletForce(midpoint);
                var trial = graph.sample(new double[]{flow}, new double[]{force}, new double[2], step);
                next = trial.flow()[0];
                double candidate = trial.midpoint()[0];
                if (Math.abs(candidate - midpoint) < 1e-12) break;
                midpoint = candidate;
            }
            flow = next;
        }
        return flow;
    }
    private static void check(boolean condition, String label) {
        checks++; if (!condition) throw new AssertionError(label);
    }
    private static void near(double expected, double actual, double tolerance, String label) {
        checks++; if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance)
            throw new AssertionError(label + ": " + actual + " != " + expected);
    }
}
