package com.diliupengyun.differential_create;

import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.kinetics.IdealFluidSolver;
import com.diliupengyun.differential_create.kinetics.MacroFluidSolver;

/** Exhaustive finite domain, independent conservation/capacity expectations.
 * Every labelled simple graph on four cells, including disconnected graphs,
 * is combined with every empty/half/full assignment. This complements real
 * world tests; it does not claim to enumerate arbitrary Minecraft networks. */
public final class FluidPartitionRegression {
    private static int checks, cases;
    private static final double DT = MacroFluidSolver.MAX_STEP;
    private FluidPartitionRegression() {}
    public static void run() {
        allFourCellGraphs(); boundaryPartitions();
        System.out.println("Fluid partition regression: " + cases + " cases, " + checks + " checks");
    }
    public static void main(String[] args) { run(); }
    private static void allFourCellGraphs() {
        for (var material : List.of(new IdealFluidSolver.Material(1000, false), new IdealFluidSolver.Material(3000, false),
                                   new IdealFluidSolver.Material(1, true)))
            for (int height = 0; height < 3; height++) for (int graph = 0; graph < 64; graph++) for (int stocks = 0; stocks < 81; stocks++) {
                var cells = new ArrayList<MacroFluidSolver.Cell>(); int code = stocks;
                for (int n = 0; n < 4; n++) {
                    double bottom = height == 0 ? 0 : height == 1 ? n : n % 2;
                    cells.add(new MacroFluidSolver.Cell(100, 50 * (code % 3), bottom, bottom + 1, 0, 0)); code /= 3;
                }
                var links = new ArrayList<MacroFluidSolver.Link>(); int bit = 0;
                for (int a = 0; a < 4; a++) for (int b = a + 1; b < 4; b++, bit++) if ((graph & (1 << bit)) != 0)
                    links.add(new MacroFluidSolver.Link(a, b, 1, Math.max(cells.get(a).bottom(), cells.get(b).bottom()), 0, 0, true));
                var noBoundary = new MacroFluidSolver.Boundary[0];
                var result = MacroFluidSolver.sample(cells, links, noBoundary, new double[links.size()], material, DT); cases++;
                check(result, cells, 0);
                double total = cells.stream().mapToDouble(MacroFluidSolver.Cell::volume).sum();
                near(total, java.util.Arrays.stream(result.volume).sum(), 1e-7, "closed graph conserves independently counted actual stock");
                // Gravity datum translation is a true physical equivalence;
                // rotating the vertical axis would not be one.
                var translated = cells.stream().map(c -> new MacroFluidSolver.Cell(c.capacity(), c.volume(), c.bottom() + 128,
                    c.top() + 128, c.rpm(), c.pumpFlow())).toList();
                var movedLinks = links.stream().map(l -> new MacroFluidSolver.Link(l.from(), l.to(), l.length(), l.faceHeight() + 128,
                    l.fromPump(), l.toPump(), l.open())).toList();
                var moved = MacroFluidSolver.sample(translated, movedLinks, noBoundary, new double[links.size()], material, DT);
                for (int n = 0; n < 4; n++) near(result.volume[n], moved.volume[n], 1e-7, "height translation preserves the physical solution");
                var expired = MacroFluidSolver.sample(cells, links, noBoundary, new double[links.size()], material, DT, 0);
                for (int n = 0; n < 4; n++) near(cells.get(n).volume(), expired.volume[n], 0, "expired work budget preserves every original inventory exactly");
            }
    }
    private static void boundaryPartitions() {
        double wet = 100 * MacroFluidSolver.MIN_WET_FRACTION;
        double[] volumes = {0, 32 * Math.ulp(100d), .025, Math.nextDown(wet), wet, Math.nextUp(wet), 50, Math.nextDown(100d), 100};
        double[] available = {0, Math.nextDown(wet), wet, Math.nextUp(wet), 1, 1000, Double.POSITIVE_INFINITY};
        for (double volume : volumes) for (double rpm : new double[]{-256, -64, 0, 1, 64, 256})
            for (int mode = 0; mode < 8; mode++) for (double supply : available) for (int direction = -1; direction <= 1; direction++) {
                var material = new IdealFluidSolver.Material(1000, false);
                var cells = List.of(new MacroFluidSolver.Cell(100, volume, 0, 1, rpm, 0));
                var links = List.of(new MacroFluidSolver.Link(0, 1, .5, .5, -1, 0, true),
                    new MacroFluidSolver.Link(0, 2, .5, .5, 1, 0, true));
                var b = new MacroFluidSolver.Boundary[]{new MacroFluidSolver.Boundary(material.gravityForce(direction),
                    (mode & 1) != 0, (mode & 2) != 0, (mode & 4) != 0, supply),
                    new MacroFluidSolver.Boundary(0, false, true, true, 0)};
                var result = MacroFluidSolver.sample(cells, links, b, new double[]{0, 0}, material, DT); cases++;
                check(result, cells, 2);
                // Flow is mB per game tick; outward at an external node is
                // positive for a real debit from that source into the cell.
                double expected = volume + result.outward[1] + result.outward[2];
                near(expected, result.volume[0], 1e-7, "boundary flux independently accounts for real cell stock");
                truth(result.outward[1] <= supply + 1e-8, "a finite external source is never overdrawn");
                if ((mode & 1) == 0) truth(result.outward[1] <= 1e-9, "a nonsupplying source contributes no fabricated inventory");
                if ((mode & 2) == 0) truth(result.outward[1] >= -1e-9, "a rejecting receiver consumes no fluid");
                if (volume == 0 && (mode & 1) == 0) {
                    near(0, result.volume[0], 0, "dry pump has no fluid to move"); near(0, result.head[0], 0, "dry pump adds no head");
                }
            }
    }
    private static void check(MacroFluidSolver.Result result, List<MacroFluidSolver.Cell> cells, int boundaries) {
        truth(result.solves <= MacroFluidSolver.CONTACT_PASSES, "work uses the declared fixed contact pass limit");
        for (int n = 0; n < cells.size(); n++) truth(Double.isFinite(result.volume[n]) && result.volume[n] >= 0
            && result.volume[n] <= cells.get(n).capacity() + 1e-7, "actual finite inventory respects its independent capacity");
        for (double[] values : new double[][]{result.flow, result.outward, result.torque, result.head})
            for (double value : values) truth(Double.isFinite(value), "reported physical state is finite");
    }
    private static void truth(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void near(double expected, double actual, double tolerance, String label) {
        checks++; if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance)
            throw new AssertionError(label + ": " + actual + " / " + expected);
    }
}
