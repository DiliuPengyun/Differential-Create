package com.diliupengyun.differential_create;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.diliupengyun.differential_create.kinetics.*;

/** Actual-fluid inventory, contact, wet pumps and bounded work in the live model. */
public final class MacroFluidRegression {
    private static final IdealFluidSolver.Material WATER = new IdealFluidSolver.Material(1000, false);
    private static int checks;
    public static void main(String[] args) { run(); }
    public static void run() {
        dryPump(); hydrostaticAndInventory(); closedWaterDoesNotRebound(); randomClosedNetworks(); blockedReceipt(); exhaustedBudget();
        reversingPumpBrakesIncomingFlow(); waterBackdrivesStoppedRotor(); extremeSpeedHasBoundedFlow(); wetSeriesPumpsKeepTheirPrime();
        exposedColumnCanRecede(); blindPipeCanFill(); actualGasRetainsInventoryAndPumpWork();
        subResolutionWaterFalls(); horizontalLowerLipDrains();
        openHorizontalRunsEmptyTheirActualStock();
        horizontalDropsPassAnUnprimedPump();
        exposedMachineReceivesItsFinalDrop();
        drySideArmDoesNotPinPartialStorage();
        dropsTurnIntoLowerElbowsAndLeaveDownwardMouths();
        anExhaustedImpellerCanSpendItsRealPrime();
        fillingChangesHeadInTheSameStep(); floodedColumnsFillTheirHorizontalConnection();
        pumpFillsAnOrdinaryPipeRun();
        scarceIntakeKeepsAvailableInflow();
        blockedOutletStillAllowsRealSpaceToFill();
        limitedWetSourceLetsFullColumnRecede();
        limitedSourceKeepsAPressurisedPipeFull();
        System.out.println("Macro fluid regression: " + checks + " checks");
    }
    private static MacroFluidSolver.Cell cell(double volume, double rpm) {
        return new MacroFluidSolver.Cell(100, volume, -.005, .005, rpm, 0);
    }
    private static void drySideArmDoesNotPinPartialStorage() {
        var partial = new MacroFluidSolver.Cell(100, 75, 0, 1, 0, 0);
        var upper = new MacroFluidSolver.Cell(100, 0, 1, 2, 0, 0);
        var links = List.of(new MacroFluidSolver.Link(0, 1, 1, 1, 0, 0, true),
            new MacroFluidSolver.Link(0, 2, .5, 0, 0, 0, true));
        double duration = MacroFluidSolver.MAX_STEP;
        double acceleration = duration / (WATER.inertia(.5) * .875);
        double expected = acceleration * WATER.gravityForce(.75 - 2)
            / (1 + acceleration * WATER.gravityForce(1) / 100 * duration / RotationMath.TICK_MINUTES);
        var result = MacroFluidSolver.sample(List.of(partial, upper), links,
            new MacroFluidSolver.Boundary[]{border(2, true, true)}, new double[2], WATER, duration);
        near(expected, result.flow[1], 1e-12, "a dry upper side preserves the partial junction's implicit storage law");
        near(0, result.volume[1], 0, "an unwetted upper mouth cannot supply fictional liquid");
        near(75, result.volume[0] - result.outward[2], 1e-12, "real source receipt and junction inventory remain simultaneous and conservative");
    }
    private static void dropsTurnIntoLowerElbowsAndLeaveDownwardMouths() {
        var row = cell(.025, 0);
        var elbow = new MacroFluidSolver.Cell(100, 0, -.5, .005, 0, 0);
        for (boolean reversed : new boolean[]{false, true}) {
            var cells = reversed ? List.of(elbow, row) : List.of(row, elbow);
            var links = List.of(new MacroFluidSolver.Link(0, 1, 1, 0, 0, 0, true));
            var result = MacroFluidSolver.sample(cells, links, new MacroFluidSolver.Boundary[0], new double[1], WATER, MacroFluidSolver.MAX_STEP);
            near(0, result.volume[reversed ? 1 : 0], 0, "a real horizontal last drop can reach the lower floor of a bend in either graph ordering");
            near(.025, result.volume[reversed ? 0 : 1], 1e-12, "the bend receives actual liquid rather than deleting the drop");
        }
        var down = new MacroFluidSolver.Cell(100, .025, 0, 1, 0, 0);
        var links = List.of(new MacroFluidSolver.Link(0, 1, .5, 0, 0, 0, true));
        for (boolean receives : new boolean[]{false, true}) {
            var boundary = new MacroFluidSolver.Boundary(0, false, receives, true);
            var result = MacroFluidSolver.sample(List.of(down), links, new MacroFluidSolver.Boundary[]{boundary}, new double[1], WATER, MacroFluidSolver.MAX_STEP);
            near(receives ? 0 : .025, result.volume[0], 1e-12, "only a receiving downward mouth releases its real tail");
            near(.025, result.volume[0] - result.outward[1], 1e-12, "a downward mouth accounts for the committed fraction");
        }
    }
    private static void anExhaustedImpellerCanSpendItsRealPrime() {
        // The native tank-to-horizontal-outlet fixture stranded these exact
        // inventories after both the supplying tank and the middle pipe emptied.
        // The impeller is just above the wet threshold, not an infinite source.
        for (double rpm : new double[]{0, 12, -12}) {
            var cells = List.of(cell(.15467954779681142, rpm), cell(0, 0),
                new MacroFluidSolver.Cell(100, .045490190827703926, -.005, .5, 0, 0));
            var links = List.of(new MacroFluidSolver.Link(0, 3, .25, 0, 1, 0, true),
                new MacroFluidSolver.Link(0, 1, .75, 0, -1, 0, true),
                new MacroFluidSolver.Link(1, 2, 1, 0, 0, 0, true),
                new MacroFluidSolver.Link(2, 4, .5, .5, 0, 0, true));
            var borders = new MacroFluidSolver.Boundary[]{border(-.005, false, true), border(.5, false, true)};
            double total = cells.stream().mapToDouble(MacroFluidSolver.Cell::volume).sum(), spent = 0;
            double[] flow = new double[links.size()];
            for (int tick = 0; tick < 96; tick++) {
                var result = MacroFluidSolver.sample(cells, links, borders, flow, WATER, MacroFluidSolver.MAX_STEP);
                spent -= result.outward[3] + result.outward[4];
                near(total, spent + java.util.Arrays.stream(result.volume).sum(), 1e-10,
                    "an exhausted impeller transfers only its actual water, even while coasting");
                truth(result.solves <= MacroFluidSolver.CONTACT_PASSES, "release of a starved prime uses the existing pressure passes");
                finite(result); cells = next(cells, result); flow = result.flow;
            }
            near(total, spent, 1e-10, "the real prime and elbow tail reach the exposed low outlet");
            for (var item : cells) near(0, item.volume(), 0, "no wet threshold locks the last real impeller water");
        }
    }
    private static void exposedMachineReceivesItsFinalDrop() {
        var cells = List.of(cell(.04673473772578681, 0));
        var links = List.of(new MacroFluidSolver.Link(0, 1, .5, 0, 0, 0, true));
        for (double available : new double[]{0, 1, 999}) {
            var boundary = new MacroFluidSolver.Boundary(WATER.gravityForce(-.005), available > 0, true, true, available);
            var result = MacroFluidSolver.sample(cells, links, new MacroFluidSolver.Boundary[]{boundary}, new double[1], WATER, MacroFluidSolver.MAX_STEP);
            near(0, result.volume[0], 0, "an exposed receiving machine does not block its last real drop because it also has stock");
            near(cells.getFirst().volume(), -result.outward[1], 1e-12, "the last drop is a real conservative external receipt");
            truth(Math.abs(result.flow[0]) <= 50, "machine tail receipt shares the normal rate limit");
            for (var blocked : List.of(new MacroFluidSolver.Boundary(WATER.gravityForce(-.005), available > 0, false, true, available),
                    new MacroFluidSolver.Boundary(WATER.gravityForce(0), available > 0, true, true, available),
                    new MacroFluidSolver.Boundary(WATER.gravityForce(-.005), available > 0, true, false, available))) {
                var retained = MacroFluidSolver.sample(cells, links, new MacroFluidSolver.Boundary[]{blocked}, new double[1], WATER, MacroFluidSolver.MAX_STEP);
                truth(retained.volume[0] >= cells.getFirst().volume() - 1e-12 && retained.outward[1] >= -1e-12,
                    "blocked, raised or pressure-sealed receivers cannot discharge the old drop");
                near(cells.getFirst().volume(), retained.volume[0] - retained.outward[1], 1e-12,
                    "any higher-head external stock entering the pipe remains actual conserved supply");
            }
        }
    }
    private static List<MacroFluidSolver.Cell> next(List<MacroFluidSolver.Cell> cells, MacroFluidSolver.Result result) {
        var next = new ArrayList<MacroFluidSolver.Cell>();
        for (int n = 0; n < cells.size(); n++) {
            var old = cells.get(n);
            next.add(new MacroFluidSolver.Cell(old.capacity(), result.volume[n], old.bottom(), old.top(),
                old.rpm(), old.pumpFlow()));
        }
        return next;
    }
    private static void fillingChangesHeadInTheSameStep() {
        var pipe = new MacroFluidSolver.Cell(100, 70, -.5, .5, 0, 0);
        var cells = List.of(pipe);
        var links = List.of(new MacroFluidSolver.Link(0, 1, .5, 0, 0, 0, true));
        double minutes = MacroFluidSolver.MAX_STEP;
        double inertia = WATER.inertia(.5) * .5 * (pipe.fraction() + 1);
        double risePerMb = WATER.gravityForce(pipe.top() - pipe.bottom()) / pipe.capacity();
        double acceleration = minutes / inertia;
        double expectedFlow = acceleration * WATER.gravityForce(pipe.surface() - .8)
            / (1 + acceleration * risePerMb * minutes / RotationMath.TICK_MINUTES);
        var result = MacroFluidSolver.sample(cells, links, new MacroFluidSolver.Boundary[]{border(.8, true, true)},
            new double[1], WATER, minutes);
        near(expectedFlow, result.flow[0], 1e-12, "incoming water raises its own hydrostatic head within this update");
        near(70 - expectedFlow * minutes / RotationMath.TICK_MINUTES, result.volume[0], 1e-12,
            "pressure, flow and real storage use the same implicit step");
        near(1, result.solves, 0, "storage coupling adds no pressure passes");
        finite(result);
    }
    private static void floodedColumnsFillTheirHorizontalConnection() {
        // The partly filled bottom pipe already has water over both mouths.
        // That permits pressure transmission, but cannot freeze its real stock.
        var cells = List.of(new MacroFluidSolver.Cell(100, 100, -.005, .5, 0, 0),
            cell(59.311833076944495, 0), new MacroFluidSolver.Cell(100, 100, -.005, .5, 0, 0),
            new MacroFluidSolver.Cell(100, 100, .5, 1.5, 0, 0),
            new MacroFluidSolver.Cell(100, 100, .5, 1.5, 0, 0),
            new MacroFluidSolver.Cell(100, 75.39, 1.5, 2.5, 0, 0),
            new MacroFluidSolver.Cell(100, 78.87, 1.5, 2.5, 0, 0));
        var links = List.of(new MacroFluidSolver.Link(0, 1, 1, 0, 0, 0, true),
            new MacroFluidSolver.Link(1, 2, 1, 0, 0, 0, true),
            new MacroFluidSolver.Link(0, 3, 1, .5, 0, 0, true),
            new MacroFluidSolver.Link(3, 5, 1, 1.5, 0, 0, true),
            new MacroFluidSolver.Link(2, 4, 1, .5, 0, 0, true),
            new MacroFluidSolver.Link(4, 6, 1, 1.5, 0, 0, true));
        double total = cells.stream().mapToDouble(MacroFluidSolver.Cell::volume).sum();
        double[] flow = new double[links.size()];
        for (int tick = 0; tick < 240; tick++) {
            var result = MacroFluidSolver.sample(cells, links, new MacroFluidSolver.Boundary[0], flow, WATER, MacroFluidSolver.MAX_STEP);
            near(total, java.util.Arrays.stream(result.volume).sum(), 1e-8, "U-shaped filling redistributes existing water only");
            for (double volume : result.volume) truth(volume >= 0 && volume <= 100 + 1e-8, "U-shaped filling respects cell capacities");
            if (tick == 0) {
                truth(result.volume[1] > cells.get(1).volume(), "both flooded columns can add water to the wet horizontal connection");
                truth(result.flow[0] > 0 && result.flow[1] < 0, "the two legs feed the bottom simultaneously");
            }
            finite(result); cells = next(cells, result); flow = result.flow;
        }
        near(100, cells.get(1).volume(), 1e-8, "a horizontal pipe below both water columns becomes actually full");
        near(100, cells.get(0).volume(), 1e-8, "the left flooded elbow remains full");
        near(100, cells.get(2).volume(), 1e-8, "the right flooded elbow remains full");
    }
    private static void pumpFillsAnOrdinaryPipeRun() {
        var cells = new ArrayList<MacroFluidSolver.Cell>();
        var links = new ArrayList<MacroFluidSolver.Link>();
        for (int n = 0; n < 9; n++) cells.add(cell(0, n == 4 ? 64 : 0));
        links.add(new MacroFluidSolver.Link(0, 9, .5, 0, 0, 0, true));
        for (int n = 0; n < 8; n++) links.add(new MacroFluidSolver.Link(n, n + 1, 1, 0, n == 4 ? 1 : 0, n + 1 == 4 ? -1 : 0, true));
        links.add(new MacroFluidSolver.Link(8, 10, .5, 0, 0, 0, true));
        var boundaries = new MacroFluidSolver.Boundary[]{border(8, true, true), border(0, true, true)};
        double[] flow = new double[links.size()]; double debit = 0, credit = 0;
        for (int tick = 0; tick < 160; tick++) {
            var result = MacroFluidSolver.sample(cells, links, boundaries, flow, WATER, MacroFluidSolver.MAX_STEP);
            debit += result.outward[9]; credit -= result.outward[10];
            near(debit - credit, java.util.Arrays.stream(result.volume).sum(), 1e-7, "priming a pipe run retains each real source debit");
            finite(result); flow = result.flow;
            cells = new ArrayList<>(next(cells, result));
            var pump = cells.get(4);
            cells.set(4, new MacroFluidSolver.Cell(100, pump.volume(), -.005, .005, 64, flow[5]));
        }
        truth(flow[9] > 10, "a primed pump keeps transporting through ordinary pipes after filling");
        for (int n = 0; n < 9; n++) if (n != 4)
            near(100, cells.get(n).volume(), 1e-7, "ordinary pipe actually fills while carrying pump pressure");
        near(flow[1], flow[9], 1e-8, "the filled run transports one conserved through-flow");
    }
    private static void scarceIntakeKeepsAvailableInflow() {
        var cells = List.of(cell(100, 0), cell(.11, 0), cell(10, 128));
        var links = List.of(new MacroFluidSolver.Link(0, 3, .5, 0, 0, 0, true),
            new MacroFluidSolver.Link(0, 1, 1, 0, 0, 0, true),
            new MacroFluidSolver.Link(1, 2, .5, 0, 0, -1, true),
            new MacroFluidSolver.Link(2, 4, .25, 0, 1, 0, true));
        var result = MacroFluidSolver.sample(cells, links,
            new MacroFluidSolver.Boundary[]{border(2, true, true), border(0, true, true)},
            new double[4], WATER, MacroFluidSolver.MAX_STEP);
        truth(result.flow[1] > 4, "a starved pump branch does not throttle the available source column");
        near(result.flow[1], -result.flow[0], 1e-9, "limiting preserves continuity in the full source column");
        near(result.flow[2], result.flow[3], 1e-9, "limiting preserves wet-impeller through-flow");
        for (int n = 0; n < cells.size(); n++) truth(result.volume[n] >= 0 && result.volume[n] <= 100 + 1e-8,
            "local limiting cannot overdraw or overflow a cell");
        near(110.11, java.util.Arrays.stream(result.volume).sum() - result.outward[3] - result.outward[4], 1e-9,
            "independent incoming and outgoing limits still conserve actual boundary water");
        finite(result);
    }
    private static void blockedOutletStillAllowsRealSpaceToFill() {
        var cells = new ArrayList<MacroFluidSolver.Cell>();
        var links = new ArrayList<MacroFluidSolver.Link>();
        for (int n = 0; n < 9; n++) cells.add(cell(n == 4 ? 10 : 60, n == 4 ? 64 : 0));
        links.add(new MacroFluidSolver.Link(0, 9, .5, 0, 0, 0, true));
        for (int n = 0; n < 8; n++) links.add(new MacroFluidSolver.Link(n, n + 1, 1, 0, n == 4 ? 1 : 0, n + 1 == 4 ? -1 : 0, true));
        links.add(new MacroFluidSolver.Link(8, 10, .5, 0, 0, 0, true));
        var boundaries = new MacroFluidSolver.Boundary[]{border(8, true, true), border(0, false, false)};
        double[] flow = new double[links.size()]; double debit = 0;
        for (int tick = 0; tick < 160; tick++) {
            var result = MacroFluidSolver.sample(cells, links, boundaries, flow, WATER, MacroFluidSolver.MAX_STEP);
            debit += result.outward[9];
            near(490 + debit, java.util.Arrays.stream(result.volume).sum(), 1e-8, "blocked-outlet admission remains real owned water");
            near(0, result.outward[10], 0, "a blocked native receiver accepts no phantom flow");
            finite(result); flow = result.flow;
            cells = new ArrayList<>(next(cells, result));
            cells.set(4, new MacroFluidSolver.Cell(100, cells.get(4).volume(), -.005, .005, 64, flow[5]));
        }
        for (int n = 0; n < 9; n++) if (n != 4) near(100, cells.get(n).volume(), 1e-8,
            "blocking the receiver does not freeze remaining ordinary pipe space");
        truth(debit > 300 && debit <= 320 + 1e-8, "the source contributes no more than the actual remaining pipe capacity");
        near(0, flow[0], 1e-8, "source extraction stops after the connected space is full");
    }
    private static void limitedWetSourceLetsFullColumnRecede() {
        var cells = new ArrayList<MacroFluidSolver.Cell>(); var links = new ArrayList<MacroFluidSolver.Link>();
        for (int n = 0; n < 7; n++) cells.add(new MacroFluidSolver.Cell(100, n >= 4 ? 100 : 1, n, n + 1, 0, 0));
        for (int n = 0; n < 6; n++) links.add(new MacroFluidSolver.Link(n, n + 1, 1, n + 1, 0, 0, true));
        links.add(new MacroFluidSolver.Link(6, 7, .5, 7, 0, 0, true));
        links.add(new MacroFluidSolver.Link(0, 8, .5, 0, 0, 0, true));
        var borders = new MacroFluidSolver.Boundary[]{new MacroFluidSolver.Boundary(WATER.gravityForce(7.3125), true, true, false, 4),
            border(0, false, true)};
        double[] flow = new double[links.size()]; double debit = 0, credit = 0;
        for (int tick = 0; tick < 240; tick++) {
            var result = MacroFluidSolver.sample(cells, links, borders, flow, WATER, MacroFluidSolver.MAX_STEP);
            // Match the native inventory reservation: four real mB are available
            // each tick, even though the source mouth remains wet throughout.
            if (result.outward[7] > 4) result = result.scale(4 / result.outward[7]);
            debit += result.outward[7]; credit -= result.outward[8];
            near(304 + debit - credit, java.util.Arrays.stream(result.volume).sum(), 1e-7,
                "receding full column conserves its own water and each actual source debit");
            truth(result.outward[7] <= 4 + 1e-8, "the small wet reservoir supplies no more than its actual inventory");
            for (double amount : result.volume) truth(amount >= 0 && amount <= 100 + 1e-8, "a receding cell respects actual inventory and capacity");
            finite(result); cells = new ArrayList<>(next(cells, result)); flow = result.flow;
        }
        for (int n = 4; n < 7; n++) truth(cells.get(n).volume() < 20,
            "continued small inflow cannot lock the upper full cells above an unfilled falling run: " + cells.get(n).volume());
    }
    private static void limitedSourceKeepsAPressurisedPipeFull() {
        var cells = List.of(cell(100, 0));
        var links = List.of(new MacroFluidSolver.Link(0, 1, .5, 0, 0, 0, true),
            new MacroFluidSolver.Link(0, 2, .5, 0, 0, 0, true));
        var borders = new MacroFluidSolver.Boundary[]{new MacroFluidSolver.Boundary(WATER.gravityForce(10), true, true, false, 4),
            border(9, false, true)};
        double[] flow = {-10, 10};
        for (int tick = 0; tick < 30; tick++) {
            var result = MacroFluidSolver.sample(cells, links, borders, flow, WATER, MacroFluidSolver.MAX_STEP);
            near(100, result.volume[0], 1e-9, "limited supply does not unfill a pipe still supported by positive pressure");
            truth(result.outward[1] <= 4 + 1e-9 && result.outward[1] > 0, "a pressurised filled pipe carries its real available replenishment");
            near(result.outward[1], -result.outward[2], 1e-9, "limited through-flow preserves the supported full cell");
            finite(result); cells = next(cells, result); flow = result.flow;
        }
    }
    private static void dryPump() {
        var cells = List.of(cell(0, 4096));
        var links = List.of(new MacroFluidSolver.Link(0, 1, .25, 0, -1, 0, true),
            new MacroFluidSolver.Link(0, 2, .25, 0, 1, 0, true));
        var borders = new MacroFluidSolver.Boundary[]{border(0, false, true), border(0, false, true)};
        var result = MacroFluidSolver.sample(cells, links, borders, new double[2], WATER, MacroFluidSolver.MAX_STEP);
        near(0, result.volume[0], 0, "dry impeller creates no water");
        near(0, result.flow[0], 0, "dry impeller creates no remote suction");
        near(0, result.flow[1], 0, "dry impeller creates no remote pressure");
        near(0, result.torque[0], 0, "dry liquid pump is unloaded");
        near(0, result.head[0], 0, "dry liquid pump supplies no head");
    }
    private static MacroFluidSolver.Boundary border(double surface, boolean supplies, boolean receives) {
        return new MacroFluidSolver.Boundary(WATER.gravityForce(surface), supplies, receives, true);
    }
    private static void subResolutionWaterFalls() {
        var cells = new ArrayList<MacroFluidSolver.Cell>();
        double trace = .052900526521377556, supported = 52.59400544956748;
        for (int n=0;n<4;n++) cells.add(new MacroFluidSolver.Cell(100,n==3?trace:n==0?supported:0,n-.5,n+.5,0,0));
        var links = List.of(new MacroFluidSolver.Link(0,1,1,.5,0,0,true),
            new MacroFluidSolver.Link(1,2,1,1.5,0,0,true),new MacroFluidSolver.Link(2,3,1,2.5,0,0,true));
        double[] flow = new double[3];
        for (int step=0;step<32;step++) {
            var result=MacroFluidSolver.sample(cells,links,new MacroFluidSolver.Boundary[0],flow,WATER,MacroFluidSolver.MAX_STEP);
            near(trace+supported,java.util.Arrays.stream(result.volume).sum(),1e-9,"trace settling preserves every real droplet");
            for(int n=0;n<cells.size();n++) near(result.volume[n]-cells.get(n).volume(),-result.outward[n],1e-10,
                "settling flow agrees with local actual inventory");
            if(step==0) {
                near(0,result.volume[3],0,"sub-resolution liquid is not suspended above an empty pipe");
                near(trace,result.volume[2],0,"a trace crosses only one vertical face per tick");
            }
            finite(result);cells=new ArrayList<>(next(cells,result));flow=result.flow;
        }
        near(trace+supported,cells.get(0).volume(),1e-9,"water settles on the actual sealed bottom");
        for(int n=1;n<cells.size();n++) near(0,cells.get(n).volume(),0,"no floating traces remain in the vertical column");
        var gas=new IdealFluidSolver.Material(1.2,true);
        var gasCells=List.of(new MacroFluidSolver.Cell(100,0,-.5,.5,0,0),new MacroFluidSolver.Cell(100,trace,.5,1.5,0,0));
        var result=MacroFluidSolver.sample(gasCells,List.of(links.get(0)),new MacroFluidSolver.Boundary[0],new double[1],gas,MacroFluidSolver.MAX_STEP);
        near(trace,result.volume[1],0,"real gas has no liquid gravity settling");
        var closed=new MacroFluidSolver.Link(0,1,1,.5,0,0,false);
        var blocked=MacroFluidSolver.sample(gasCells,List.of(closed),new MacroFluidSolver.Boundary[0],new double[1],WATER,MacroFluidSolver.MAX_STEP);
        near(trace,blocked.volume[1],0,"trace settling does not cross a closed connection");
        var shortStep=MacroFluidSolver.sample(gasCells,List.of(links.get(0)),new MacroFluidSolver.Boundary[0],new double[1],
            WATER,MacroFluidSolver.MAX_STEP*1e-10);
        truth(Math.abs(shortStep.flow[0])<=50+1e-8,"shorter intervals cannot bypass the trace transport rate limit");
        near(trace,java.util.Arrays.stream(shortStep.volume).sum(),1e-10,"a tiny interval preserves trace inventory");
        var fullCells=List.of(new MacroFluidSolver.Cell(100,100,-.5,.5,0,0),
            new MacroFluidSolver.Cell(100,trace,.5,1.5,0,0));
        var full=MacroFluidSolver.sample(fullCells,List.of(links.get(0)),new MacroFluidSolver.Boundary[0],new double[1],
            WATER,MacroFluidSolver.MAX_STEP);
        near(trace,full.volume[1],0,"a full lower pipe supports the trace without deleting or overflowing it");
        near(100,full.volume[0],0,"settling respects the receiver's actual capacity");
        var fastCells=List.of(new MacroFluidSolver.Cell(100,0,-.5,.5,0,0),
            new MacroFluidSolver.Cell(100,50.05,.5,1.5,0,0));
        var fast=MacroFluidSolver.sample(fastCells,List.of(links.get(0)),new MacroFluidSolver.Boundary[0],new double[]{-1e6},
            WATER,MacroFluidSolver.MAX_STEP);
        truth(Math.abs(fast.flow[0])<=50+1e-8,"bulk drainage and the remaining trace share one transport rate cap");
        near(50.05,java.util.Arrays.stream(fast.volume).sum(),1e-10,"rapid bulk drainage retains its real trace");
        for(int n=0;n<fastCells.size();n++) near(fast.volume[n]-fastCells.get(n).volume(),-fast.outward[n],1e-10,
            "combined bulk and trace flow agrees with each cell's inventory");
    }
    private static void horizontalLowerLipDrains() {
        for(var start:List.of(new MacroFluidSolver.Cell(100,48.41045702988291,-.005,.005,0,0),
            new MacroFluidSolver.Cell(100,.9846625550232172,-.005,.5,0,0))) {
            var cells=List.of(start);
            var links=List.of(new MacroFluidSolver.Link(0,1,.5,0,0,0,true));
            var boundaries=new MacroFluidSolver.Boundary[]{border(-.005,false,true)};
            double[] flow={0};double delivered=0;
            for(int tick=0;tick<240;tick++) {
                var result=MacroFluidSolver.sample(cells,links,boundaries,flow,WATER,MacroFluidSolver.MAX_STEP);
                if(tick==0) truth(result.flow[0]>0,"an exposed lower lip drains water below the mouth centre");
                delivered-=result.outward[1];
                near(start.volume(),result.volume[0]+delivered,1e-8,"horizontal release never discards water");
                finite(result);cells=next(cells,result);flow=result.flow;
            }
            truth(cells.get(0).volume()<.1,"an open pipe or elbow does not keep a half-full pool");
        }
    }
    private static void openHorizontalRunsEmptyTheirActualStock() {
        for (int size : new int[]{1, 5}) for (double amount : new double[]{100, 48, .05}) {
            List<MacroFluidSolver.Cell> cells = new ArrayList<>();
            for (int n = 0; n < size; n++) cells.add(cell(amount, 0));
            var links = new ArrayList<MacroFluidSolver.Link>();
            for (int n = 0; n < size - 1; n++) links.add(new MacroFluidSolver.Link(n, n + 1, 1, 0, 0, 0, true));
            links.add(new MacroFluidSolver.Link(0, size, .5, 0, 0, 0, true));
            links.add(new MacroFluidSolver.Link(size - 1, size + 1, .5, 0, 0, 0, true));
            var bounds = new MacroFluidSolver.Boundary[]{border(-.005, false, true), border(-.005, false, true)};
            var flow = new double[links.size()]; double spent = 0;
            for (int tick = 0; tick < 1200; tick++) {
                var result = MacroFluidSolver.sample(cells, links, bounds, flow, WATER, MacroFluidSolver.MAX_STEP);
                spent -= result.outward[size] + result.outward[size + 1];
                near(amount * size, java.util.Arrays.stream(result.volume).sum() + spent, 1e-8,
                    "two exposed lower lips discharge real water without deleting any residue");
                for (int n = 0; n < size; n++) near(result.volume[n] - cells.get(n).volume(), -result.outward[n], 1e-8,
                    "each cell's lost water equals its recorded outgoing volume");
                truth(result.solves <= MacroFluidSolver.CONTACT_PASSES, "drainage stays within the same pressure-pass limit");
                if (tick == 0 && size == 1 && amount == 100)
                    truth(result.volume[0] < 100, "a completely full exposed pipe can begin draining without an old incoming stream");
                if (tick == 0 && size == 5 && amount == .05)
                    near(.1, spent, 1e-12, "only the two exit cells' original drops can leave in the first trace tick");
                finite(result); cells = next(cells, result); flow = result.flow;
            }
            for (var item : cells) near(0, item.volume(), 0, "an open horizontal run reaches exactly empty inventory");
        }
        var trace = List.of(cell(.05, 0));
        var link = List.of(new MacroFluidSolver.Link(0, 1, .5, 0, 0, 0, true));
        for (var boundary : List.of(border(-.005, false, false), border(0, false, true))) {
            var result = MacroFluidSolver.sample(trace, link, new MacroFluidSolver.Boundary[]{boundary}, new double[1], WATER, MacroFluidSolver.MAX_STEP);
            near(.05, result.volume[0], 0, "a blocked or higher outlet cannot erase a supported puddle");
        }
        var tiny = MacroFluidSolver.sample(trace, link, new MacroFluidSolver.Boundary[]{border(-.005, false, true)},
            new double[1], WATER, MacroFluidSolver.MAX_STEP * 1e-10);
        truth(Math.abs(tiny.flow[0]) <= 50 + 1e-8, "trace discharge shares the original rate cap in short intervals");
        near(.05, tiny.volume[0] - tiny.outward[1] * 1e-10, 1e-10, "small intervals conserve remaining and discharged droplets");
        var fullCell = List.of(cell(100, 0));
        var lowerStock = MacroFluidSolver.sample(fullCell, link, new MacroFluidSolver.Boundary[]{border(-.005, true, true)},
            new double[1], WATER, MacroFluidSolver.MAX_STEP);
        truth(lowerStock.volume[0] < 100, "low-pressure actual outlet stock cannot lock a full pipe against its own higher free surface");
        var supported = MacroFluidSolver.sample(fullCell, link, new MacroFluidSolver.Boundary[]{border(.005, true, true)},
            new double[1], WATER, MacroFluidSolver.MAX_STEP);
        near(100, supported.volume[0], 1e-9, "an actual outside free surface at the full pipe height still supports its inventory");
    }
    private static void horizontalDropsPassAnUnprimedPump() {
        double tail = .045369753824465786;
        var links = List.of(new MacroFluidSolver.Link(0, 1, 1, 0, 0, 0, true),
            new MacroFluidSolver.Link(1, 2, .75, 0, 0, -1, true),
            new MacroFluidSolver.Link(2, 3, .25, 0, 1, 0, true));
        for (double rpm : new double[]{0, 3.8680105412689, -64, 4096}) {
            List<MacroFluidSolver.Cell> cells = List.of(new MacroFluidSolver.Cell(100, tail, -.005, .5, 0, 0),
                cell(0, 0), cell(0, rpm));
            double[] flow = new double[links.size()]; double spent = 0;
            var boundaries = new MacroFluidSolver.Boundary[]{border(-.005, false, true)};
            for (int tick = 0; tick < 4; tick++) {
                var result = MacroFluidSolver.sample(cells, links, boundaries, flow, WATER, MacroFluidSolver.MAX_STEP);
                spent -= result.outward[3];
                near(tail, java.util.Arrays.stream(result.volume).sum() + spent, 1e-12,
                    "a dry pump path transports the actual final drop without rounding it away");
                near(0, result.head[2], 0, "a sub-resolution drop cannot prime a rotating pump");
                near(0, result.torque[2], 0, "passive dry-pump drainage creates no hydraulic pump work");
                truth(result.solves <= MacroFluidSolver.CONTACT_PASSES, "dry-pump settling adds no pressure pass");
                if (tick == 0) {
                    near(tail, result.volume[1], 0, "each original drop crosses only one interface per tick");
                    near(0, spent, 0, "the distant outlet cannot discharge the drop before it arrives");
                }
                finite(result); cells = next(cells, result); flow = result.flow;
            }
            near(tail, spent, 1e-12, "the real tail reaches the receiving exposed mouth through a dry pump");
            for (var item : cells) near(0, item.volume(), 0, "no drop is stranded before or inside the dry pump");
        }
        var drop = List.of(cell(tail, 64));
        var outlet = List.of(new MacroFluidSolver.Link(0, 1, .25, 0, 1, 0, true));
        for (var boundary : List.of(border(-.005, false, false), border(0, false, true))) {
            var result = MacroFluidSolver.sample(drop, outlet, new MacroFluidSolver.Boundary[]{boundary}, new double[1], WATER, MacroFluidSolver.MAX_STEP);
            near(tail, result.volume[0], 0, "a dry rotating pump cannot force a drop through a blocked or raised outlet");
            near(0, result.head[0], 0, "retained droplets still give no pump head");
        }
        var tiny = MacroFluidSolver.sample(drop, outlet, new MacroFluidSolver.Boundary[]{border(-.005, false, true)},
            new double[1], WATER, MacroFluidSolver.MAX_STEP * 1e-10);
        truth(Math.abs(tiny.flow[0]) <= 50 + 1e-8, "dry-pump drops obey the existing rate cap in short intervals");
        near(tail, tiny.volume[0] - tiny.outward[1] * 1e-10, 1e-12, "short passive drainage preserves real stock");
    }
    private static void exposedColumnCanRecede() {
        // A flooded elbow reaches a dry upper leg; its horizontal lower leg
        // drains into an empty tank. The unfilled upper leg cannot supply liquid, but
        // the existing higher column must still drain through the lower outlet.
        var cells = List.of(new MacroFluidSolver.Cell(100, 100, -.005, .5, 0, 0),
            cell(100, 0), new MacroFluidSolver.Cell(100, 0, .5, .505, 0, 0));
        var links = List.of(new MacroFluidSolver.Link(0, 2, 1, .5, 0, 0, true),
            new MacroFluidSolver.Link(0, 1, 1, 0, 0, 0, true),
            new MacroFluidSolver.Link(1, 3, .5, 0, 0, 0, true));
        var result = MacroFluidSolver.sample(cells, links, new MacroFluidSolver.Boundary[]{border(0, false, true)},
            new double[3], WATER, MacroFluidSolver.MAX_STEP);
        truth(result.flow[1] > .1 && result.flow[2] > .1, "an exposed higher column returns through a lower outlet");
        truth(result.volume[0] < 100, "closing a dry inlet does not lock existing liquid into the column");
        near(0, result.flow[0], 0, "the dry upper leg contributes no liquid");
        near(0, result.volume[2], 0, "returning water does not climb the dry upper leg");
        near(200, java.util.Arrays.stream(result.volume).sum() - result.outward[3], 1e-9,
            "all real water is retained or delivered to the lower tank");
        finite(result);
    }
    private static void hydrostaticAndInventory() {
        var cells = List.of(cell(50, 0));
        var links = List.of(new MacroFluidSolver.Link(0, 1, .5, 0, 0, 0, true));
        var result = MacroFluidSolver.sample(cells, links, new MacroFluidSolver.Boundary[]{border(0, true, true)},
            new double[1], WATER, MacroFluidSolver.MAX_STEP);
        near(0, result.flow[0], 1e-10, "matching free surfaces remain still");
        near(50, result.volume[0], 1e-10, "hydrostatic water is retained");
        var driven = MacroFluidSolver.sample(cells, links, new MacroFluidSolver.Boundary[]{border(1, true, true)},
            new double[1], WATER, MacroFluidSolver.MAX_STEP);
        truth(driven.volume[0] > 50, "tank head admits water");
        near(driven.volume[0] - 50, driven.outward[1] * MacroFluidSolver.MAX_STEP / RotationMath.TICK_MINUTES,
            1e-10, "external debit equals actual cell admission");
        var limited = driven.scale(.01);
        near(limited.volume[0] - 50, limited.outward[1] * MacroFluidSolver.MAX_STEP / RotationMath.TICK_MINUTES,
            1e-10, "fractional reservation limits water, not elapsed time");
        var stopped = driven.scale(0);
        near(50, stopped.volume[0], 0, "failed capability promise retains existing stock");
        near(0, stopped.outward[1], 0, "failed capability promise consumes no additional stock");
    }
    private static void closedWaterDoesNotRebound() {
        // Equal interface heights isolate momentum and capacity from gravity.
        // There is no ambient-air spring at either closed end.
        var cells = List.of(new MacroFluidSolver.Cell(100, 70, 0, 0, 0, 0),
            new MacroFluidSolver.Cell(100, 100, 0, 0, 0, 0), new MacroFluidSolver.Cell(100, 70, 0, 0, 0, 0));
        var links = List.of(new MacroFluidSolver.Link(0, 1, 1, 0, 0, 0, true),
            new MacroFluidSolver.Link(1, 2, 1, 0, 0, 0, true));
        double[] velocity = {2, 2};
        for (int step = 0; step < 256; step++) {
            var result = MacroFluidSolver.sample(cells, links, new MacroFluidSolver.Boundary[0], velocity, WATER, MacroFluidSolver.MAX_STEP);
            near(240, java.util.Arrays.stream(result.volume).sum(), 1e-8, "closed pipe retains all water");
            for (double flow : result.flow) truth(flow >= -1e-10, "unfilled space does not store compression work or reverse water");
            finite(result); cells = next(cells, result); velocity = result.flow;
        }
        near(100, cells.get(2).volume(), 1e-8, "water uses the entire receiver space without a gas reserve");
        near(0, velocity[0], 1e-10, "capacity stops the flow at the closed end");
    }
    private static void randomClosedNetworks() {
        var random = new Random(1807);
        for (int scene = 0; scene < 12; scene++) {
            var cells = new ArrayList<MacroFluidSolver.Cell>(); var links = new ArrayList<MacroFluidSolver.Link>();
            int count = 12;
            for (int n = 0; n < count; n++) cells.add(cell(n % 3 == 0 ? 100 : 10 + random.nextDouble() * 80, 0));
            for (int n = 0; n < count; n++) links.add(new MacroFluidSolver.Link(n, (n + 1) % count, 1, 0, 0, 0, true));
            for (int n = 0; n < count / 2; n++) links.add(new MacroFluidSolver.Link(n, n + count / 2, 1, 0, 0, 0, true));
            double[] velocity = new double[links.size()];
            for (int e = 0; e < velocity.length; e++) velocity[e] = random.nextDouble() * 64 - 32;
            double total = cells.stream().mapToDouble(MacroFluidSolver.Cell::volume).sum();
            for (int step = 0; step < 128; step++) {
                var result = MacroFluidSolver.sample(cells, links, new MacroFluidSolver.Boundary[0], velocity, WATER, MacroFluidSolver.MAX_STEP);
                near(total, java.util.Arrays.stream(result.volume).sum(), 1e-7, "branched mixed pipe preserves water");
                for (double amount : result.volume) truth(amount >= 0 && amount <= 100 + 1e-8, "cell cannot overdraw or overflow");
                finite(result); cells = new ArrayList<>(next(cells, result)); velocity = result.flow;
            }
        }
    }
    private static void blockedReceipt() {
        var cells = List.of(cell(100, 64));
        var links = List.of(new MacroFluidSolver.Link(0, 1, .25, 0, -1, 0, true),
            new MacroFluidSolver.Link(0, 2, .25, 0, 1, 0, true));
        var borders = new MacroFluidSolver.Boundary[]{border(0, true, true), border(0, false, false)};
        var result = MacroFluidSolver.sample(cells, links, borders, new double[2], WATER, MacroFluidSolver.MAX_STEP);
        near(100, result.volume[0], 1e-10, "full receiver retains flooded pipe stock");
        near(0, result.flow[1], 0, "full receiver never accepts phantom water");
        near(0, result.torque[0], 1e-10, "dead-head ideal pump has zero transferred power");
        truth(result.head[0] > 0, "a wet pump still provides head at zero flow");
        finite(result);
    }
    private static void exhaustedBudget() {
        var cells = List.of(cell(70, 64), cell(90, 0));
        var links = List.of(new MacroFluidSolver.Link(0, 1, 1, 0, 1, 0, true));
        var result = MacroFluidSolver.sample(cells, links, new MacroFluidSolver.Boundary[0], new double[]{32}, WATER,
            RotationMath.TICK_MINUTES, 0);
        near(70, result.volume[0], 0, "exhausted CPU budget retains source water");
        near(90, result.volume[1], 0, "exhausted CPU budget retains receiver water");
        near(0, result.torque[0], 0, "deferred hydraulic work applies no unaccounted torque");
        near(0, result.solves, 0, "exhausted budget does not start another pressure solve");
    }
    private static void reversingPumpBrakesIncomingFlow() {
        var cells = List.of(new MacroFluidSolver.Cell(100, 100, -.005, .005, -64, 32));
        var links = List.of(new MacroFluidSolver.Link(0, 1, .25, 0, -1, 0, true),
            new MacroFluidSolver.Link(0, 2, .25, 0, 1, 0, true));
        var borders = new MacroFluidSolver.Boundary[]{border(0, true, true), border(0, true, true)};
        var result = MacroFluidSolver.sample(cells, links, borders, new double[]{-32, 32}, WATER, MacroFluidSolver.MAX_STEP);
        truth(result.flow[1] < 0, "reversing a wet pump reverses the previously forward stream");
        near(100, result.volume[0], 1e-8, "reversal retains actual primed liquid");
        near(0, result.flow[0] + result.flow[1], 1e-8, "reversal debits and credits the same real amount");
        finite(result);
    }
    private static void waterBackdrivesStoppedRotor() {
        var cells = List.of(new MacroFluidSolver.Cell(100, 100, -.005, .005, 0, 32));
        var links = List.of(new MacroFluidSolver.Link(0, 1, .25, 0, -1, 0, true),
            new MacroFluidSolver.Link(0, 2, .25, 0, 1, 0, true));
        var borders = new MacroFluidSolver.Boundary[]{border(0, true, true), border(0, true, true)};
        var result = MacroFluidSolver.sample(cells, links, borders, new double[]{-32, 32}, WATER, MacroFluidSolver.MAX_STEP);
        truth(result.flow[1] > 0 && result.torque[0] > 0, "moving real water backdrives a stopped rotor");
        near(100, result.volume[0], 1e-8, "backdrive retains the primed liquid");
        finite(result);
    }
    private static void extremeSpeedHasBoundedFlow() {
        var cells = List.of(new MacroFluidSolver.Cell(100, 100, -.005, .005, 4096, 0));
        var links = List.of(new MacroFluidSolver.Link(0, 1, .25, 0, -1, 0, true),
            new MacroFluidSolver.Link(0, 2, .25, 0, 1, 0, true));
        var borders = new MacroFluidSolver.Boundary[]{new MacroFluidSolver.Boundary(0, true, true, true, 70), border(0, true, true)};
        for (double minutes : new double[]{MacroFluidSolver.MAX_STEP, MacroFluidSolver.MAX_STEP * .01}) {
            var result = MacroFluidSolver.sample(cells, links, borders, new double[2], WATER, minutes);
            truth(result.flow[1] > 0 && result.flow[1] <= 50 + 1e-8,
                "extreme rotor speed remains bounded even during a short mechanical interval");
            near(100, result.volume[0], 1e-8, "bounded through-flow preserves primed stock");
            near(0, result.outward[1] + result.outward[2], 1e-8,
                "high-speed source debit equals receiver credit");
            near(1, result.solves, 0, "inventory above the existing rate cap does not falsely trigger a starved-source pressure pass");
            finite(result);
        }
    }
    private static void wetSeriesPumpsKeepTheirPrime() {
        var cells = List.of(cell(10, 128), cell(10, 64));
        var links = List.of(new MacroFluidSolver.Link(0, 2, .25, 0, -1, 0, true),
            new MacroFluidSolver.Link(0, 1, .5, 0, 1, -1, true),
            new MacroFluidSolver.Link(1, 3, .25, 0, 1, 0, true));
        var borders = new MacroFluidSolver.Boundary[]{border(0, true, true), border(0, true, true)};
        double[] velocity = new double[3]; boolean backdrive = false;
        for (int tick = 0; tick < 100; tick++) {
            var result = MacroFluidSolver.sample(cells, links, borders, velocity, WATER, MacroFluidSolver.MAX_STEP);
            near(10, result.volume[0], 1e-8, "wet first impeller retains its real prime");
            near(10, result.volume[1], 1e-8, "wet series impeller retains its real prime");
            truth(result.flow[1] > 10, "a wet series pair keeps transporting water");
            near(result.flow[1], result.flow[2], 1e-8, "series impellers have the same conserved through-flow");
            backdrive |= result.torque[1] > 0;
            finite(result); velocity = result.flow;
            cells = List.of(new MacroFluidSolver.Cell(100, result.volume[0], -.005, .005,
                128, velocity[1]),
                new MacroFluidSolver.Cell(100, result.volume[1], -.005, .005,
                    64, velocity[2]));
        }
        truth(backdrive, "faster water returns shaft power to the slower series impeller");
    }
    private static void blindPipeCanFill() {
        var cells = List.of(cell(0, 0));
        var links = List.of(new MacroFluidSolver.Link(0, 1, .5, 0, 0, 0, true));
        var borders = new MacroFluidSolver.Boundary[]{new MacroFluidSolver.Boundary(WATER.gravityForce(1), true, true, false)};
        double[] velocity = {0}; double debit = 0;
        for (int tick = 0; tick < 128; tick++) {
            var result = MacroFluidSolver.sample(cells, links, borders, velocity, WATER, MacroFluidSolver.MAX_STEP);
            debit += result.outward[1];
            near(debit, result.volume[0], 1e-8, "submerged source debit equals real water in a blind pipe");
            truth(result.volume[0] >= 0 && result.volume[0] <= 100 + 1e-8, "blind pipe respects actual fluid capacity");
            finite(result); cells = next(cells, result); velocity = result.flow;
        }
        near(100, cells.get(0).volume(), 1e-8, "a submerged source can replace all unfilled space");
        near(0, velocity[0], 1e-10, "full blind pipe does not continue withdrawing water");
    }
    private static void actualGasRetainsInventoryAndPumpWork() {
        var gas = new IdealFluidSolver.Material(1.2, true);
        var links = List.of(new MacroFluidSolver.Link(0, 1, .25, 1000, -1, 0, true),
            new MacroFluidSolver.Link(0, 2, .25, -1000, 1, 0, true));
        var borders = new MacroFluidSolver.Boundary[]{new MacroFluidSolver.Boundary(0, true, true, true),
            new MacroFluidSolver.Boundary(0, true, true, true)};
        var wet = MacroFluidSolver.sample(List.of(cell(100, 64)), links, borders, new double[2], gas, MacroFluidSolver.MAX_STEP);
        truth(wet.flow[1] > 0 && wet.head[0] > 0 && wet.torque[0] < 0, "a real gas inventory receives pump work and loads the rotor");
        near(100, wet.volume[0], 1e-8, "actual gas is retained as fluid inventory");
        near(0, wet.outward[1] + wet.outward[2], 1e-8, "actual gas source debit equals receiver credit");
        finite(wet);
        var stopped = MacroFluidSolver.sample(List.of(cell(50, 0)), links, borders, new double[2], gas, MacroFluidSolver.MAX_STEP);
        near(0, stopped.flow[0], 1e-10, "height alone does not drive the gas transport approximation");
        near(0, stopped.flow[1], 1e-10, "actual gas ignores liquid mouth height");
        near(50, stopped.volume[0], 1e-10, "a stopped gas inventory does not disappear");
        finite(stopped);
        var dry = MacroFluidSolver.sample(List.of(cell(0, 64)), links,
            new MacroFluidSolver.Boundary[]{new MacroFluidSolver.Boundary(0, false, true, true),
                new MacroFluidSolver.Boundary(0, false, true, true)}, new double[2], gas, MacroFluidSolver.MAX_STEP);
        near(0, dry.head[0], 0, "a dry gas pump cannot act on nonexistent fluid");
        near(0, dry.volume[0], 0, "unfilled space is not fabricated gas inventory");
        finite(dry);
    }
    private static void finite(MacroFluidSolver.Result result) {
        truth(result.solves <= MacroFluidSolver.CONTACT_PASSES, "contact handling has a fixed solve budget");
        for (double[] values : new double[][]{result.volume, result.flow, result.outward, result.torque, result.head})
            for (double value : values) truth(Double.isFinite(value), "macro state remains finite");
    }
    private static void truth(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static void near(double expected, double actual, double tolerance, String label) {
        checks++; if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance)
            throw new AssertionError(label + ": " + actual + " / " + expected);
    }
}
