package com.diliupengyun.differential_create;

import java.util.Arrays;
import java.util.List;
import com.diliupengyun.differential_create.kinetics.*;

/** Physical air-pocket, free-plug, dry-pump and work expectations independent of Minecraft. */
public final class AirLiquidRegression {
    private static int checks;
    private static final IdealFluidSolver.Material WATER = new IdealFluidSolver.Material(1000, false);
    public static void run() {
        compression(); subUlpCompression(); bubbleReliefAtRoundoff(); newbornInletVelocity(); newbornJunctionVelocity(); nanofilmAirContact(); resolvedReversalEvent(); shortPlugIntoWetInventory(); microscopicPacketReduction(); detachedPlugs(); dryReturnPath(); trappedAir(); sealedAirAcrossJunction(); resolvedJunctionContact(); dryAirOpposesJunctionPressure(); departingJetReleasesJunction(); adjacentResolvedGasLabels(); dryPump(); initialFilling(); closedSourceVoid(); movingPersistence();
        smallPocket(); microscopicLiquid(); thinReversalAtPipeMouth(); fastMicroscopicTail(); tinyBranchedTail(); energeticMicroscopicFork(); energeticMicroscopicReceipt(); localMicroscopicReceipt(); tinyTailAtCounterflowFork(); microscopicCounterflowContact(); subClockContactReversal(); tinyWetJunctionTail(); tinyTailJoinsWetArm(); fullColumnPassesTinyTail(); resolvedNearMouth(); wetPump(); closedValve(); reservoirCompressesAir(); ventedJetCrossesPipeMouths(); roundoffMouth(); roundoffContact(); storedMicroscopicAir(); compressedAirAtNearMouth(); staticHeadOpensDryBranch();
        nanometreJunctionHandoff(); localJunctionPressure(); disjointMicroscopicPackets(); microscopicMouthAirContact();
        stiffPressureRecovery(); millimetreAirResolution(); millimetreMouthAirResolution(); independentGasPressureScales();
        System.out.println("Air/liquid regression: " + checks + " checks passed");
    }
    public static void main(String[] args) { run(); }
    private static void compression() {
        double volume = 1e-4, energy = PipeAir.internalEnergy(volume, PipeAir.ATMOSPHERE);
        for (double ratio : new double[]{1e-20, 1e-17, .1, .5, .999999999, 1, 1.000000001, 2, 10}) {
            double next = PipeAir.compressedEnergy(volume, energy, volume * ratio);
            near(energy * Math.pow(ratio, 1 - PipeAir.GAMMA), next, 1e-12 * Math.max(1, next), "adiabatic internal energy");
            double pressure = PipeAir.ATMOSPHERE * Math.pow(ratio, -PipeAir.GAMMA);
            near(pressure, PipeAir.pressure(volume * ratio, next), 1e-12 * Math.max(1, pressure), "adiabatic absolute pressure");
            double work = PipeAir.averagePressure(volume, energy, volume * ratio) * volume * (1 - ratio);
            near(next - energy, work, 1e-12 * Math.max(1, next), "exact pressure work including near-equal volumes");
        }
    }
    private static void subUlpCompression() {
        double volume = 1e-4, internal = PipeAir.internalEnergy(volume, PipeAir.ATMOSPHERE), change = Math.ulp(volume) / 8;
        check(volume + change == volume, "test displacement is below absolute-volume resolution");
        double increment = PipeAir.averageGaugePressureChange(volume, internal, change) - PipeAir.gaugePressure(volume, internal);
        near(-PipeAir.ATMOSPHERE * PipeAir.GAMMA * change / (2 * volume), increment, 1e-27,
            "sub-ULP displacement still resolves the gas compliance force");
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        var motion = new PipeMotion[]{PipeMotion.uniform(PipeContents.of(.5, .5 + 1e-12), 1)};
        var air = emptyAir(1);
        for (int tick = 0; tick < 20; tick++) {
            var result = step(tubes, motion, air, new boolean[2], new boolean[2], new double[2], new double[1], IdealFluidSolver.MAX_STEP);
            check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP,
                "an unresolved film between large air regions takes the complete implicit step");
            motion = result.motion(); air = result.air();
        }
    }
    private static void bubbleReliefAtRoundoff() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 4, 0, 0));
        var liquid = PipeContents.of(0, .24444513349050462);
        var motion = new PipeMotion[]{PipeMotion.uniform(liquid, 18.453156004368896)};
        double pressure = 103011.09375, volume = PipeAir.volume(4, 1 - liquid.fraction());
        for (int ulps : new int[]{-8, -2, -1, 0, 1, 2, 8}) {
            double internal = PipeAir.internalEnergy(volume, pressure);
            for (int i = 0; i < Math.abs(ulps); i++) internal = ulps < 0 ? Math.nextDown(internal) : Math.nextUp(internal);
            var air = new PipeAir[]{new PipeAir(List.of(new PipeAir.Portion(liquid.end(0), 1, PipeAir.DENSITY * volume, internal)))};
            for (double duration : new double[]{1e-22, 1e-20, 1e-18, 1e-17, 1e-15, 1e-12, 1e-10}) {
                var result = AirLiquidSolver.sample(tubes, tubes, 2, motion, air,
                    new boolean[]{true, true}, new boolean[]{true, true}, new boolean[]{true, true},
                    new double[]{.5137629697997391, IdealFluidSolver.pressureForce(pressure - PipeAir.ATMOSPHERE)},
                    new boolean[2], new double[]{Double.POSITIVE_INFINITY, pressure}, new double[1], WATER, duration);
                check(result.converged() && result.allowedMinutes() == duration,
                    "sub-ULP bubble relief keeps its physical pressure and completes the detached step: "
                        + ulps + " ULP, " + duration + " / " + result.allowedMinutes() + ", " + result.converged());
                check(result.motion()[0].average() > 18 && result.motion()[0].average() < 19,
                    "opening a bubble outlet does not give the liquid an artificial pressure impulse");
            }
        }
    }
    private static void newbornInletVelocity() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        double pressure = 2000;
        // Bernoulli speed from a large resting reservoir into atmospheric air:
        // 2 m/s at 2000 Pa, or 10 mB/tick with the model's pipe area.
        for (double duration : new double[]{1e-10, 1e-7, IdealFluidSolver.MAX_STEP}) {
            var result = step(tubes, new PipeMotion[]{PipeMotion.uniform(PipeContents.EMPTY, 0)}, emptyAir(1),
                new boolean[]{true, true}, new boolean[]{true, false},
                new double[]{IdealFluidSolver.pressureForce(pressure), 0}, new double[1], duration);
            check(result.converged() && result.allowedMinutes() == duration, "pressure establishes the actual newborn inlet flow");
            near(10, result.motion()[0].average(), 1e-9, "new liquid has the Bernoulli inlet velocity");
            near(10 * duration / (RotationMath.TICK_MINUTES * PipeFilling.MB_PER_METRE),
                result.motion()[0].contents().fraction(), 1e-11, "admitted volume uses the same real inlet velocity throughout its birth step");
        }
    }
    private static void newbornJunctionVelocity() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(1, 2, 1, 0, 0));
        var motion = new PipeMotion[]{PipeMotion.uniform(PipeContents.FULL, 10), PipeMotion.uniform(PipeContents.EMPTY, 0)};
        var air = emptyAir(2);
        var result = step(tubes, motion, air, new boolean[]{true, false, true}, new boolean[]{true, false, false},
            new double[3], new double[2], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "incoming water admits a new junction parcel");
        check(result.motion()[1].contents().fraction() > 0, "the junction transfers actual incoming water");
        near(result.motion()[0].flow()[0], result.motion()[1].flow()[0], 1e-10,
            "a newborn junction parcel retains instantaneous incompressible velocity continuity");
        near(energy(tubes, motion, air), energy(tubes, result.motion(), result.air()) + result.projectionLoss() + result.airBoundaryOutput(),
            1e-10, "junction admission accounts for the real new parcel's kinetic energy");
    }
    private static void nanofilmAirContact() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        var liquid = PipeContents.of(.5, .5 + 5e-12);
        var motion = new PipeMotion[]{PipeMotion.uniform(liquid, .1)};
        var air = new PipeAir[]{PipeAir.UNINITIALIZED.fit(liquid, 1)};
        double initial = energy(tubes, motion, air), mass = air[0].portions().stream().mapToDouble(PipeAir.Portion::mass).sum();
        var result = step(tubes, motion, air, new boolean[2], new boolean[2], new double[2], new double[1], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "unresolved passive-air film contact completes the requested step");
        near(.1, result.motion()[0].flow()[0], 1e-12, "a nonsealing nanofilm retains its actual independent velocity");
        near(liquid.fraction(), result.motion()[0].contents().fraction(), 2e-16, "air contact does not delete the microscopic liquid inventory");
        near(mass, result.air()[0].portions().stream().mapToDouble(PipeAir.Portion::mass).sum(), 1e-18, "air contact retains all closed gas mass");
        near(initial, energy(tubes, result.motion(), result.air()) + result.projectionLoss(), 1e-12, "air contact retains gas and liquid energy");
        var compressed = new PipeAir[]{new PipeAir(air[0].portions().stream().map(p -> new PipeAir.Portion(p.start(), p.end(), p.mass(), 4 * p.energy())).toList())};
        result = step(tubes, motion, compressed, new boolean[2], new boolean[2], new double[2], new double[1], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP,
            "equal compressed pressures do not impose an unresolved nanofilm oscillator");
        near(.1, result.motion()[0].flow()[0], 1e-12, "uniform compression retains the actual nanofilm velocity");
        near(energy(tubes, motion, compressed), energy(tubes, result.motion(), result.air()) + result.projectionLoss(), 1e-12,
            "joining equal-pressure regions retains their resolved compression energy");
        var unequal = new PipeAir[]{new PipeAir(air[0].portions().stream().map(p -> new PipeAir.Portion(p.start(), p.end(), p.mass(),
            (p.start() == 0 ? 4 : 1) * p.energy())).toList())};
        result = step(tubes, motion, unequal, new boolean[2], new boolean[2], new double[2], new double[1], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP,
            "a sub-resolution film consistently communicates air even across a pressure difference");
        near(.1, result.motion()[0].flow()[0], 1e-12, "pressure does not turn an unresolved film back into a sealed piston");
        near(energy(tubes, motion, unequal), energy(tubes, result.motion(), result.air()) + result.projectionLoss(), 1e-12,
            "nonsealing film retains the summed gas internal energy");
        near(unequal[0].portions().stream().mapToDouble(PipeAir.Portion::mass).sum(),
            result.air()[0].portions().stream().mapToDouble(PipeAir.Portion::mass).sum(), 1e-18,
            "nonsealing film retains all closed gas mass");
        var thicker = new PipeMotion[]{PipeMotion.uniform(PipeContents.of(.5, .5 + .002), .1)};
        result = step(tubes, thicker, emptyAir(1), new boolean[2], new boolean[2], new double[2], new double[1], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP && result.motion()[0].flow()[0] < 0,
            "a resolved liquid thickness remains a sealed gas piston");
    }
    private static void resolvedReversalEvent() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        double start = .001, midpoint = -2;
        var prediction = PipeFilling.predictResolved(tubes, 2, new PipeContents[]{PipeContents.of(start, start + 1e-10)},
            new double[]{-80}, new double[]{midpoint}, new boolean[]{true, true}, new double[2], WATER, IdealFluidSolver.MAX_STEP);
        near(start * PipeFilling.MB_PER_METRE * RotationMath.TICK_MINUTES / -midpoint, prediction.allowedMinutes(), 1e-18,
            "stiff reversal locates the mouth from actual implicit displacement, not a fictitious acceleration profile");
    }
    private static void shortPlugIntoWetInventory() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, .5, 0, 0));
        var liquid = PipeContents.of(.9999991, 1);
        var motion = new PipeMotion[]{PipeMotion.uniform(liquid, 100)};
        double duration = 1e-9;
        var result = AirLiquidSolver.sample(tubes, tubes, 2, motion, emptyAir(1),
            new boolean[]{false, true}, new boolean[]{false, true}, new boolean[]{false, true},
            new double[]{0, IdealFluidSolver.pressureForce(2000)}, new boolean[2],
            new double[]{Double.POSITIVE_INFINITY, PipeAir.ATMOSPHERE + 2000}, new double[1], WATER, duration);
        double freeTransit = liquid.fraction() * .5 * PipeFilling.MB_PER_METRE * RotationMath.TICK_MINUTES / 100;
        check(result.converged() && result.allowedMinutes() >= .99 * freeTransit,
            "a short entering plug cannot turn the wet receiving inventory into a vacuum and accelerate its transit");
        near(liquid.fraction(), result.motion()[0].contents().fraction(), 1e-18,
            "a detached receiving-mouth event retains the actual water until commit");
    }
    private static void energeticMicroscopicReceipt() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        var liquid = PipeContents.of(1 - 1e-13, 1);
        var motion = new PipeMotion[]{PipeMotion.uniform(liquid, 20000)};
        var air = emptyAir(1);
        var result = step(tubes, motion, air, new boolean[]{false, true}, new boolean[2], new double[2], new double[1], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "a real inventory completes an energetic microscopic receipt");
        check(result.motion()[0].contents() == PipeContents.EMPTY && result.projectionLoss() > 1e-11,
            "the receiving inventory absorbs and records the complete parcel's kinetic energy");
        near(liquid.fraction() * PipeFilling.MB_PER_METRE,
            -result.waterOutward()[1] * IdealFluidSolver.MAX_STEP / RotationMath.TICK_MINUTES, 1e-18,
            "microscopic receipt delivers all actual water to the inventory");
        near(energy(tubes, motion, air), energy(tubes, result.motion(), result.air()) + result.projectionLoss() + result.airBoundaryOutput(), 1e-12,
            "energetic receipt retains the strict water and air work accounting");
    }
    private static void localMicroscopicReceipt() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(2, 3, .5, 26, 26));
        var liquid = PipeContents.of(1 - 4e-10, 1);
        var motion = new PipeMotion[]{PipeMotion.uniform(PipeContents.FULL, 0), PipeMotion.uniform(liquid, 2)};
        var fitted = PipeAir.UNINITIALIZED.fit(liquid, .5);
        var compressed = new PipeAir(fitted.portions().stream().map(p -> new PipeAir.Portion(p.start(), p.end(), p.mass(),
            PipeAir.internalEnergy(PipeAir.volume(.5, p.end() - p.start()), PipeAir.ATMOSPHERE + 2000))).toList());
        var air = new PipeAir[]{PipeAir.UNINITIALIZED, compressed};
        boolean[] boundary = {true, false, false, true};
        double duration = IdealFluidSolver.MAX_STEP;
        for (double remotePressure : new double[]{250000, 1e8}) {
            double[] datum = {IdealFluidSolver.pressureForce(remotePressure), 0, 0, IdealFluidSolver.pressureForce(2000)};
            var result = AirLiquidSolver.sample(tubes, tubes, 4, motion, air, boundary, boundary, boundary, datum, new boolean[4],
                new double[]{PipeAir.ATMOSPHERE + remotePressure, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, PipeAir.ATMOSPHERE + 2000},
                new double[2], WATER, duration);
            check(result.converged() && result.allowedMinutes() == duration,
                "remote pressure across dry space cannot keep a local nanofilm receipt on a microscopic clock");
            double before = .5 * liquid.fraction() * PipeFilling.MB_PER_METRE;
            double after = .5 * result.motion()[1].contents().fraction() * PipeFilling.MB_PER_METRE;
            double delivered = -result.waterOutward()[3] * duration / RotationMath.TICK_MINUTES;
            near(before - after, delivered, 8 * Math.ulp(1d) * .5 * PipeFilling.MB_PER_METRE,
                "local receipt preserves the actual inventory within its stored endpoint roundoff, including pressure-driven readmission");
            double potentialPerMb = WATER.gravityForce(26) * RotationMath.TICK_MINUTES;
            double work = 0; for (int n = 0; n < datum.length; n++) work += datum[n] * result.waterOutward()[n] * duration;
            near(energy(tubes, motion, air) + before * potentialPerMb + work,
                energy(tubes, result.motion(), result.air()) + (after + delivered) * potentialPerMb
                    + result.projectionLoss() + result.airBoundaryOutput(), 1e-11,
                "nanofilm receipt accounts for its original gravitational energy and the receiving inventory's potential");
        }
    }
    private static void subClockContactReversal() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(0, 2, 1, 0, 0), new PipeFilling.Tube(0, 3, 1, 0, 0));
        var motion = new PipeMotion[]{PipeMotion.uniform(PipeContents.of(0, 3e-18), 2),
            PipeMotion.uniform(PipeContents.of(0, 6e-13), 2), PipeMotion.uniform(PipeContents.EMPTY, 0)};
        var air = emptyAir(3); double initial = energy(tubes, motion, air);
        var result = step(tubes, motion, air, new boolean[]{false, true, true, true}, new boolean[4], new double[4], new double[3], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP,
            "a sub-clock wet-mouth reversal resolves its actual contact instead of shrinking time indefinitely");
        near(6e-13 + 3e-18, Arrays.stream(result.motion()).mapToDouble(m -> m.contents().fraction()).sum(), 1e-18,
            "sub-clock reversal retains the microscopic parcel");
        near(initial, energy(tubes, result.motion(), result.air()) + result.projectionLoss() + result.airBoundaryOutput(), 1e-12,
            "sub-clock pressure impulse and receiving contact account for kinetic energy");
    }
    private static void microscopicPacketReduction() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        var liquid = PipeContents.of(.4999999, .4999999 + 5e-14, .5, .5 + 5e-14, .5000001, .5000001 + 5e-14);
        var motion = new PipeMotion[]{new PipeMotion(liquid, new double[]{.02, -.01, .03})};
        var air = new PipeAir[]{PipeAir.UNINITIALIZED.fit(liquid, 1)};
        double initial = energy(tubes, motion, air), mass = air[0].portions().stream().mapToDouble(PipeAir.Portion::mass).sum();
        var result = step(tubes, motion, air, new boolean[2], new boolean[2], new double[2], new double[1], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "compact unresolved film train takes a complete implicit step");
        check(result.motion()[0].contents().intervals() == 1, "sub-resolution films reduce to one actual parcel");
        near(liquid.fraction(), result.motion()[0].contents().fraction(), 2e-16, "packet reduction retains actual water volume");
        near(mass, result.air()[0].portions().stream().mapToDouble(PipeAir.Portion::mass).sum(), 1e-18, "packet reduction retains all closed air mass");
        near(initial, energy(tubes, result.motion(), result.air()) + result.projectionLoss() + result.airBoundaryOutput(), 1e-12,
            "packet reduction records its collision energy instead of deleting gas inventory");
        var pockets = new java.util.ArrayList<PipeAir.Portion>();
        for (var p : air[0].portions()) pockets.add(p.start() > .4999999 && p.end() <= .5000001
            ? new PipeAir.Portion(p.start(), p.end(), p.mass(), 4 * p.energy()) : p);
        result = step(tubes, motion, new PipeAir[]{new PipeAir(pockets)}, new boolean[2], new boolean[2], new double[2], new double[1], 1e-16);
        check(result.motion()[0].contents().intervals() == 3, "an energetic gas imbalance prevents optional packet reduction");
    }
    private static void microscopicMouthAirContact() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(0, 2, 1, 0, 0),
            new PipeFilling.Tube(0, 3, 1, 0, 0));
        var liquid = PipeContents.of(0, 2e-7);
        PipeMotion[] motion = {PipeMotion.uniform(liquid, 0), PipeMotion.uniform(PipeContents.EMPTY, 0),
            PipeMotion.uniform(PipeContents.EMPTY, 0)};
        var air = emptyAir(3);
        for (int e = 0; e < air.length; e++) air[e] = air[e].fit(motion[e].contents(), 1);
        air[0] = new PipeAir(air[0].portions().stream().map(p -> new PipeAir.Portion(p.start(), p.end(), p.mass(), 4 * p.energy())).toList());
        double initial = energy(tubes, motion, air);
        double mass = Arrays.stream(air).flatMap(a -> a.portions().stream()).mapToDouble(PipeAir.Portion::mass).sum();
        var result = step(tubes, motion, air, new boolean[4], new boolean[4], new double[4], new double[3], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "a microscopic mouth film communicates with adjoining dry arms");
        double expectedPressure = PipeAir.ATMOSPHERE * (6 - 4 * liquid.fraction()) / (3 - liquid.fraction());
        for (int e = 0; e < air.length; e++) for (var p : result.air()[e].portions())
            near(expectedPressure, PipeAir.pressure(PipeAir.volume(1, p.end() - p.start()), p.energy()), 1e-7,
                "one common pressure uses the retained gas internal energy and actual empty volume");
        near(liquid.fraction(), Arrays.stream(result.motion()).mapToDouble(m -> m.contents().fraction()).sum(), 1e-16,
            "microscopic mouth air passage retains all actual water");
        near(mass, Arrays.stream(result.air()).flatMap(a -> a.portions().stream()).mapToDouble(PipeAir.Portion::mass).sum(), 1e-18,
            "microscopic mouth air passage retains the closed gas inventory");
        near(initial, energy(tubes, result.motion(), result.air()) + result.projectionLoss(), 1e-12,
            "microscopic mouth air passage preserves the total energy ledger");
    }

    private static void disjointMicroscopicPackets() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        var liquid = PipeContents.of(.1, .1 + 1e-7, .100002, .100002 + 1e-7,
            .7, .7 + 1e-7, .700002, .700002 + 1e-7);
        var motion = new PipeMotion[]{new PipeMotion(liquid, new double[]{2, -2, 3, -3})};
        var air = new PipeAir[]{PipeAir.UNINITIALIZED.fit(liquid, 1)};
        double initial = energy(tubes, motion, air), gasMass = air[0].portions().stream().mapToDouble(PipeAir.Portion::mass).sum();
        var result = step(tubes, motion, air, new boolean[2], new boolean[2], new double[2], new double[1], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP,
            "all disjoint microscopic packets complete one requested interval");
        check(result.motion()[0].contents().intervals() == 2, "each compact train becomes one parcel without merging distant trains");
        near(liquid.fraction(), result.motion()[0].contents().fraction(), 3e-16, "batched reduction retains water inventory");
        near(liquid.moment(), result.motion()[0].contents().moment(), 3e-16, "batched reduction retains the liquid centre of mass");
        for (double q : result.motion()[0].flow()) near(0, q, 1e-8, "each opposed packet retains its zero total momentum");
        near(gasMass, result.air()[0].portions().stream().mapToDouble(PipeAir.Portion::mass).sum(), 1e-18,
            "batched reduction retains the closed gas mass");
        near(initial, energy(tubes, result.motion(), result.air()) + result.projectionLoss(), 1e-12,
            "batched reduction accounts for all kinetic loss and gas internal energy");
        check(result.projectionLoss() > 1e-11, "resolved collision energy is recorded rather than silently discarded");
        var resolved = new PipeMotion[]{new PipeMotion(PipeContents.of(.2, .202, .204, .206), new double[]{0, 0})};
        result = step(tubes, resolved, emptyAir(1), new boolean[2], new boolean[2], new double[2], new double[1], 1e-10);
        check(result.motion()[0].contents().intervals() == 2, "resolved liquid thickness prevents packet reduction");
    }

    private static AirLiquidSolver.Result step(List<PipeFilling.Tube> tubes, PipeMotion[] motion, PipeAir[] air,
                                               boolean[] boundary, boolean[] supplies, double[] datum, double[] rpm, double dt) {
        boolean[] receives = boundary.clone(); double[] ceiling = new double[boundary.length]; Arrays.fill(ceiling, Double.POSITIVE_INFINITY);
        for (int n = 0; n < ceiling.length; n++) if (boundary[n]) ceiling[n] = PipeAir.ATMOSPHERE;
        return AirLiquidSolver.sample(tubes, tubes, boundary.length, motion, air, boundary, supplies, receives,
            datum, boundary, ceiling, rpm, WATER, dt);
    }
    private static PipeAir[] emptyAir(int count) { PipeAir[] air = new PipeAir[count]; Arrays.fill(air, PipeAir.UNINITIALIZED); return air; }
    private static void detachedPlugs() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 1), new PipeFilling.Tube(1, 2, 1, 1, 1), new PipeFilling.Tube(2, 3, 1, 1, 1));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.of(.1, .9), .1), PipeMotion.uniform(PipeContents.EMPTY, 0), PipeMotion.uniform(PipeContents.of(.2, .4), .1)};
        var result = step(tubes, motion, emptyAir(3), new boolean[]{true, true, false, true}, new boolean[4], new double[4], new double[3], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "vented plug step converges");
        check(result.motion()[0].flow()[0] < 0, "vertical plug falls back under gravity");
        near(.1, result.motion()[2].flow()[0], 1e-12, "detached horizontal plug keeps its independent velocity");
        near(.2, result.motion()[2].contents().fraction(), 1e-14, "horizontal plug keeps its inventory");
    }
    private static double energy(List<PipeFilling.Tube> tubes, PipeMotion[] motion, PipeAir[] air) {
        double energy = 0;
        for (int e = 0; e < tubes.size(); e++) {
            var tube = tubes.get(e); var liquid = motion[e].contents();
            for (int i = 0; i < liquid.intervals(); i++) energy += .5 * WATER.inertia(tube.length()) * (liquid.end(i) - liquid.start(i)) * motion[e].flow()[i] * motion[e].flow()[i];
            for (var portion : air[e].fit(liquid, tube.length()).portions())
                energy += PipeAir.availableEnergy(PipeAir.volume(tube.length(), portion.end() - portion.start()), portion.mass(), portion.energy());
        }
        return energy;
    }
    private static void dryReturnPath() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(1, 2, 1, 0, 0),
            new PipeFilling.Tube(2, 3, 1, 0, 0), new PipeFilling.Tube(3, 0, 1, 0, 0));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.of(.2, .4), .1), PipeMotion.uniform(PipeContents.EMPTY, 0),
            PipeMotion.uniform(PipeContents.EMPTY, 0), PipeMotion.uniform(PipeContents.EMPTY, 0)};
        var air = emptyAir(4); double initial = energy(tubes, motion, air);
        var result = step(tubes, motion, air, new boolean[4], new boolean[4], new double[4], new double[4], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "air on both sides communicates through the actual dry return path");
        near(.1, result.motion()[0].flow()[0], 1e-14, "one shared air pressure exerts no net force on an isolated horizontal plug");
        near(initial, energy(tubes, result.motion(), result.air()), 1e-12, "a closed dry loop preserves independent plug kinetic energy");
    }
    private static void trappedAir() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        PipeMotion[] motion = {new PipeMotion(PipeContents.of(.1, .3, .7, .9), new double[]{3, -1})};
        PipeAir[] air = emptyAir(1); double initial = energy(tubes, motion, air), mass = 0;
        for (var portion : air[0].fit(motion[0].contents(), 1).portions()) if (portion.start() == .3) mass = portion.mass();
        for (int i = 0; i < 30; i++) {
            var result = step(tubes, motion, air, new boolean[]{true, true}, new boolean[2], new double[2], new double[1], IdealFluidSolver.MAX_STEP / 16);
            check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP / 16, "compressible contact solve converges");
            motion = result.motion(); air = result.air();
            near(initial, energy(tubes, motion, air) + result.airBoundaryOutput(), 1e-9, "liquid kinetic energy and compressed air energy balance");
            near(mass, air[0].portions().get(1).mass(), 1e-18, "trapped air mass is conserved");
        }
        check(Math.abs(motion[0].flow()[0] - motion[0].flow()[1]) > .01, "air pressure does not force distinct plugs to share velocity");
    }
    private static void dryPump() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(1, 2, .5, 0, 0), new PipeFilling.Tube(2, 3, 1, 0, 0));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.EMPTY, 0), PipeMotion.uniform(PipeContents.EMPTY, 0), PipeMotion.uniform(PipeContents.of(.3, .5), 0)};
        var result = step(tubes, motion, emptyAir(3), new boolean[]{true, false, false, true}, new boolean[]{true, false, false, false}, new double[4], new double[]{0, 64, 0}, IdealFluidSolver.MAX_STEP);
        check(result.converged(), "dry-pump state converges");
        near(0, result.pumpWork(), 0, "dry rotor transfers no liquid power across an air gap");
        near(0, result.pumpHead()[1], 0, "dry pump reports no fictitious liquid head");
        near(0, result.motion()[2].flow()[0], 0, "dry pump cannot accelerate remote water");
    }
    private static void adjacentResolvedGasLabels() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        double a = .5, b = Math.nextUp(a), c = Math.nextUp(b), d = Math.nextUp(c);
        var liquid = PipeContents.of(.1, a, b, c, d, .9);
        var ambient = PipeAir.UNINITIALIZED.fit(liquid, 1); var portions = new java.util.ArrayList<>(ambient.portions());
        for (int i = 1; i <= 2; i++) {
            var p = portions.get(i); portions.set(i, new PipeAir.Portion(p.start(), p.end(), p.mass(), i * 1e-7));
        }
        PipeAir[] air = {new PipeAir(portions)};
        var result = step(tubes, new PipeMotion[]{PipeMotion.uniform(liquid, 0)}, air, new boolean[]{true, true},
            new boolean[2], new double[2], new double[1], 1e-25);
        check(result.converged() && result.allowedMinutes() == 1e-25, "neighbouring resolved gas pockets retain their own exact moving-face labels");
        for (int i = 1; i <= 2; i++) near(portions.get(i).mass(), result.air()[0].portions().get(i).mass(), 1e-30,
            "nearby compressed gas is not assigned to the first pocket within a coordinate tolerance");
    }
    private static void sealedAirAcrossJunction() {
        double width = 1e-8;
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(1, 2, 1, 0, 0));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.of(.1, 1 - width), .05),
            PipeMotion.uniform(PipeContents.of(width, .9), -.05)};
        PipeAir[] air = {PipeAir.UNINITIALIZED.fit(motion[0].contents(), 1), PipeAir.UNINITIALIZED.fit(motion[1].contents(), 1)};
        double initial = energy(tubes, motion, air);
        double mass = air[0].portions().getLast().mass() + air[1].portions().getFirst().mass();
        var result = step(tubes, motion, air, new boolean[]{true, false, true}, new boolean[3],
            new double[3], new double[2], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP,
            "sealed gas spanning a junction turns the approaching plugs before a fictitious free-flight mouth event");
        check(result.motion()[0].flow()[0] < 0 && result.motion()[1].flow()[0] > 0,
            "compressed junction air returns momentum to both real plugs");
        near(mass, result.air()[0].portions().getLast().mass() + result.air()[1].portions().getFirst().mass(), 1e-24,
            "a sealed air region keeps its mass while spanning two pipe arms");
        near(initial, energy(tubes, result.motion(), result.air()), 1e-10,
            "junction-spanning air and liquid exchange conserved energy");
    }
    private static void resolvedJunctionContact() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(0, 2, 1, 0, 0),
            new PipeFilling.Tube(0, 3, 1, 0, 0));
        var edges = tubes.stream().map(t -> new IdealFluidSolver.Edge(t.from(), t.to(), WATER.inertia(t.length()))).toList();
        var junctions = new DirectionalFluidJunctions(4, edges, tubes, new int[]{4, 5, 2}, new int[]{-1, -1, -1});
        var liquid = new PipeContents[]{PipeContents.FULL, PipeContents.EMPTY, PipeContents.of(1e-13, .1)};
        var lane = junctions.select(liquid, true);
        check(lane.joined() == 0, "a retained air gap keeps transverse water out of the actual junction contact");
        check(junctions.pressurise(lane, liquid, new double[junctions.nodes()], new double[3], new double[3], WATER,
            16 * AirLiquidSolver.RELATIVE_TOLERANCE) == lane, "position roundoff cannot invent transverse liquid pressure");
        liquid[2] = PipeContents.of(0, .1);
        check(junctions.select(liquid, true).joined() == 1, "actual transverse water arrival establishes the common wet pressure constraint");
        liquid[2] = PipeContents.EMPTY;
        var straight = junctions.select(liquid, true); double[] pressure = new double[junctions.nodes()];
        pressure[straight.tubes().getFirst().from()] = WATER.gravityForce(.2);
        var opened = junctions.pressurise(straight, liquid, pressure, new double[3], new double[3], WATER,
            16 * AirLiquidSolver.RELATIVE_TOLERANCE);
        check(opened.joined() == 1 && junctions.reselect(opened, liquid) == opened,
            "contact preparation retains the same trial's pressure-opened wet junction");
        liquid[0] = PipeContents.EMPTY;
        check(junctions.reselect(opened, liquid).joined() == 0, "a genuinely dry junction releases its liquid constraint");
    }
    private static void initialFilling() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.EMPTY, 0)};
        var result = step(tubes, motion, emptyAir(1), new boolean[]{true, true}, new boolean[]{true, false}, new double[]{WATER.gravityForce(.2), 0}, new double[1], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "a real source can start filling an empty pipe");
        check(result.motion()[0].contents().fraction() > 0, "gravity pressure admits actual source inventory");
        near(result.motion()[0].contents().fraction() * PipeFilling.MB_PER_METRE,
            result.waterOutward()[0] * IdealFluidSolver.MAX_STEP / RotationMath.TICK_MINUTES, 1e-12, "initial fill owns exactly the water extracted");
    }
    private static void dryAirOpposesJunctionPressure() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(0, 2, 1, 0, 0),
            new PipeFilling.Tube(0, 3, 1, 0, 0));
        var edges = tubes.stream().map(t -> new IdealFluidSolver.Edge(t.from(), t.to(), WATER.inertia(t.length()))).toList();
        var junctions = new DirectionalFluidJunctions(4, edges, tubes, new int[]{4, 5, 2}, new int[]{-1, -1, -1});
        var liquid = new PipeContents[]{PipeContents.FULL, PipeContents.FULL, PipeContents.EMPTY};
        var motion = Arrays.stream(liquid).map(c -> PipeMotion.uniform(c, 0)).toArray(PipeMotion[]::new);
        int nodes = junctions.nodes(); boolean[] boundary = new boolean[nodes]; boundary[1] = true;
        double[] ceiling = new double[nodes]; Arrays.fill(ceiling, Double.POSITIVE_INFINITY);
        for (int sign : new int[]{1, -1}) {
            PipeAir[] air = emptyAir(3); double volume = PipeAir.volume(1, 1);
            air[2] = new PipeAir(List.of(new PipeAir.Portion(0, 1, PipeAir.DENSITY * volume,
                PipeAir.internalEnergy(volume, PipeAir.ATMOSPHERE + sign * 400))));
            double[] datum = new double[nodes]; datum[1] = IdealFluidSolver.pressureForce(sign * 200);
            var layout = junctions.select(liquid, true);
            var held = AirLiquidSolver.sample(layout.tubes(), tubes, nodes, motion, air, boundary, boundary, boundary,
                datum, new boolean[nodes], ceiling, new double[3], WATER, IdealFluidSolver.MAX_STEP);
            var contact = junctions.pressurise(layout, liquid, held.pressure(), held.fromFlow(), held.toFlow(), WATER,
                16 * AirLiquidSolver.RELATIVE_TOLERANCE);
            check(held.converged(), "wet trunk and isolated branch air retain their actual static pressures");
            check(sign > 0 ? contact == layout : contact.joined() == 1,
                sign > 0 ? "compressed branch air opposes weaker positive liquid pressure"
                    : "low-pressure branch air admits higher liquid pressure even below atmosphere");
        }
    }
    private static void departingJetReleasesJunction() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(0, 2, 1, 0, 0),
            new PipeFilling.Tube(0, 3, 1, 0, 0));
        var edges = tubes.stream().map(t -> new IdealFluidSolver.Edge(t.from(), t.to(), WATER.inertia(t.length()))).toList();
        var junctions = new DirectionalFluidJunctions(4, edges, tubes, new int[]{4, 5, 2}, new int[]{-1, -1, -1});
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.of(0, .2), 16), PipeMotion.uniform(PipeContents.EMPTY, 0),
            PipeMotion.uniform(PipeContents.EMPTY, 0)};
        var liquid = Arrays.stream(motion).map(PipeMotion::contents).toArray(PipeContents[]::new);
        int nodes = junctions.nodes(); boolean[] boundary = new boolean[nodes]; boundary[1] = boundary[2] = boundary[3] = true;
        double[] ceiling = new double[nodes]; Arrays.fill(ceiling, Double.POSITIVE_INFINITY);
        var result = AirLiquidSolver.sample(junctions.select(liquid, true).tubes(), tubes, nodes, motion, emptyAir(3),
            boundary, new boolean[nodes], boundary, new double[nodes], boundary, ceiling, new double[3], WATER, IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "an outgoing plug admits adjoining air behind its trailing face");
        near(16, result.motion()[0].flow()[0], 1e-11, "a dry opposite lane cannot pin or reverse the real departing jet");
        near(0, result.projectionLoss(), 1e-12, "air contact release introduces no fictitious liquid impulse");
        check(result.motion()[1].contents() == PipeContents.EMPTY && result.motion()[2].contents() == PipeContents.EMPTY,
            "the released dry junction cannot split the departing jet into invented incoming water");
    }
    private static void closedSourceVoid() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.FULL, 30)};
        boolean[] boundary = {true, true}, receives = {false, true}, atmosphere = {false, true};
        double[] ceiling = {Double.POSITIVE_INFINITY, PipeAir.ATMOSPHERE};
        var result = AirLiquidSolver.sample(tubes, tubes, 2, motion, emptyAir(1), boundary, new boolean[2], receives,
            new double[]{WATER.gravityForce(1), 0}, atmosphere, ceiling, new double[1], WATER, IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "an exhausted closed liquid source provides no implicit atmospheric air");
        var voidAir = result.air()[0].portions().getFirst();
        check(voidAir.start() == 0 && voidAir.end() > 0 && voidAir.mass() == 0 && voidAir.energy() == 0,
            "departing water leaves an explicit closed vacuum instead of invented air inventory");
        near(0, result.waterOutward()[0], 1e-14, "a closed exhausted source cannot supply fictitious water");
        check(result.motion()[0].flow()[0] < 30, "the common absolute-pressure reference opposes creation of a vacuum");
    }
    private static void movingPersistence() {
        var motion = new PipeMotion(PipeContents.of(.1, .3, .7, .9), new double[]{4, -2});
        var joined = PipeMotion.join(motion.slice(0, .5), motion.slice(.5, 1), .5);
        near(4, joined.flow()[0], 0, "half-arm round trip keeps first plug velocity");
        near(-2, joined.flow()[1], 0, "half-arm round trip keeps second plug velocity");
        var air = PipeAir.UNINITIALIZED.fit(motion.contents(), 1);
        var recovered = PipeAir.join(air.slice(0, .5), air.slice(.5, 1), .5).fit(motion.contents(), 1);
        near(air.portions().stream().mapToDouble(PipeAir.Portion::mass).sum(), recovered.portions().stream().mapToDouble(PipeAir.Portion::mass).sum(), 1e-18, "air split/join conserves mass");
        near(air.portions().stream().mapToDouble(PipeAir.Portion::energy).sum(), recovered.portions().stream().mapToDouble(PipeAir.Portion::energy).sum(), 1e-12, "air split/join conserves internal energy");
    }
    private static void smallPocket() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        for (double width : new double[]{.1, .001, .00001, .0000001}) {
            PipeMotion[] motion = {PipeMotion.uniform(PipeContents.of(.1, 1 - width), .05)};
            PipeAir[] air = emptyAir(1); double initial = energy(tubes, motion, air);
            for (int i = 0; i < 10; i++) {
                var result = step(tubes, motion, air, new boolean[]{true, false}, new boolean[2], new double[2], new double[1], IdealFluidSolver.MAX_STEP);
                check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "small closed pocket accepts the physical time step: " + width);
                motion = result.motion(); air = result.air();
                near(initial, energy(tubes, motion, air), 1e-9, "stiff compression stores and returns the plug's kinetic energy");
                check(air[0].portions().getLast().mass() > 0 && motion[0].contents().end(0) < 1, "finite sealed air cannot be erased or cross a wall");
            }
        }
    }
    private static void microscopicLiquid() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        for (double width : new double[]{1e-8, 1e-11, 1e-13}) {
            PipeMotion[] motion = {new PipeMotion(PipeContents.of(.1, .3, .6, .6 + width, .8, .9), new double[]{1, 60, -1})};
            PipeAir[] air = emptyAir(1); double initial = energy(tubes, motion, air);
            double liquid = motion[0].contents().fraction();
            var result = step(tubes, motion, air, new boolean[]{true, true}, new boolean[2], new double[2], new double[1], IdealFluidSolver.MAX_STEP);
            check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "a microscopic real parcel accepts the full implicit step: " + width);
            check(result.motion()[0].contents().intervals() == 3, "the thin parcel keeps its separate liquid inventory");
            near(liquid, result.motion()[0].contents().fraction(), 2e-15, "thin parcels are not deleted to regularise pressure");
            near(initial, energy(tubes, result.motion(), result.air()) + result.airBoundaryOutput(), 1e-10, "air and all three parcels conserve their physical energy");
        }
    }
    private static void thinReversalAtPipeMouth() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(1, 2, 1, 0, 0));
        PipeMotion[] motion = {new PipeMotion(PipeContents.of(.9999999602838959, .9999999602847929,
            .9999999607848117, .9999999607857735), new double[]{162.57368050870397, -37.10348771113712}),
            PipeMotion.uniform(PipeContents.EMPTY, 0)};
        PipeAir[] air = emptyAir(2); double liquid = motion[0].contents().fraction();
        boolean crossed = false;
        for (int event = 0; event < 30 && !crossed; event++) {
            double dt = IdealFluidSolver.MAX_STEP; AirLiquidSolver.Result result;
            do {
                result = step(tubes, motion, air, new boolean[]{true, false, true}, new boolean[3], new double[3], new double[2], dt);
                check(result.allowedMinutes() > 1e-18, "thin reversal keeps a representable physical clock");
                if (!result.converged() || result.allowedMinutes() < dt * (1 - 1e-12)) dt = Math.min(dt / 2, result.allowedMinutes());
                else break;
            } while (true);
            motion = result.motion(); air = result.air();
            near(liquid, motion[0].contents().fraction() + motion[1].contents().fraction(), 2e-15,
                "a thin parcel crossing a real junction cannot drain into its temporary air boundary");
            crossed = motion[1].contents().fraction() > 0;
        }
        check(crossed, "compressed air can reverse a thin parcel before it crosses the real pipe mouth");
    }
    private static void fastMicroscopicTail() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(1, 2, 1, 0, 0));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.EMPTY, 0), PipeMotion.uniform(PipeContents.of(0, 2.1094237467877974e-15), -247685)};
        PipeAir[] air = emptyAir(2); double initial = energy(tubes, motion, air), liquid = motion[1].contents().fraction();
        var result = step(tubes, motion, air, new boolean[]{true, false, true}, new boolean[3], new double[3], new double[2], 1e-8);
        check(result.converged() && result.allowedMinutes() == 1e-8, "a microscopic fast tail completes its zero-volume junction crossing");
        check(result.motion()[1].contents() == PipeContents.EMPTY && result.motion()[0].contents().intervals() == 1,
            "the departed tail does not remain as a fictitious wet source");
        near(liquid, result.motion()[0].contents().fraction(), 2e-16, "fast microscopic tail transfers its real water");
        near(initial, energy(tubes, result.motion(), result.air()) + result.airBoundaryOutput(), 1e-11, "affine endpoint roundoff preserves the fast tail's kinetic energy");
    }
    private static void wetPump() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, .5, 0, 0));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.of(.2, .8), 1)}; PipeAir[] air = emptyAir(1);
        double initial = energy(tubes, motion, air);
        var result = step(tubes, motion, air, new boolean[]{true, true}, new boolean[2], new double[2], new double[]{64}, IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "wet pump interval converges");
        check(result.pumpWork() > 0 && result.pumpTorque()[0] > 0, "wet pump transfers power through its actual liquid contact");
        near(result.pumpWork(), energy(tubes, result.motion(), result.air()) - initial, 1e-10, "liquid energy gain equals wet rotor work");
        near(result.pumpWork(), IdealFluidSolver.MAX_STEP * 64 * result.pumpTorque()[0], 1e-14, "signed torque and pressure use one pump coefficient");
    }
    private static void closedValve() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.of(.2, .8), 1)};
        double[] ceiling = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        var result = AirLiquidSolver.sample(tubes, tubes, 2, motion, emptyAir(1), new boolean[]{true, true}, new boolean[]{true, false},
            new boolean[]{true, true}, new double[]{WATER.gravityForce(2), 0}, new boolean[]{true, true}, ceiling, new double[]{64}, WATER, IdealFluidSolver.MAX_STEP, new boolean[]{false});
        check(result.converged(), "closed valve converges with an explicit contact impulse");
        near(0, result.motion()[0].flow()[0], 0, "closed pipe permission blocks liquid flow");
        near(.5 * WATER.inertia(1) * .6, result.projectionLoss(), 1e-14, "blocked motion is an accounted impulse");
        near(0, result.waterOutward()[0], 0, "closed valve cannot draw source inventory");
        near(.6, result.motion()[0].contents().fraction(), 1e-14, "closed valve retains its water");
    }
    private static void reservoirCompressesAir() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.EMPTY, 0)}; PipeAir[] air = emptyAir(1);
        boolean[] borders = {true, false}; double[] ceiling = {PipeAir.ATMOSPHERE + 1962, Double.POSITIVE_INFINITY};
        double mass = PipeAir.DENSITY * IdealFluidSolver.AREA, pressureWork = 0, output = 0, initial = energy(tubes, motion, air);
        double remaining = IdealFluidSolver.MAX_STEP * 30;
        int steps = 0;
        while (remaining > 1e-14) {
            double dt = Math.min(remaining, IdealFluidSolver.MAX_STEP);
            AirLiquidSolver.Result result;
            for (int retry = 0;; retry++) {
                result = AirLiquidSolver.sample(tubes, tubes, 2, motion, air, borders, new boolean[]{true, false}, borders,
                    new double[]{WATER.gravityForce(.2), 0}, new boolean[2], ceiling, new double[1], WATER, dt);
                if (result.converged() && result.allowedMinutes() >= dt * (1 - 1e-12)) break;
                check(retry < 64 && result.allowedMinutes() > 1e-18, "air contact event resolves in finite physical time");
                dt = Math.min(result.allowedMinutes(), dt / 2);
            }
            pressureWork += dt * WATER.gravityForce(.2) * result.waterOutward()[0];
            double q = result.waterOutward()[0];
            if (q < 0) output += dt * WATER.outletForce(q) * q;
            output += result.airBoundaryOutput() + result.projectionLoss();
            motion = result.motion(); air = result.air(); remaining -= dt;
            check(++steps < 1000, "gravity filling does not chatter at the source mouth");
            near(initial + pressureWork, energy(tubes, motion, air) + output, 1e-9, "source work accounts for compressed air, liquid kinetic energy and boundary output");
            near(mass, air[0].portions().stream().mapToDouble(PipeAir.Portion::mass).sum(), 1e-17, "sealed downstream air retains its mass");
        }
        check(motion[0].contents().fraction() > 0, "source pressure fills a closed air-containing pipe by compression");
        check(motion[0].contents().fraction() < .1, "low hydrostatic head cannot displace all sealed atmospheric air");
    }
    private static void ventedJetCrossesPipeMouths() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(1, 2, 1, 0, 0));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.of(.2, .8), 16), PipeMotion.uniform(PipeContents.EMPTY, 0)};
        PipeAir[] air = emptyAir(2); double initial = energy(tubes, motion, air), delivered = 0, output = 0, loss = 0;
        double remaining = IdealFluidSolver.MAX_STEP * 100; int steps = 0;
        while (remaining > 1e-14) {
            double dt = Math.min(remaining, IdealFluidSolver.MAX_STEP); AirLiquidSolver.Result result;
            for (int retry = 0;; retry++) {
                result = step(tubes, motion, air, new boolean[]{true, false, true}, new boolean[3], new double[3], new double[2], dt);
                if (result.converged() && result.allowedMinutes() >= dt * (1 - 1e-12)) break;
                check(retry < 64 && result.allowedMinutes() > 1e-18, "a jet arriving at a dry mouth resolves a finite front event");
                dt = Math.min(result.allowedMinutes(), dt / 2);
            }
            delivered -= result.waterOutward()[2] * dt / RotationMath.TICK_MINUTES;
            output += dt * -result.waterOutward()[2] * Math.abs(WATER.outletForce(result.midpoint()[1])) + result.airBoundaryOutput();
            loss += result.projectionLoss(); motion = result.motion(); air = result.air(); remaining -= dt;
            double stored = Arrays.stream(motion).mapToDouble(m -> m.contents().fraction() * PipeFilling.MB_PER_METRE).sum();
            near(60, stored + delivered, 1e-7, "plug transport preserves real water through repeated wet/dry mouth changes");
            near(initial, energy(tubes, motion, air) + output + loss, 1e-8, "moving-mass transport and inventory output conserve the jet's energy: step=" + steps
                + "; loss=" + loss + "; output=" + output + "; motion=" + Arrays.stream(motion).map(m -> Arrays.toString(m.contents().data()) + Arrays.toString(m.flow())).toList()
                + "; air=" + Arrays.stream(air).map(PipeAir::portions).toList());
            check(++steps < 1000, "vented jet finishes the requested physical interval");
        }
        near(60, delivered, 1e-7, "complete vented packet reaches the receiver");
        near(0, loss, 1e-10, "free pipe-mouth passage does not remove a jet's momentum");
    }
    private static void roundoffMouth() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(1, 2, 1, 0, 0));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.of(.7, 1 - 4e-15), 16), PipeMotion.uniform(PipeContents.EMPTY, 0)};
        var result = step(tubes, motion, emptyAir(2), new boolean[]{true, false, true}, new boolean[3], new double[3], new double[2], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "roundoff at a wet mouth is a single contact event");
        near(.3, Arrays.stream(result.motion()).mapToDouble(m -> m.contents().fraction()).sum(), 1e-14,
            "near-mouth contact sends water into the next pipe, never into an air boundary");
        check(result.motion()[1].contents().fraction() > 0, "real jet reaches the adjoining dry pipe");
    }
    private static void roundoffContact() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        double face = .9, afterFace = Math.nextUp(Math.nextUp(face));
        var liquid = PipeContents.of(.5, face, afterFace, 1);
        PipeMotion[] motion = {new PipeMotion(liquid, new double[]{-2, -3})};
        PipeAir[] air = {new PipeAir(List.of(new PipeAir.Portion(0, .5, PipeAir.DENSITY * PipeAir.volume(1, .5),
            PipeAir.internalEnergy(PipeAir.volume(1, .5), PipeAir.ATMOSPHERE)), new PipeAir.Portion(face, afterFace, 0, 0)))};
        var result = step(tubes, motion, air, new boolean[]{true, true}, new boolean[]{false, true}, new double[2], new double[1], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP, "coalescing an internal roundoff gap permits a finite step");
        check(result.motion()[0].contents().intervals() == 1 && result.motion()[0].contents().end(0) == 1,
            "merged water retains its wet mouth instead of reopening the same gap");
        near(liquid.fraction() * PipeFilling.MB_PER_METRE + result.waterOutward()[1] * IdealFluidSolver.MAX_STEP / RotationMath.TICK_MINUTES,
            result.motion()[0].contents().fraction() * PipeFilling.MB_PER_METRE, 1e-10, "roundoff contact preserves real liquid ownership");
        check(result.projectionLoss() > 0, "merging unequal water velocities accounts for their collision impulse");
    }
    private static void storedMicroscopicAir() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0));
        var liquid = PipeContents.of(.1, 1 - 4e-15);
        PipeMotion[] motion = {PipeMotion.uniform(liquid, 0)};
        var ambient = PipeAir.UNINITIALIZED.fit(liquid, 1);
        var compressed = ambient.portions().getLast();
        PipeAir[] air = {new PipeAir(List.of(ambient.portions().getFirst(),
            new PipeAir.Portion(compressed.start(), compressed.end(), compressed.mass(), 1e-4)))};
        double initial = energy(tubes, motion, air), dt = IdealFluidSolver.MAX_STEP;
        AirLiquidSolver.Result result;
        for (int retry = 0;; retry++) {
            result = step(tubes, motion, air, new boolean[2], new boolean[2], new double[2], new double[1], dt);
            if (result.converged() && result.allowedMinutes() >= dt * (1 - 1e-12)) break;
            check(retry < 64 && result.allowedMinutes() > 1e-18, "stored microscopic air has a finite pressure response");
            dt = Math.min(result.allowedMinutes(), dt / 2);
        }
        check(result.motion()[0].flow()[0] < 0, "compressed air remains a real restoring force even within coordinate roundoff");
        near(compressed.mass(), result.air()[0].portions().getLast().mass(), 1e-25, "meaningful microscopic air storage is never removed by contact coalescence");
        near(initial, energy(tubes, result.motion(), result.air()) + result.projectionLoss(), 1e-10, "microscopic gas releases its stored energy into the actual water mass");
    }
    private static void staticHeadOpensDryBranch() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(0, 2, 1, 0, 0),
            new PipeFilling.Tube(0, 3, 1, 0, 0), new PipeFilling.Tube(0, 4, 1, 0, 0));
        var edges = tubes.stream().map(t -> new IdealFluidSolver.Edge(t.from(), t.to(), WATER.inertia(t.length()))).toList();
        var junctions = new DirectionalFluidJunctions(5, edges, tubes, new int[]{4, 5, 2, 3}, new int[]{-1, -1, -1, -1});
        var liquid = new PipeContents[]{PipeContents.FULL, PipeContents.FULL, PipeContents.EMPTY, PipeContents.EMPTY};
        var motion = Arrays.stream(liquid).map(c -> PipeMotion.uniform(c, 0)).toArray(PipeMotion[]::new);
        int nodes = junctions.nodes(); boolean[] boundary = new boolean[nodes], supplies = new boolean[nodes], receives = new boolean[nodes];
        boundary[1] = supplies[1] = receives[1] = true;
        double[] datum = new double[nodes], ceiling = new double[nodes]; datum[1] = WATER.gravityForce(.2); Arrays.fill(ceiling, Double.POSITIVE_INFINITY);
        var layout = junctions.select(liquid, true);
        var held = AirLiquidSolver.sample(layout.tubes(), tubes, nodes, motion, emptyAir(4), boundary, supplies, receives, datum,
            new boolean[nodes], ceiling, new double[4], WATER, IdealFluidSolver.MAX_STEP);
        check(held.converged(), "static liquid pressure is resolved without a through-flow");
        var contact = junctions.pressurise(layout, liquid, held.pressure(), held.fromFlow(), held.toFlow(), WATER,
            16 * AirLiquidSolver.RELATIVE_TOLERANCE);
        check(contact != layout && contact.joined() == 1, "a blocked wet trunk retains tank head and opens its dry side mouths");
        var filling = AirLiquidSolver.sample(contact.tubes(), tubes, nodes, motion, emptyAir(4), boundary, supplies, receives, datum,
            new boolean[nodes], ceiling, new double[4], WATER, IdealFluidSolver.MAX_STEP);
        check(filling.converged() && filling.motion()[2].contents().fraction() > 0 && filling.motion()[3].contents().fraction() > 0,
            "real source pressure admits water into both sealed air branches");
        near(filling.motion()[2].contents().fraction(), filling.motion()[3].contents().fraction(), 1e-12, "symmetric compressed-air branches receive equal water");
    }
    private static void compressedAirAtNearMouth() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, .5, 0, 0));
        double face = 4.874989301129062e-13, dt = 5.669473745711178e-18;
        var liquid = PipeContents.of(face, .005749906268845417);
        var ambient = PipeAir.UNINITIALIZED.fit(liquid, .5);
        PipeAir[] air = {new PipeAir(List.of(new PipeAir.Portion(0, face, 1.2171025038232126e-12, 2.3253812997036035e-5),
            ambient.portions().getLast()))};
        for (double speed : new double[]{-19.21661780911123, 0, 19.21661780911123}) {
            PipeMotion[] motion = {PipeMotion.uniform(liquid, speed)};
            double initial = energy(tubes, motion, air);
            var result = step(tubes, motion, air, new boolean[]{false, true}, new boolean[2], new double[2], new double[1], dt);
            check(result.converged() && result.allowedMinutes() == dt, "a resolved compressed mouth pocket permits motion in both directions");
            near(initial, energy(tubes, result.motion(), result.air()) + result.airBoundaryOutput(), 1e-12,
                "near-zero front coordinates preserve actual gas-pressure work during return flow");
            near(air[0].portions().getFirst().mass(), result.air()[0].portions().getFirst().mass(), 1e-25,
                "returning liquid retains the closed microscopic gas inventory");
        }
    }
    private static void resolvedNearMouth() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, .5, 0, 0), new PipeFilling.Tube(0, 2, .5, 0, 0));
        var liquid = PipeContents.of(1.0829727722191405e-13, 4.549827323615812e-8);
        PipeMotion[] motion = {PipeMotion.uniform(liquid, -208.66621374025704), PipeMotion.uniform(PipeContents.EMPTY, 0)};
        var ambient = PipeAir.UNINITIALIZED.fit(liquid, .5);
        PipeAir[] air = {new PipeAir(List.of(new PipeAir.Portion(0, liquid.start(0), 1e-12, 1e-5), ambient.portions().getLast())),
            PipeAir.UNINITIALIZED.fit(PipeContents.EMPTY, .5)};
        var result = step(tubes, motion, air, new boolean[]{false, true, false}, new boolean[3], new double[3], new double[2], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() < 1e-16,
            "a protected gas gap within front roundoff still locates its real pipe-mouth event");
        near(liquid.fraction(), result.motion()[0].contents().fraction(), 1e-25,
            "a detached mouth event cannot discharge real water into its temporary air boundary");
    }
    private static void tinyBranchedTail() {
        var tubes = List.of(new PipeFilling.Tube(1, 0, 1, 0, 0), new PipeFilling.Tube(0, 2, .5, 0, 0),
            new PipeFilling.Tube(0, 3, 1, 0, 0));
        var incoming = PipeContents.of(1 - 8e-14, 1);
        PipeMotion[] motion = {PipeMotion.uniform(incoming, 16), PipeMotion.uniform(PipeContents.EMPTY, 0), PipeMotion.uniform(PipeContents.EMPTY, 0)};
        var result = step(tubes, motion, emptyAir(3), new boolean[]{false, true, true, true}, new boolean[4],
            new double[4], new double[3], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP && result.motion()[0].contents() == PipeContents.EMPTY,
            "an unresolved all-dry fork completes the finite incoming packet without retaining a false wet source");
        double first = .5 * result.motion()[1].contents().fraction(), second = result.motion()[2].contents().fraction();
        near(incoming.fraction(), first + second, 1e-17, "branched contact transfers actual microscopic water without deleting it");
        near(first, second, 1e-17, "equal-area initially empty outlets admit equal mass despite different empty lengths");
        double kinetic = .5 * WATER.inertia(1) * incoming.fraction() * 16 * 16;
        near(.75 * kinetic, result.projectionLoss(), 1e-20, "the finite fork impulse explicitly accounts for its projected kinetic energy");
    }
    private static void microscopicCounterflowContact() {
        var tubes = List.of(new PipeFilling.Tube(1, 0, 1, 0, 0), new PipeFilling.Tube(0, 2, .5, 0, 0),
            new PipeFilling.Tube(0, 3, 1, 0, 0));
        var incoming = PipeContents.of(1 - 8e-14, 1); double volume = incoming.fraction();
        PipeMotion[] motion = {PipeMotion.uniform(incoming, 16), PipeMotion.uniform(PipeContents.of(0, 2 * volume), -16),
            PipeMotion.uniform(PipeContents.EMPTY, 0)};
        var result = step(tubes, motion, emptyAir(3), new boolean[]{false, true, true, true}, new boolean[4],
            new double[4], new double[3], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP && result.motion()[0].contents() == PipeContents.EMPTY,
            "opposing microscopic packets complete one actual contact without retaining two false wet sources");
        near(2 * volume, .5 * result.motion()[1].contents().fraction(), 1e-25, "head-on contact retains the actual mass of both packets");
        near(0, result.motion()[1].flow()[0], 1e-12, "equal incoming momenta cancel in the recorded contact impulse");
        near(WATER.inertia(1) * volume * 16 * 16, result.projectionLoss(), 1e-20,
            "a microscopic head-on collision records its actual kinetic loss rather than deleting inventory");
    }
    private static void nanometreJunctionHandoff() {
        for (double length : new double[]{.5, 1, 4}) for (double width : new double[]{1e-11, 4e-10, 9e-10}) {
            var tubes = List.of(new PipeFilling.Tube(1, 0, length, 0, 0), new PipeFilling.Tube(0, 2, .5, 0, 0),
                new PipeFilling.Tube(0, 3, 1, 0, 0));
            var incoming = PipeContents.of(1 - width / length, 1);
            double volume = length * incoming.fraction();
            PipeMotion[] motion = {PipeMotion.uniform(incoming, 16), PipeMotion.uniform(PipeContents.of(0, 2 * volume), -16),
                PipeMotion.uniform(PipeContents.EMPTY, 0)};
            var result = step(tubes, motion, emptyAir(3), new boolean[]{false, true, true, true}, new boolean[4],
                new double[4], new double[3], IdealFluidSolver.MAX_STEP);
            check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP,
                "a nanometre junction contact cannot impose repeated microscopic clock events");
            check(result.motion()[0].contents() == PipeContents.EMPTY, "the complete finite parcel crosses its mouth");
            near(2 * volume, .5 * result.motion()[1].contents().fraction(), 1e-16, "nanometre handoff retains both water inventories");
            near(0, result.motion()[1].flow()[0], 1e-12, "opposing nanometre momenta cancel");
            near(WATER.inertia(1) * volume * 16 * 16, result.projectionLoss(), 1e-17,
                "nanometre contact records all physical collision energy");
        }
        // Keep the existing spatial and pressure-work bounds. A resolved film
        // still crosses in physical time; a large head cannot be skipped.
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(1, 2, 1, 0, 0));
        for (double width : new double[]{4e-10, 2e-9}) {
            var incoming = PipeContents.of(1 - width, 1);
            PipeMotion[] motion = {PipeMotion.uniform(incoming, 16), PipeMotion.uniform(PipeContents.EMPTY, 0)};
            double[] pressure = {width < 1e-9 ? IdealFluidSolver.pressureForce(1e8) : 0, 0, 0};
            var result = step(tubes, motion, emptyAir(2), new boolean[]{true, false, true}, new boolean[3],
                pressure, new double[2], IdealFluidSolver.MAX_STEP);
            check(result.allowedMinutes() < IdealFluidSolver.MAX_STEP && result.motion()[0].contents().fraction() > 0,
                "resolved length or pressure work retains the timed mouth event");
        }
    }

    private static void localJunctionPressure() {
        var tubes = List.of(new PipeFilling.Tube(1, 0, 1, 0, 0), new PipeFilling.Tube(0, 2, .5, 0, 0),
            new PipeFilling.Tube(0, 3, 1, 0, 0));
        var incoming = PipeContents.of(.4, .6, 1 - 4e-10, 1);
        double volume = 1 - incoming.start(1);
        PipeMotion[] motion = {new PipeMotion(incoming, new double[]{0, 16}),
            PipeMotion.uniform(PipeContents.of(0, 2 * volume), -16), PipeMotion.uniform(PipeContents.EMPTY, 0)};
        var air = emptyAir(3);
        air[0] = new PipeAir(PipeAir.UNINITIALIZED.fit(incoming, 1).portions().stream()
            .map(p -> p.start() == 0 ? new PipeAir.Portion(p.start(), p.end(), p.mass(),
                PipeAir.internalEnergy(PipeAir.volume(1, p.end() - p.start()), 1e8)) : p).toList());
        double duration = 1e-12;
        var result = step(tubes, motion, air, new boolean[]{false, false, true, true}, new boolean[4],
            new double[4], new double[3], duration);
        check(result.converged() && result.allowedMinutes() == duration, "local contact completes its requested interval");
        check(result.motion()[0].contents().intervals() == 1 && result.motion()[0].contents().end(0) < 1,
            "a remote compressed pocket separated by liquid cannot retain the junction tail");
        near(2 * volume, .5 * result.motion()[1].contents().fraction(), 1e-16, "local handoff retains both contacting water parcels");
        near(0, result.motion()[1].flow()[0], 1e-12, "remote gas does not supply a spurious local contact impulse");
        near(WATER.inertia(1) * volume * 16 * 16, result.projectionLoss(), 1e-17, "local contact records the actual kinetic loss");
        near(air[0].portions().getFirst().mass(), result.air()[0].portions().getFirst().mass(), 1e-18,
            "the separate compressed pocket retains its gas inventory");
    }

    private static void energeticMicroscopicFork() {
        var tubes = List.of(new PipeFilling.Tube(1, 0, 1, 0, 0), new PipeFilling.Tube(0, 2, .5, 0, 0),
            new PipeFilling.Tube(0, 3, 1, 0, 0));
        var incoming = PipeContents.of(1 - 8e-14, 1);
        for (double speed : new double[]{6000, 1e6}) {
            var motion = new PipeMotion[]{PipeMotion.uniform(incoming, speed), PipeMotion.uniform(PipeContents.EMPTY, 0),
                PipeMotion.uniform(PipeContents.EMPTY, 0)};
            var air = emptyAir(3); double initial = energy(tubes, motion, air), duration = 1e-11;
            var result = step(tubes, motion, air, new boolean[]{false, true, true, true}, new boolean[4],
                new double[4], new double[3], duration);
            check(result.converged() && result.allowedMinutes() == duration && result.motion()[0].contents() == PipeContents.EMPTY,
                "a fast microscopic fork parcel completes contact even when its kinetic energy exceeds the roundoff bound");
            near(incoming.fraction(), .5 * result.motion()[1].contents().fraction() + result.motion()[2].contents().fraction(),
                2e-16, "energetic fork contact retains the real water");
            near(initial, energy(tubes, result.motion(), result.air()) + result.projectionLoss() + result.airBoundaryOutput(),
                1e-11, "energetic fork contact accounts for all retained and projected kinetic energy");
        }
    }
    private static void tinyTailAtCounterflowFork() {
        var tubes = List.of(new PipeFilling.Tube(1, 0, 1, 0, 0), new PipeFilling.Tube(0, 2, 1, 0, 0),
            new PipeFilling.Tube(0, 3, 1, 0, 0));
        var incoming = PipeContents.of(1 - 8e-14, 1);
        var motion = new PipeMotion[]{PipeMotion.uniform(incoming, 6000), PipeMotion.uniform(PipeContents.FULL, -4),
            PipeMotion.uniform(PipeContents.EMPTY, 0)};
        var air = emptyAir(3); double initial = energy(tubes, motion, air), duration = 1e-9;
        var result = step(tubes, motion, air, new boolean[]{false, true, true, true}, new boolean[]{false, false, true, false},
            new double[4], new double[3], duration);
        check(result.converged() && result.allowedMinutes() == duration && result.motion()[0].contents() == PipeContents.EMPTY,
            "a full counterflow arm does not block a microscopic parcel's genuinely dry exit");
        double before = 100 * (1 + incoming.fraction()), after = Arrays.stream(result.motion()).mapToDouble(m -> 100 * m.contents().fraction()).sum();
        double supplied = (result.waterOutward()[1] + result.waterOutward()[2] + result.waterOutward()[3]) * duration / RotationMath.TICK_MINUTES;
        near(before + supplied, after, 1e-9, "counterflow fork preserves actual source and pipe inventory");
        near(initial, energy(tubes, result.motion(), result.air()) + result.projectionLoss() + result.airBoundaryOutput(),
            1e-11, "counterflow fork accounts for the actual liquid contact impulse");
    }
    private static void tinyWetJunctionTail() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(1, 2, 1, 0, 0),
            new PipeFilling.Tube(1, 3, .5, 0, 0));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.FULL, 8.344209102287621),
            PipeMotion.uniform(PipeContents.of(0, 1.7238314718634385e-23), -3.115271530227309),
            PipeMotion.uniform(PipeContents.FULL, 11.459480632514929)};
        var result = step(tubes, motion, emptyAir(3), new boolean[]{true, false, false, true},
            new boolean[]{true, false, false, false}, new double[4], new double[3], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP,
            "a sub-resolution departing tail does not hold a branched liquid constraint for an unrepresentable interval");
        double before = 150, after = 0, input = 0;
        for (int e = 0; e < tubes.size(); e++) after += result.motion()[e].contents().fraction() * tubes.get(e).length() * PipeFilling.MB_PER_METRE;
        for (int n : new int[]{0, 3}) input += result.waterOutward()[n] * IdealFluidSolver.MAX_STEP / RotationMath.TICK_MINUTES;
        near(before + input, after, 1e-10, "unresolved tail water is transferred to the actual inventory, never discarded");
        check(result.projectionLoss() > 0, "the new wetted constraint records its real momentum impulse");
    }
    private static void tinyTailJoinsWetArm() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, .5, 0, 0), new PipeFilling.Tube(0, 2, 1, 0, 0));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.of(0, 8.271806125530277e-25), -16.057234452134928),
            PipeMotion.uniform(PipeContents.of(0, .0021140269780768085), 12.153725261215211)};
        var air = emptyAir(2); double initial = energy(tubes, motion, air);
        var result = step(tubes, motion, air, new boolean[]{false, true, true}, new boolean[3], new double[3], new double[2], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP,
            "a microscopic incoming tail joins actual outgoing water before its unrepresentable contact event");
        near(motion[1].contents().fraction(), result.motion()[1].contents().fraction(), 1e-14,
            "the wet-arm handoff retains the real packet volume");
        near(initial, energy(tubes, result.motion(), result.air()) + result.projectionLoss() + result.airBoundaryOutput(), 1e-12,
            "wet-arm handoff and released gas contact account for their collision energy");
        check(result.motion()[0].contents() == PipeContents.EMPTY, "the departing tail cannot persist as a false wet inlet");
    }
    private static void fullColumnPassesTinyTail() {
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(1, 2, 1, 0, 0),
            new PipeFilling.Tube(1, 3, 1, 0, 0), new PipeFilling.Tube(2, 4, 1, 0, 0));
        PipeMotion[] motion = {PipeMotion.uniform(PipeContents.FULL, 5.677911689011328),
            PipeMotion.uniform(PipeContents.FULL, 7.4499102998654605), PipeMotion.uniform(PipeContents.of(0, 2.9888361977013695e-26), -1.7719986108541328),
            PipeMotion.uniform(PipeContents.of(0, .14939546297479284), 7.4499102998654605)};
        var result = step(tubes, motion, emptyAir(4), new boolean[]{true, false, false, false, true},
            new boolean[]{true, false, false, false, false}, new double[5], new double[4], IdealFluidSolver.MAX_STEP);
        check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP,
            "a full outgoing column transfers an unresolved contact parcel into its actual downstream water front");
        double before = 0, after = 0, input = 0;
        for (int e = 0; e < tubes.size(); e++) {
            before += motion[e].contents().fraction() * tubes.get(e).length() * PipeFilling.MB_PER_METRE;
            after += result.motion()[e].contents().fraction() * tubes.get(e).length() * PipeFilling.MB_PER_METRE;
        }
        for (int n : new int[]{0, 4}) input += result.waterOutward()[n] * IdealFluidSolver.MAX_STEP / RotationMath.TICK_MINUTES;
        near(before + input, after, 1e-10, "the full-column displacement preserves actual water ownership");
    }
    private static void stiffPressureRecovery() {
        // Minimized from the 2026-10-04 saved-world pressure crash. Three arms,
        // six liquid plugs and their actual air inventories suffice; no game
        // state, prepared contacts or injected solver failure is needed.
        var tubes = List.of(new PipeFilling.Tube(0, 1, 1, 0, 0), new PipeFilling.Tube(1, 2, .5, 0, .5),
            new PipeFilling.Tube(1, 3, .5, 0, 0));
        PipeMotion[] motion = {
            PipeMotion.uniform(PipeContents.of(.9993116607621663, .9996337781817141), 1.1538228766226977),
            PipeMotion.uniform(PipeContents.of(.00016168390840148444, .002350405600714477), -5.280922970107226),
            new PipeMotion(PipeContents.of(0, 1.2816734705458994e-6, .015455762645902002, .01567451093746973,
                .018497116430358354, .01939456651724958, .03502038232364161, .03731570391963564),
                new double[]{61.653669906743836, 2.8002512996116535, -5.873631852181154, 8.203375662856015})
        };
        PipeAir[] air = {
            new PipeAir(List.of(
                new PipeAir.Portion(0, .9993116607621663, .00012174094825420724, 25.118694683458767),
                new PipeAir.Portion(.9996337781817141, 1, 4.348975553038984e-8, .008905375557704888))),
            new PipeAir(List.of(
                new PipeAir.Portion(0, .00016168390840148444, 9.599284480555777e-9, .001965581415577109),
                new PipeAir.Portion(.002350405600714477, 1, 6.110603765695626e-5, 12.635855644063454))),
            new PipeAir(List.of(
                new PipeAir.Portion(1.2816734705458994e-6, .015455762645902002, 9.209967012236554e-7, .1885107644577653),
                new PipeAir.Portion(.01567451093746973, .018497116430358354, 1.6973797147084807e-7, .03484244578335851),
                new PipeAir.Portion(.01939456651724958, .03502038232364161, 9.442575637674577e-7, .19420987284640556),
                new PipeAir.Portion(.03731570391963564, 1, 5.9010860559260325e-5, 12.207491682343377)))
        };
        boolean[] boundary = {false, false, true, true}, supplies = {false, false, false, true};
        double[] datum = {0, 0, .18393750000000417, 0};
        double[] ceiling = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 104390.62500000007, PipeAir.ATMOSPHERE};
        double duration = IdealFluidSolver.MAX_STEP;
        AirLiquidSolver.Result result = null;
        boolean accepted = false;
        for (int retry = 0; retry < 32; retry++) {
            result = AirLiquidSolver.sample(tubes, tubes, 4, motion, air, boundary, supplies, boundary,
                datum, boundary, ceiling, new double[3], WATER, duration);
            check(Double.isFinite(result.allowedMinutes()) && result.allowedMinutes() > 0,
                "stiff pressure recovery returns a finite physical event");
            if (result.allowedMinutes() == duration && result.converged()) { accepted = true; break; }
            duration = Math.min(result.allowedMinutes(), duration / 2);
        }
        check(accepted,
            "the captured pressure failure recovers to an accepted conservative step");
        double before = 0, after = 0, input = 0, gravityChange = 0, boundaryWork = 0;
        for (int e = 0; e < tubes.size(); e++) {
            var tube = tubes.get(e);
            before += tube.length() * PipeFilling.MB_PER_METRE * motion[e].contents().fraction();
            after += tube.length() * PipeFilling.MB_PER_METRE * result.motion()[e].contents().fraction();
            gravityChange += WATER.density() * IdealFluidSolver.GRAVITY * IdealFluidSolver.AREA * tube.length()
                * (tube.toHeight() - tube.fromHeight()) * (result.motion()[e].contents().moment() - motion[e].contents().moment())
                / PipeAir.JOULES_PER_ENERGY;
            for (double q : result.motion()[e].flow()) check(Double.isFinite(q), "recovered liquid velocity is finite");
        }
        for (int n = 0; n < boundary.length; n++) if (boundary[n]) {
            input += duration / RotationMath.TICK_MINUTES * result.waterOutward()[n];
            boundaryWork += duration * datum[n] * result.waterOutward()[n];
            near(0, result.waterOutward()[n], 0, "the isolated plugs have not reached an inventory mouth");
        }
        near(before + input, after, 1e-11, "pressure recovery preserves actual water inventory");
        near(energy(tubes, motion, air) + boundaryWork,
            energy(tubes, result.motion(), result.air()) + gravityChange + result.projectionLoss() + result.airBoundaryOutput(),
            1e-10, "pressure recovery preserves liquid, gravity and air work");
        // A successful tiny step alone missed the original performance failure.
        // Advance an entire physical tick from the captured state, accounting
        // for real reservoir transfers instead of merely accepting a preview.
        double remaining = RotationMath.TICK_MINUTES, admitted = 0;
        int steps = 0;
        while (remaining > 1e-14) {
            duration = Math.min(remaining, IdealFluidSolver.MAX_STEP);
            accepted = false;
            for (int retry = 0; retry < 64; retry++) {
                result = AirLiquidSolver.sample(tubes, tubes, 4, motion, air, boundary, supplies, boundary,
                    datum, boundary, ceiling, new double[3], WATER, duration);
                check(Double.isFinite(result.allowedMinutes()) && result.allowedMinutes() > 0,
                    "captured micro-contact keeps a finite forward clock");
                if (result.converged() && result.allowedMinutes() >= duration * (1 - 1e-12)) { accepted = true; break; }
                duration = Math.min(result.allowedMinutes(), duration / 2);
            }
            check(accepted, "captured micro-contact converges before committing");
            check(++steps <= 128, "captured micro-contact completes a whole tick without a parcel-event explosion");
            for (int n = 0; n < boundary.length; n++) if (boundary[n])
                admitted += duration / RotationMath.TICK_MINUTES * result.waterOutward()[n];
            motion = result.motion(); air = result.air(); remaining = Math.max(0, remaining - duration);
            double stored = 0;
            for (int e = 0; e < tubes.size(); e++) {
                stored += tubes.get(e).length() * PipeFilling.MB_PER_METRE * motion[e].contents().fraction();
                for (double q : motion[e].flow()) check(Double.isFinite(q), "the full captured tick retains finite liquid velocities");
            }
            near(before + admitted, stored, 1e-9, "the full captured tick retains water and actual inventory transfers");
        }
    }
    private static void millimetreAirResolution() {
        // The resolution is a physical length, independent of the stored pipe
        // coordinate. Unequal closed gas pressures may communicate through a
        // sub-resolution film without deleting its real water or either gas.
        for (double length : new double[]{.05, .5, 4}) {
            var tubes = List.of(new PipeFilling.Tube(0, 1, length, 0, 0));
            for (double thickness : new double[]{2e-6, 2e-4, 9e-4}) {
                var liquid = PipeContents.of(.4, .4 + thickness / length);
                for (double speed : new double[]{-1, 0, 1}) {
                    PipeMotion[] motion = {PipeMotion.uniform(liquid, speed)};
                    var ambient = PipeAir.UNINITIALIZED.fit(liquid, length);
                    var pockets = ambient.portions().stream().map(p -> new PipeAir.Portion(p.start(), p.end(), p.mass(),
                        (p.start() == 0 ? 4 : 2) * p.energy())).toList();
                    PipeAir[] air = {new PipeAir(pockets)};
                    double initial = energy(tubes, motion, air);
                    double mass = pockets.stream().mapToDouble(PipeAir.Portion::mass).sum();
                    double internal = pockets.stream().mapToDouble(PipeAir.Portion::energy).sum();
                    double pressure = PipeAir.pressure(PipeAir.volume(length, 1 - liquid.fraction()), internal);
                    var result = step(tubes, motion, air, new boolean[2], new boolean[2], new double[2], new double[1], IdealFluidSolver.MAX_STEP);
                    check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP,
                        "sub-millimetre film takes one full conservative step: " + length + "/" + thickness);
                    near(speed, result.motion()[0].flow()[0], 1e-10, "a communicating film keeps its independent liquid velocity");
                    near(liquid.fraction(), result.motion()[0].contents().fraction(), 2e-15, "the coarse air closure retains actual water");
                    near(mass, result.air()[0].portions().stream().mapToDouble(PipeAir.Portion::mass).sum(), 1e-17,
                        "the coarse air closure retains all closed gas mass");
                    near(internal, result.air()[0].portions().stream().mapToDouble(PipeAir.Portion::energy).sum(), 1e-12,
                        "equalisation retains all gas internal energy");
                    for (var p : result.air()[0].portions()) near(pressure,
                        PipeAir.pressure(PipeAir.volume(length, p.end() - p.start()), p.energy()), 1e-7,
                        "communicating gas pressure follows its conserved internal energy and actual volume");
                    near(initial, energy(tubes, result.motion(), result.air()) + result.projectionLoss(), 1e-12,
                        "coarse air resolution preserves the total energy ledger");
                    near(0, result.waterOutward()[0], 0, "coarse closure invents no liquid at a closed boundary");
                    near(0, result.waterOutward()[1], 0, "coarse closure invents no liquid at the other closed boundary");
                }
            }
        }
        // The same coarse air closure still resolves a strongly compressed,
        // nanometre gas pocket when a genuine water column seals it.
        var tubes = List.of(new PipeFilling.Tube(0, 1, .5, 0, 0));
        var liquid = PipeContents.of(.1, 1 - 1e-8);
        PipeMotion[] motion = {PipeMotion.uniform(liquid, .05)};
        PipeAir[] air = {PipeAir.UNINITIALIZED.fit(liquid, .5)};
        double mass = air[0].portions().getLast().mass(), initial = energy(tubes, motion, air);
        for (int i = 0; i < 32; i++) {
            var result = step(tubes, motion, air, new boolean[]{true, false}, new boolean[2], new double[2], new double[1], IdealFluidSolver.MAX_STEP);
            check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP,
                "a resolved water column retains the full implicit compression step");
            check(result.motion()[0].contents().end(0) < 1, "the sealed gas still prevents liquid crossing its wall");
            near(mass, result.air()[0].portions().getLast().mass(), 1e-25, "even a nanometre gas pocket keeps its stored mass");
            near(initial, energy(tubes, result.motion(), result.air()) + result.airBoundaryOutput(), 1e-10,
                "resolved liquid still compresses and recovers the tiny pocket's stored energy");
            motion = result.motion(); air = result.air();
        }
    }
    private static void millimetreMouthAirResolution() {
        for (double length : new double[]{.05, .5, 4}) for (double thickness : new double[]{2e-6, 2e-4, 9e-4})
            for (boolean reverse : new boolean[]{false, true}) {
                var tubes = List.of(new PipeFilling.Tube(reverse ? 1 : 0, reverse ? 0 : 1, length, 0, 0),
                    new PipeFilling.Tube(0, 2, length, 0, 0), new PipeFilling.Tube(0, 3, length, 0, 0));
                double width = thickness / length;
                var liquid = reverse ? PipeContents.of(1 - width, 1) : PipeContents.of(0, width);
                PipeMotion[] motion = {PipeMotion.uniform(liquid, 0), PipeMotion.uniform(PipeContents.EMPTY, 0),
                    PipeMotion.uniform(PipeContents.EMPTY, 0)};
                var air = emptyAir(3);
                for (int e = 0; e < air.length; e++) air[e] = air[e].fit(motion[e].contents(), length);
                air[0] = new PipeAir(air[0].portions().stream().map(p -> new PipeAir.Portion(p.start(), p.end(), p.mass(), 4 * p.energy())).toList());
                double initial = energy(tubes, motion, air);
                double mass = Arrays.stream(air).flatMap(a -> a.portions().stream()).mapToDouble(PipeAir.Portion::mass).sum();
                double pressure = PipeAir.ATMOSPHERE * (6 - 4 * liquid.fraction()) / (3 - liquid.fraction());
                var result = step(tubes, motion, air, new boolean[4], new boolean[4], new double[4], new double[3], IdealFluidSolver.MAX_STEP);
                check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP,
                    "both orientations of a sub-millimetre mouth film complete a full conservative step: "
                        + length + "/" + thickness + "/" + reverse + "/" + result.allowedMinutes() + "/" + result.converged());
                for (int e = 0; e < air.length; e++) for (var p : result.air()[e].portions())
                    near(pressure, PipeAir.pressure(PipeAir.volume(length, p.end() - p.start()), p.energy()), 1e-7,
                        "a mouth film communicates its real back-face gas with the adjoining dry arms");
                near(liquid.fraction(), Arrays.stream(result.motion()).mapToDouble(m -> m.contents().fraction()).sum(), 1e-15,
                    "mouth air equalisation retains all actual water");
                near(mass, Arrays.stream(result.air()).flatMap(a -> a.portions().stream()).mapToDouble(PipeAir.Portion::mass).sum(), 1e-17,
                    "mouth air equalisation retains the entire closed gas inventory");
                near(initial, energy(tubes, result.motion(), result.air()) + result.projectionLoss(), 1e-12,
                    "mouth air equalisation preserves the total energy ledger");
            }
    }
    private static void independentGasPressureScales() {
        // Adding a disconnected, uniformly pressurised pipe cannot change the
        // actual discharge of this modestly compressed pocket into a tank.
        // Compare both edge orders, including pressure differences that are
        // smaller than one ULP of the distant pocket's pressure.
        var liquid = PipeContents.of(0, .5);
        var ambient = PipeAir.UNINITIALIZED.fit(liquid, 1);
        var compressed = new PipeAir(ambient.portions().stream()
            .map(p -> new PipeAir.Portion(p.start(), p.end(), p.mass(), 2 * p.energy())).toList());
        var isolated = step(List.of(new PipeFilling.Tube(0, 1, 1, 0, 0)),
            new PipeMotion[]{PipeMotion.uniform(liquid, 0)}, new PipeAir[]{compressed},
            new boolean[]{true, false}, new boolean[2], new double[2], new double[1], IdealFluidSolver.MAX_STEP);
        check(isolated.converged() && isolated.allowedMinutes() == IdealFluidSolver.MAX_STEP
            && isolated.motion()[0].flow()[0] < -1, "the isolated low-pressure pocket really discharges into its tank");
        for (double scale : new double[]{1e8, 1e16, 1e18, 1e24}) for (boolean highFirst : new boolean[]{false, true}) {
            int high = highFirst ? 0 : 1, low = 1 - high;
            var highTube = new PipeFilling.Tube(0, 1, 1, 0, 0);
            var lowTube = new PipeFilling.Tube(2, 3, 1, 0, 0);
            var tubes = highFirst ? List.of(highTube, lowTube) : List.of(lowTube, highTube);
            PipeMotion[] motion = new PipeMotion[2];
            motion[high] = PipeMotion.uniform(PipeContents.of(.25, .75), 0);
            motion[low] = PipeMotion.uniform(liquid, 0);
            PipeAir[] air = new PipeAir[2]; air[low] = compressed;
            air[high] = new PipeAir(PipeAir.UNINITIALIZED.fit(motion[high].contents(), 1).portions().stream()
                .map(p -> new PipeAir.Portion(p.start(), p.end(), p.mass(), scale * p.energy())).toList());
            var result = step(tubes, motion, air, new boolean[]{false, false, true, false}, new boolean[4],
                new double[4], new double[2], IdealFluidSolver.MAX_STEP);
            check(result.converged() && result.allowedMinutes() == IdealFluidSolver.MAX_STEP,
                "distant pressure scales do not shorten the physical step: " + scale + "/" + highFirst);
            near(isolated.motion()[0].flow()[0], result.motion()[low].flow()[0], 0,
                "the independent pipe preserves its discharge despite a distant extreme pressure");
            near(isolated.motion()[0].contents().fraction(), result.motion()[low].contents().fraction(), 0,
                "independent pressure components transport identical actual water");
            near(isolated.waterOutward()[0], result.waterOutward()[2], 0,
                "independent pressure components deliver identical water to the real inventory");
            near(isolated.pressure()[1], result.pressure()[3], 0,
                "a large synthetic datum cannot erase the smaller physical pressure");
            near(isolated.air()[0].portions().getFirst().energy(), result.air()[low].portions().getFirst().energy(), 0,
                "the smaller pocket preserves its independent energy exchange");
            near(0, result.motion()[high].flow()[0], 0,
                "the equally pressurised high-pressure water stays exactly at rest");
            for (int p = 0; p < air[high].portions().size(); p++) {
                near(air[high].portions().get(p).mass(), result.air()[high].portions().get(p).mass(), 0,
                    "the independent high-pressure pocket retains its gas mass");
                near(air[high].portions().get(p).energy(), result.air()[high].portions().get(p).energy(), 0,
                    "the independent high-pressure pocket retains its gas internal energy");
            }
        }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    private static void near(double expected, double actual, double tolerance, String message) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance, message + ": " + expected + " / " + actual);
    }
}
