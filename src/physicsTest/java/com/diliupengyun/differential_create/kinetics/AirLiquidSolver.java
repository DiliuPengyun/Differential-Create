package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.math.BigDecimal;
import java.math.MathContext;

/** Historical offline reference, excluded from the mod's runtime source set.
 * Contact graph of actual liquid plugs and lumped compressible air regions.
 * Dry space is a pressure boundary, never a zero-mass liquid connection.
 * Ambient air is passive: the existing liquid pump is not a self-priming compressor. */
public final class AirLiquidSolver {
    public static final double RELATIVE_TOLERANCE = 2e-12;
    // Spatial/model resolution, separate from the unchanged solve/work tolerances.
    // A reduction retains water, momentum and gas internal energy; any liquid
    // collision loss remains in the explicit energy ledger (energy unit = 20 J).
    // One millimetre owns 0.1 mB at this pipe area. Finer liquid still moves
    // with its real mass and velocity, but cannot form a sealed air piston.
    // Resolved water columns keep every trapped bubble, however small.
    private static final double MICROSCOPIC_FILM_METRES = 1e-3;
    private static final double MICROSCOPIC_PACKET_METRES = 1e-2;
    private static final double MICROSCOPIC_MODE_ENERGY = 1e-8;
    private AirLiquidSolver() {}
    public record Result(PipeMotion[] motion, PipeAir[] air, double[] waterOutward,
                         double[] flow, double[] midpoint, double[] fromFlow, double[] toFlow, double[] pressure, double[] pumpTorque, double[] pumpHead,
                         double pumpWork, double airEnergyChange, double airBoundaryOutput,
                         double projectionLoss, double allowedMinutes, boolean converged) {}
    private record Part(int edge, PipeContents contents, double flow, int from, int to, boolean nascent) {}
    private record Gap(int edge, double start, double end, int group) {}
    private record AirStep(double volume, double mass, double energy, double pressure, double output, double change, double gauge) {}
    private record MouthTransfer(PipeMotion[] motion, double[] delivered, double impulseLoss) {}
    record Prepared(PipeMotion[] beforeHandoff, MouthTransfer handoff, PipeAir[] air) {
        PipeContents[] contacts() { return Arrays.stream(handoff.motion).map(PipeMotion::contents).toArray(PipeContents[]::new); }
    }
    private record LiquidRoute(int receiver, int wetTarget, int wetNode) {}

    private static final class Gas {
        double volume, mass, energy;
        boolean atmosphere;
        double ceiling = Double.POSITIVE_INFINITY;
        double slope(AirStep next) {
            if (atmosphere || energy == 0) return 0;
            double delta = next.change;
            if (Double.isInfinite(ceiling)) return Math.abs(delta) < .5 * volume
                ? PipeAir.pressureSlopeChange(volume, energy, delta) : PipeAir.pressureSlope(volume, energy, next.volume);
            if (next.volume == 0) return Math.min(0, (ceiling - next.pressure) / delta);
            double firstEnergy = Math.min(energy, PipeAir.internalEnergy(volume, ceiling));
            if (PipeAir.pressure(next.volume, PipeAir.compressedEnergy(volume, firstEnergy, next.volume)) <= ceiling)
                return Math.abs(delta) < .5 * volume ? PipeAir.pressureSlopeChange(volume, firstEnergy, delta)
                    : PipeAir.pressureSlope(volume, firstEnergy, next.volume);
            // The same secant identity applies through a bubble-relief event.
            // Before relief, use the analytic derivative even for sub-ULP work.
            return delta == 0 ? 0 : Math.min(0, (PipeAir.pressure(next.volume, next.energy) - next.pressure) / delta);
        }
        AirStep step(double nextVolume) {
            return step(nextVolume, nextVolume - volume);
        }
        AirStep stepChange(double change) {
            return step(volume + change, change);
        }
        private AirStep step(double nextVolume, double change) {
            double before = PipeAir.availableEnergy(volume, mass, energy);
            if (atmosphere) {
                double nextMass = PipeAir.DENSITY * nextVolume, nextEnergy = PipeAir.internalEnergy(nextVolume, PipeAir.ATMOSPHERE);
                return new AirStep(nextVolume, nextMass, nextEnergy, PipeAir.ATMOSPHERE, before, change, 0);
            }
            if (energy == 0) return new AirStep(nextVolume, mass, 0, 0, 0, change, -PipeAir.ATMOSPHERE);
            if (!(nextVolume > 0) && Double.isInfinite(ceiling)) return new AirStep(nextVolume, mass, energy, Double.POSITIVE_INFINITY, 0, change, Double.POSITIVE_INFINITY);
            double firstMass = mass, firstEnergy = energy;
            double firstPressure = PipeAir.pressure(volume, energy);
            // A submerged tank port can discharge bubbles, but supplies no ambient air.
            if (firstPressure > ceiling) {
                double ratio = Math.pow(ceiling / firstPressure, 1 / PipeAir.GAMMA);
                firstMass *= ratio; firstEnergy = PipeAir.internalEnergy(volume, ceiling); firstPressure = ceiling;
            }
            double nextEnergy = PipeAir.compressedEnergy(volume, firstEnergy, nextVolume), nextMass = firstMass;
            double average = Math.abs(change) < .5 * volume ? PipeAir.averagePressureChange(volume, firstEnergy, change)
                : PipeAir.averagePressure(volume, firstEnergy, nextVolume);
            double gauge = Math.abs(change) < 1e-4 * volume ? PipeAir.averageGaugePressureChange(volume, firstEnergy, change)
                : average - PipeAir.ATMOSPHERE;
            if (nextVolume == 0 || PipeAir.pressure(nextVolume, nextEnergy) > ceiling) {
                double crossingChange = volume * Math.expm1(Math.log1p((firstPressure - ceiling) / ceiling) / PipeAir.GAMMA);
                double crossing = volume + crossingChange;
                nextMass = firstMass * nextVolume / crossing;
                nextEnergy = PipeAir.internalEnergy(nextVolume, ceiling);
                // Add the pre-relief compression work to the constant-pressure
                // remainder. Subtracting two almost equal internal energies
                // and volumes at the relief threshold loses the pressure even
                // though their independently stored change is still resolved.
                double excess = change == 0 || crossingChange == 0 ? 0
                    : (PipeAir.averagePressureChange(volume, firstEnergy, crossingChange) - ceiling) * crossingChange / change;
                average = ceiling + excess;
                gauge = ceiling - PipeAir.ATMOSPHERE + excess;
            }
            double after = PipeAir.availableEnergy(nextVolume, nextMass, nextEnergy);
            double work = gauge * change / PipeAir.JOULES_PER_ENERGY;
            return new AirStep(nextVolume, nextMass, nextEnergy, average, before - after - work, change, gauge);
        }
    }
    private static final class Union {
        final int[] parent;
        Union(int size) { parent = new int[size]; for (int i = 0; i < size; i++) parent[i] = i; }
        int root(int i) { while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; } return i; }
        void join(int a, int b) { parent[root(a)] = root(b); }
    }

    /** Pressures supplied by inventories are gauge pressures in the solver's force units.
     * airCeiling is absolute Pa, infinity for walls; atmosphere marks bidirectional vents.
     * rpm is zero on ordinary pipes. Trial state is detached until the common clock commits. */
    public static Result sample(List<PipeFilling.Tube> tubes, List<PipeFilling.Tube> physicalTubes, int nodes,
                                PipeMotion[] state, PipeAir[] airState, boolean[] boundary, boolean[] supplies,
                                boolean[] receives, double[] datum, boolean[] atmosphere, double[] airCeiling,
                                double[] rpm, IdealFluidSolver.Material material, double minutes) {
        boolean[] open = new boolean[tubes.size()]; Arrays.fill(open, true);
        return sample(tubes, physicalTubes, nodes, state, airState, boundary, supplies, receives, datum,
            atmosphere, airCeiling, rpm, material, minutes, open);
    }
    public static Result sample(List<PipeFilling.Tube> tubes, List<PipeFilling.Tube> physicalTubes, int nodes,
                                PipeMotion[] state, PipeAir[] airState, boolean[] boundary, boolean[] supplies,
                                boolean[] receives, double[] datum, boolean[] atmosphere, double[] airCeiling,
                                double[] rpm, IdealFluidSolver.Material material, double minutes, boolean[] open) {
        return samplePrepared(tubes, physicalTubes, nodes, state, airState, boundary, supplies, receives, datum,
            atmosphere, airCeiling, rpm, material, minutes, open, null);
    }
    static Result samplePrepared(List<PipeFilling.Tube> tubes, List<PipeFilling.Tube> physicalTubes, int nodes,
                                 PipeMotion[] state, PipeAir[] airState, boolean[] boundary, boolean[] supplies,
                                 boolean[] receives, double[] datum, boolean[] atmosphere, double[] airCeiling,
                                 double[] rpm, IdealFluidSolver.Material material, double minutes, boolean[] open, Prepared prepared) {
        try {
            return sample(tubes, physicalTubes, nodes, state, airState, boundary, supplies, receives, datum,
                atmosphere, airCeiling, rpm, material, minutes, open, new boolean[nodes], new boolean[nodes], prepared);
        } catch (IdealFluidSolver.ConstraintConvergenceException failure) {
            if (minutes < 1e-17) throw failure;
            return stationary(state, airState, nodes, tubes.size(), minutes / 2, false);
        }
    }
    private static Result sample(List<PipeFilling.Tube> tubes, List<PipeFilling.Tube> physicalTubes, int nodes,
                                 PipeMotion[] state, PipeAir[] airState, boolean[] boundary, boolean[] supplies,
                                 boolean[] receives, double[] datum, boolean[] atmosphere, double[] airCeiling,
                                 double[] rpm, IdealFluidSolver.Material material, double minutes, boolean[] open, boolean[] released, boolean[] returning, Prepared prepared) {
        return new Step(tubes, physicalTubes, nodes, state, airState, boundary, supplies, receives, datum,
            atmosphere, airCeiling, rpm, material, minutes, open, released, returning, prepared).run();
    }

    /** Detached state for one trial. Keep topology, Newton iteration and state
     * reconstruction in separate methods so the JVM can compile each hot stage. */
    private static final class Step {
        final List<PipeFilling.Tube> tubes, physicalTubes;
        final int nodes, count;
        final PipeMotion[] state, beforeHandoff;
        final PipeAir[] airState;
        final boolean[] boundary, supplies, receives, atmosphere, open, released, returning;
        final double[] datum, airCeiling, rpm;
        final IdealFluidSolver.Material material;
        final double minutes;
        final Prepared prepared;
        final MouthTransfer handoff;
        boolean[] wet, physicallyWet, borders, inventory, enabled;
        boolean[][] freeContact;
        int[] firstGap, lastGap, dryNode, groupIndex;
        int[][] partsByEdge;
        int totalNodes;
        Union union;
        List<Gas> gas;
        List<Gap> grouped;
        List<Part> parts;
        List<Integer> gasAtNode;
        List<IdealFluidSolver.Edge> edges;
        List<PipeFilling.Tube> subTubes;
        PipeContents[] content;
        double[] supplied, pressure, initial, fullMass, startMass, guess, origin, pumpGain;
        double inputLoss, mouthEvent, allowed;
        IdealFluidSolver.Trial trial;
        PipeFilling.Prediction prediction;
        AirStep[] airStep;
        final ComplianceGraph compliance = new ComplianceGraph();

        Step(List<PipeFilling.Tube> tubes, List<PipeFilling.Tube> physicalTubes, int nodes,
             PipeMotion[] state, PipeAir[] airState, boolean[] boundary, boolean[] supplies,
             boolean[] receives, double[] datum, boolean[] atmosphere, double[] airCeiling,
             double[] rpm, IdealFluidSolver.Material material, double minutes, boolean[] open,
             boolean[] released, boolean[] returning, Prepared prepared) {
            this.tubes = tubes; this.physicalTubes = physicalTubes; this.nodes = nodes; count = tubes.size();
            this.boundary = boundary; this.supplies = supplies; this.receives = receives; this.datum = datum;
            this.atmosphere = atmosphere; this.airCeiling = airCeiling; this.rpm = rpm; this.material = material;
            this.minutes = minutes; this.open = open; this.released = released; this.returning = returning;
            this.prepared = prepared == null ? prepareContacts(tubes, state, airState, open, boundary, receives, datum, rpm, material) : prepared;
            this.airState = this.prepared.air; beforeHandoff = this.prepared.beforeHandoff;
            handoff = this.prepared.handoff; this.state = handoff.motion;
        }

        Result run() {
            prepareTopology();
            Result event = locateFreeContacts();
            if (event != null) return event;
            prepareLiquid();
            Result retry = solveLiquid();
            return retry == null ? finish() : retry;
        }

        private void prepareTopology() {
            wet = new boolean[nodes]; physicallyWet = new boolean[nodes];
            boolean[] sealed = new boolean[nodes];
            for (int n = 0; n < nodes; n++) if (boundary[n] && supplies[n]) wet[n] = physicallyWet[n] = true;
            var gaps = new ArrayList<Gap>(); var portions = new ArrayList<PipeAir.Portion>();
            firstGap = new int[count]; lastGap = new int[count]; Arrays.fill(firstGap, -1); Arrays.fill(lastGap, -1);
            for (int e = 0; e < count; e++) {
                var liquid = state[e].contents(); var tube = tubes.get(e); var physical = physicalTubes.get(e);
                if (open[e] && !released[physical.from()] && liquid.intervals() > 0 && liquid.start(0) == 0) { wet[tube.from()] = true; physicallyWet[physical.from()] = true; }
                if (open[e] && !released[physical.to()] && liquid.intervals() > 0 && liquid.end(liquid.intervals() - 1) == 1) { wet[tube.to()] = true; physicallyWet[physical.to()] = true; }
                if (open[e] && liquid.intervals() > 0) {
                    if (!released[physical.from()] && liquid.start(0) == 0 && liquid.end(0) * tube.length() > MICROSCOPIC_FILM_METRES)
                        sealed[physical.from()] = true;
                    int last = liquid.intervals() - 1;
                    if (!released[physical.to()] && liquid.end(last) == 1 && (1 - liquid.start(last)) * tube.length() > MICROSCOPIC_FILM_METRES)
                        sealed[physical.to()] = true;
                }
                var air = airState[e].fit(liquid, tube.length());
                for (var portion : air.portions()) {
                    int index = gaps.size();
                    gaps.add(new Gap(e, portion.start(), portion.end(), index)); portions.add(portion);
                    if (portion.start() == 0) firstGap[e] = index;
                    if (portion.end() == 1) lastGap[e] = index;
                    // Apply the same nonsealing film rule at a real mouth as
                    // inside a pipe. Its gas port is the film's back face; the
                    // liquid itself still owns its full volume and momentum.
                    if (liquid.intervals() > 0) {
                        if (liquid.start(0) == 0 && liquid.end(0) * tube.length() <= MICROSCOPIC_FILM_METRES
                            && portion.start() == liquid.end(0)) firstGap[e] = index;
                        int last = liquid.intervals() - 1;
                        if (liquid.end(last) == 1 && (1 - liquid.start(last)) * tube.length() <= MICROSCOPIC_FILM_METRES
                            && portion.end() == liquid.start(last)) lastGap[e] = index;
                    }
                }
            }
            union = new Union(gaps.size()); dryNode = new int[nodes]; Arrays.fill(dryNode, -1);
            for (int e = 0; e < count; e++) {
                if (!open[e]) continue;
                var physical = physicalTubes.get(e);
                for (int side = 0; side < 2; side++) {
                    int gap = side == 0 ? firstGap[e] : lastGap[e], node = side == 0 ? physical.from() : physical.to();
                    if (gap < 0) continue;
                    if (dryNode[node] < 0) dryNode[node] = gap;
                    else if (!sealed[node]) union.join(dryNode[node], gap);
                }
            }
            releaseUnresolvedAirFilms(tubes, state, gaps, union);
            gas = new ArrayList<Gas>(); groupIndex = new int[gaps.size()]; Arrays.fill(groupIndex, -1);
            grouped = new ArrayList<Gap>();
            for (int i = 0; i < gaps.size(); i++) {
                int root = union.root(i);
                if (groupIndex[root] < 0) { groupIndex[root] = gas.size(); gas.add(new Gas()); }
                int group = groupIndex[root]; var gap = gaps.get(i); var pocket = gas.get(group); var portion = portions.get(i);
                pocket.volume += PipeAir.volume(tubes.get(gap.edge).length(), gap.end - gap.start);
                pocket.mass += portion.mass(); pocket.energy += portion.energy();
                var physical = physicalTubes.get(gap.edge);
                if (open[gap.edge] && i == firstGap[gap.edge] && boundary[physical.from()]) vent(pocket, physical.from(), atmosphere, airCeiling);
                if (open[gap.edge] && i == lastGap[gap.edge] && boundary[physical.to()]) vent(pocket, physical.to(), atmosphere, airCeiling);
                grouped.add(new Gap(gap.edge, gap.start, gap.end, group));
            }
            parts = new ArrayList<Part>(); gasAtNode = new ArrayList<Integer>();
            for (int n = 0; n < nodes; n++) gasAtNode.add(-1);
            for (int e = 0; e < count; e++) {
                var liquid = state[e].contents(); var tube = tubes.get(e);
                var physical = physicalTubes.get(e);
                for (int i = 0; i < liquid.intervals(); i++) {
                    double a = liquid.start(i), b = liquid.end(i);
                    int from = a == 0 ? released[physical.from()]
                        ? front(gasAtNode, groupIndex[union.root(dryNode[physical.from()])]) : tube.from()
                        : front(gasAtNode, groupAt(grouped, e, a, false));
                    int to = b == 1 ? released[physical.to()]
                        ? front(gasAtNode, groupIndex[union.root(dryNode[physical.to()])]) : tube.to()
                        : front(gasAtNode, groupAt(grouped, e, b, true));
                    parts.add(new Part(e, PipeContents.of(a, b), state[e].flow()[i], from, to, false));
                }
                // A liquid contact can admit a new plug at a dry mouth. Its trial
                // inertance comes from the actually admitted mass, not the empty length.
                if (open[e] && (liquid.intervals() == 0 || liquid.start(0) != 0) && wet[tube.from()])
                    parts.add(new Part(e, PipeContents.EMPTY, 0, tube.from(), front(gasAtNode, groupAt(grouped, e, 0, true)), true));
                if (open[e] && (liquid.intervals() == 0 || liquid.end(liquid.intervals() - 1) != 1) && wet[tube.to()])
                    parts.add(new Part(e, PipeContents.EMPTY, 0, front(gasAtNode, groupAt(grouped, e, 1, false)), tube.to(), true));
            }
            partsByEdge = indexParts(parts, count, nodes);
        }

        private Result locateFreeContacts() {
            // A freely translating front can reach a real pipe mouth long before
            // the requested horizon. Locate it before a coefficient prediction
            // drains its thin parcel into the temporary air boundary. A sealed
            // pocket must still use the implicit solve, which can turn it around.
            double freeEvent = minutes; mouthEvent = minutes; int[] degree = new int[nodes];
            for (var tube : physicalTubes) { degree[tube.from()]++; degree[tube.to()]++; }
            freeContact = new boolean[parts.size()][2];
            for (int p = 0; p < parts.size(); p++) {
                var part = parts.get(p); var tube = physicalTubes.get(part.edge);
                for (int side = 0; side < 2; side++) {
                    int face = side == 0 ? part.from : part.to, mouth = side == 0 ? tube.from() : tube.to();
                    int gap = side == 0 ? firstGap[part.edge] : lastGap[part.edge];
                    boolean pipeContact = degree[mouth] > 1 && gap >= 0 && face >= nodes
                        && groupIndex[union.root(gap)] == gasAtNode.get(face);
                    if (face >= nodes) {
                        var pocket = gas.get(gasAtNode.get(face));
                        boolean pressureFree = pocket.atmosphere || pocket.energy == 0
                            || PipeAir.pressure(pocket.volume, pocket.energy) >= pocket.ceiling;
                        freeContact[p][side] = pocket.atmosphere || pipeContact && pressureFree;
                        if (pipeContact && !part.nascent && part.from >= nodes && part.to >= nodes)
                            freeContact[p][side] = canReachMouth(part, parts, side == 1 ? 1 : -1, tubes.get(part.edge),
                                gasAtNode, gas, material, rpm[part.edge]);
                    }
                    if (!part.nascent && part.flow != 0 && side == (part.flow > 0 ? 1 : 0)
                        && (freeContact[p][side] || pipeContact))
                        mouthEvent = Math.min(mouthEvent, part.contents.travelToMouth(part.flow, 0) * tubes.get(part.edge).length()
                            * PipeFilling.MB_PER_METRE * RotationMath.TICK_MINUTES / Math.abs(part.flow));
                }
                if (!part.nascent && part.flow != 0 && part.contents != PipeContents.FULL && freeContact[p][part.flow > 0 ? 1 : 0])
                    freeEvent = Math.min(freeEvent, part.contents.travelToMouth(part.flow, 0) * tubes.get(part.edge).length()
                        * PipeFilling.MB_PER_METRE * RotationMath.TICK_MINUTES / Math.abs(part.flow));
            }
            if (freeEvent < minutes * (1 - 1e-12)) return stationary(state, airState, nodes, count, freeEvent, true);
            return null;
        }

        private void prepareLiquid() {
            totalNodes = gasAtNode.size();
            borders = Arrays.copyOf(boundary, totalNodes); inventory = Arrays.copyOf(boundary, totalNodes);
            supplied = new double[totalNodes]; pressure = Arrays.copyOf(datum, totalNodes);
            for (int n = 0; n < nodes; n++) supplied[n] = supplies[n] ? 1 : 0;
            for (int n = nodes; n < totalNodes; n++) borders[n] = inventory[n] = true;
            edges = new ArrayList<IdealFluidSolver.Edge>(); subTubes = new ArrayList<PipeFilling.Tube>();
            initial = new double[parts.size()]; fullMass = new double[parts.size()];
            content = new PipeContents[parts.size()]; enabled = new boolean[parts.size()]; Arrays.fill(enabled, true);
            inputLoss = handoff.impulseLoss;
            for (int e = 0; e < count; e++) inputLoss += .5 * material.inertia(tubes.get(e).length()) * state[e].mergeLoss();
            for (int p = 0; p < parts.size(); p++) {
                var part = parts.get(p); var tube = tubes.get(part.edge);
                fullMass[p] = material.inertia(tube.length()); initial[p] = part.flow; content[p] = part.contents;
                enabled[p] = open[part.edge];
                edges.add(new IdealFluidSolver.Edge(part.from, part.to, fullMass[p]));
                subTubes.add(new PipeFilling.Tube(part.from, part.to, tube.length(), tube.fromHeight(), tube.toHeight()));
                if (!part.nascent) {
                    if (part.to < nodes && boundary[part.to] && content[p].atEnd() && initial[p] > 0 && !receives[part.to]) enabled[p] = false;
                    if (part.from < nodes && boundary[part.from] && content[p].atStart() && initial[p] < 0 && !receives[part.from]) enabled[p] = false;
                }
            }
            guess = initial.clone();
            startMass = new double[initial.length]; double[] seedForce = new double[initial.length];
            for (int p = 0; p < initial.length; p++) {
                startMass[p] = fullMass[p] * (content[p].fraction() == 0 ? 1e-30 : content[p].fraction());
                seedForce[p] = -material.gravityForce(subTubes.get(p).toHeight() - subTubes.get(p).fromHeight()) * content[p].fraction()
                    + IdealFluidSolver.coupling(material, rpm[parts.get(p).edge], initial[p]) * rpm[parts.get(p).edge] * content[p].fraction();
            }
            for (int n = nodes; n < totalNodes; n++) pressure[n] = IdealFluidSolver.pressureForce(gas.get(gasAtNode.get(n)).stepChange(0).gauge);
            {
                var seedGraph = new ReducedFluidGraph(totalNodes, edges, borders, enabled);
                // The full-length seed estimates head only. Existing plug momentum
                // belongs to its real mass, never to this full-pipe trial weight.
                var seed = seedGraph.sample(new double[initial.length], fullMass, seedForce, pressure, minutes);
                double[] projected = seedGraph.sample(initial, startMass, new double[initial.length], new double[totalNodes], minutes).flow();
                if (Arrays.stream(initial).allMatch(q -> q == 0)) guess = seed.midpoint().clone();
                for (int p = 0; p < parts.size(); p++) if (parts.get(p).nascent) {
                    var part = parts.get(p); boolean forward = part.from < nodes;
                    double drop = forward ? seed.pressure()[part.from] - pressure[part.to] : seed.pressure()[part.to] - pressure[part.from];
                    double incoming = (forward ? 1 : -1) * projected[p];
                    guess[p] = (forward ? 1 : -1) * Math.max(0, .5 * incoming + Math.sqrt(Math.max(0, drop) * RotationMath.TICK_MINUTES
                        * subTubes.get(p).length() * PipeFilling.MB_PER_METRE / (2 * fullMass[p])));
                }
                if (Arrays.stream(initial).allMatch(q -> q == 0)) {
                    double maximum = Arrays.stream(guess).map(Math::abs).max().orElse(0);
                    if (maximum > IdealFluidSolver.REFERENCE_FLOW) for (int p = 0; p < guess.length; p++) guess[p] *= IdealFluidSolver.REFERENCE_FLOW / maximum;
                }
            }
        }

        private Result solveLiquid() {
            origin = initial.clone(); double[] applied = null;
            double[] volumeGuess = new double[gas.size()];
            // Store changes directly: adding them to a large region before Newton
            // would quantise its compliance and the force on a thin liquid plug.
            allowed = minutes; double provisionalEvent = minutes; boolean converged = false;
            ReducedFluidGraph graph = null;
            for (int pass = 0; pass < 160; pass++) {
                if (graph == null || !graph.matches(borders, enabled)) {
                    graph = new ReducedFluidGraph(totalNodes, edges, borders, enabled);
                    origin = graph.sample(initial, startMass, new double[initial.length], new double[totalNodes], minutes).flow();
                }
                // A reservoir's newly admitted parcel has no stored inlet velocity;
                // pressure and momentum flux establish its birth-step velocity.
                // At an internal liquid junction, inherit the incoming velocity
                // through the initial continuity projection instead. Overwriting
                // that value would make the final velocities on its two arms differ
                // and repeatedly fragment the newly admitted water.
                for (int p = 0; p < parts.size(); p++) {
                    var part = parts.get(p);
                    if (reservoirBirth(part, boundary, nodes)) origin[p] = guess[p];
                }
                prediction = PipeFilling.predictResolved(subTubes, totalNodes, content, origin, guess, inventory, supplied, material, minutes);
                double[] outwardGuess = new double[nodes];
                for (int p = 0; p < parts.size(); p++) {
                    var part = parts.get(p);
                    if (part.from < nodes) outwardGuess[part.from] += guess[p];
                    if (part.to < nodes) outwardGuess[part.to] -= guess[p];
                }
                for (int n = 0; n < nodes; n++) if (boundary[n]) {
                    // A wet inventory keeps its liquid pressure when a short plug
                    // enters it. The fraction of a long preview spent delivering
                    // water is not the fraction of time that the receiving tank
                    // becomes vacuum. Only an exhausted, outward-drawing source
                    // needs its actual supplied liquid fraction.
                    double fraction = supplies[n] || outwardGuess[n] <= 0 ? 1
                        : Math.max(0, Math.min(1, prediction.waterOutward()[n] / outwardGuess[n]));
                    double dryPressure = atmosphere[n] ? 0 : -IdealFluidSolver.pressureForce(PipeAir.ATMOSPHERE);
                    pressure[n] = datum[n] * fraction + dryPressure * (1 - fraction);
                }
                double[] mass = prediction.inertia().clone(); applied = prediction.correction().clone(); pumpGain = new double[parts.size()];
                for (int p = 0; p < parts.size(); p++) {
                    var part = parts.get(p);
                    if (!part.nascent && part.from >= nodes && part.to >= nodes) {
                        // Both faces remain material liquid/air interfaces until a
                        // real mouth event. A long coefficient preview must not
                        // drain this plug into either temporary air-pressure node;
                        // the implicit gas response can reverse it before arrival.
                        mass[p] = fullMass[p] * content[p].fraction();
                        applied[p] = -material.gravityForce(subTubes.get(p).toHeight() - subTubes.get(p).fromHeight()) * content[p].fraction();
                    }
                    double fraction = mass[p] / fullMass[p]; double speed = rpm[part.edge];
                    pumpGain[p] = IdealFluidSolver.coupling(material, speed, guess[p]) * fraction;
                    applied[p] += pumpGain[p] * speed;
                    int destination = guess[p] >= 0 ? part.to : part.from;
                    double outletFraction = destination < nodes && boundary[destination] && guess[p] != 0
                        ? Math.max(0, Math.min(1, -prediction.waterOutward()[destination] / Math.abs(guess[p]))) : 0;
                    applied[p] -= material.outletForce(guess[p]) * outletFraction;
                    // Pump flow dependence is solved implicitly, using the same
                    // cancelled tangent as the full-liquid pressure solver.
                    double slope = Math.min(0, IdealFluidSolver.pumpSlope(material, speed, guess[p])) * fraction
                        - (guess[p] == 0 ? 0 : 2 * Math.abs(material.outletForce(guess[p]) / guess[p]) * outletFraction);
                    // Linearise both the transported-mass force and its changing
                    // inertia. This is essential when a moving jet creates a new
                    // plug: its actual admitted mass starts at zero.
                    double flux = guess[p] == 0 ? 0 : 2 * (mass[p] - fullMass[p] * content[p].fraction()) / (minutes * guess[p]);
                    // The newborn inlet's virtual initial velocity is itself the
                    // unknown. Its changing-mass inertia already supplies the
                    // momentum-flux tangent; adding the old-velocity tangent a
                    // second time would halve every Newton correction.
                    if (!reservoirBirth(part, boundary, nodes)) slope += Math.min(0, -flux * (3 * guess[p] - 2 * origin[p]));
                    mass[p] = Math.max(mass[p], fullMass[p] * 1e-30) - .5 * minutes * slope;
                    applied[p] += slope * (origin[p] - guess[p]);
                }
                double[] nextVolume = volumeGuess.clone();
                airStep = new AirStep[gas.size()];
                boolean feasible = true;
                for (int g = 0; g < gas.size(); g++) {
                    var pocket = gas.get(g);
                    if (!(pocket.volume + nextVolume[g] > 0) && pocket.energy > 0 && !pocket.atmosphere && Double.isInfinite(pocket.ceiling)) { feasible = false; break; }
                    airStep[g] = pocket.stepChange(Math.max(-pocket.volume, nextVolume[g]));
                    if (!Double.isFinite(airStep[g].pressure) || !Double.isFinite(airStep[g].energy)) { feasible = false; break; }
                }
                if (!feasible) {
                    for (int p = 0; p < guess.length; p++) guess[p] *= .5;
                    for (int g = 0; g < gas.size(); g++) volumeGuess[g] *= .5;
                    continue;
                }
                for (int n = nodes; n < totalNodes; n++) pressure[n] = IdealFluidSolver.pressureForce(airStep[gasAtNode.get(n)].gauge);
                if (Arrays.stream(mass).anyMatch(m -> !(m > 0) || !Double.isFinite(m)))
                    throw new IllegalStateException("Invalid liquid trial mass: " + Arrays.toString(mass) + "; guess=" + Arrays.toString(guess)
                        + "; origin=" + Arrays.toString(origin) + "; pressure=" + Arrays.toString(pressure));
                double[] candidateVolume = volumeGuess.clone();
                trial = coupledAir(edges, enabled, borders, origin, mass, applied, pressure, minutes, nodes, gasAtNode, gas, airStep, candidateVolume, graph, compliance);
                double[] displacement = gasDisplacement(parts, trial.midpoint(), gasAtNode, nodes, gas.size(), minutes);
                for (int g = 0; g < gas.size(); g++) if (gas.get(g).slope(airStep[g]) == 0) candidateVolume[g] = displacement[g];
                boolean liquidContact = false;
                for (int p = 0; p < parts.size(); p++) {
                    var part = parts.get(p); if (part.nascent) continue;
                    var position = physicalTubes.get(part.edge); double q = trial.midpoint()[p];
                    int node = part.contents.start(0) == 0 && q < 0 ? position.from()
                        : part.contents.end(0) == 1 && q > 0 ? position.to() : -1;
                    if (node >= 0 && released[node]) {
                        // Air admitted behind a departing parcel is not a liquid
                        // sink. When that parcel returns, restore its actual wet
                        // mouth before any transported mass can leave the pipe.
                        released[node] = false; returning[node] = true; liquidContact = true;
                    }
                }
                if (liquidContact) return sample(tubes, physicalTubes, nodes, beforeHandoff, airState, boundary, supplies, receives, datum,
                    atmosphere, airCeiling, rpm, material, minutes, open, released, returning, prepared);
                // When all real water at a mouth moves away, an adjoining dry arm
                // supplies air pressure, not an invented liquid inflow. Locate this
                // contact change before projecting the moving plug to a stop.
                boolean airContact = false;
                for (int node = 0; node < nodes; node++) {
                    if (boundary[node] || released[node] || returning[node] || !physicallyWet[node] || dryNode[node] < 0) continue;
                    double incoming = 0, outgoing = 0, departing = 0;
                    for (int k = 0; k < parts.size(); k++) if (!parts.get(k).nascent && enabled[k]) {
                        var other = parts.get(k); var position = physicalTubes.get(other.edge); double outward = 0, prior = 0;
                        if (position.from() == node && other.contents.start(0) == 0) { outward = trial.midpoint()[k]; prior = other.flow; }
                        if (position.to() == node && other.contents.end(other.contents.intervals() - 1) == 1) { outward = -trial.midpoint()[k]; prior = -other.flow; }
                        incoming += Math.max(0, -outward); outgoing += Math.max(0, outward);
                        departing += Math.max(0, prior);
                    }
                    // Test actual physical mouths, including independent lane nodes
                    // and disabled nascent inlets. A dry lane must not project an
                    // already departing plug to rest before admitting the air behind it.
                    if ((outgoing > 0 || departing > 0) && incoming <= 64 * Math.ulp(incoming + outgoing)) {
                        released[node] = true; airContact = true;
                    }
                }
                if (airContact) return sample(tubes, physicalTubes, nodes, beforeHandoff, airState, boundary, supplies, receives, datum,
                    atmosphere, airCeiling, rpm, material, minutes, open, released, returning, prepared);
                boolean changed = false;
                for (int p = 0; p < parts.size(); p++) {
                    var part = parts.get(p); double q = trial.midpoint()[p];
                    if (part.nascent && (part.from >= nodes ? q >= 0 : q <= 0) && enabled[p]) {
                        double drop = part.from >= nodes ? trial.pressure()[part.to] - pressure[part.from]
                            : trial.pressure()[part.from] - pressure[part.to];
                        // An empty mouth can admit liquid, never return invented
                        // liquid from its air boundary. This one-way contact also
                        // applies when inertia temporarily opposes the pressure.
                        if (q != 0 || drop <= 0) { enabled[p] = false; changed = true; }
                    }
                    int destination = q >= 0 ? part.to : part.from;
                    if (destination < nodes && boundary[destination] && !receives[destination]
                        && (destination == part.to ? content[p].atEnd() : content[p].atStart()) && enabled[p]) { enabled[p] = false; changed = true; }
                }
                if (changed) {
                    guess = trial.midpoint().clone();
                    for (int p = 0; p < guess.length; p++) if (!enabled[p]) guess[p] = 0;
                    continue;
                }
                // Remember a possible crossing during iteration, but let the
                // converged pressure response decide whether a reversal actually
                // reaches it. A provisional mass-flux correction can reverse a
                // thin plug even when the final solution keeps it inside the pipe.
                for (int p = 0; p < parts.size(); p++) if (!parts.get(p).nascent && trial.midpoint()[p] != 0
                    && freeContact[p][trial.midpoint()[p] > 0 ? 1 : 0]) {
                    double contact = PipeFilling.predictResolved(List.of(subTubes.get(p)), totalNodes, new PipeContents[]{content[p]},
                        new double[]{origin[p]}, new double[]{trial.midpoint()[p]}, inventory, supplied, material, minutes).allowedMinutes();
                    provisionalEvent = Math.min(provisionalEvent, contact);
                }
                double residual = 0;
                for (int p = 0; p < guess.length; p++) {
                    double r = trial.midpoint()[p] - guess[p];
                    if (parts.get(p).nascent) {
                        // At an almost equal liquid/air pressure, a newly admitted
                        // infinitesimal mass magnifies one pressure ULP into a
                        // visible velocity residual. Judge that residual in the
                        // actual momentum equation. Retain the computed flow and
                        // leave the independent inventory/work audits unchanged.
                        var part = parts.get(p);
                        double forceScale = Math.abs(applied[p]) + Math.abs(pressure[part.from]) + Math.abs(pressure[part.to]);
                        if (Math.abs(2 * mass[p] * r / minutes) <= 64 * Math.ulp(forceScale)) continue;
                    }
                    residual = Math.max(residual, Math.abs(r) / (1 + Math.abs(trial.midpoint()[p])));
                }
                for (int g = 0; g < gas.size(); g++) if (!gas.get(g).atmosphere && gas.get(g).energy > 0)
                    residual = Math.max(residual, Math.abs(candidateVolume[g] - volumeGuess[g]) / (gas.get(g).volume + Math.abs(gas.get(g).volume + candidateVolume[g])));
                if (residual < RELATIVE_TOLERANCE) { converged = true; break; }
                double[] flowStep = new double[guess.length];
                for (int p = 0; p < guess.length; p++) {
                    flowStep[p] = trial.midpoint()[p] - guess[p];
                    var part = parts.get(p);
                    if (part.nascent) flowStep[p] = (part.from < nodes ? Math.max(0, trial.midpoint()[p])
                        : Math.min(0, trial.midpoint()[p])) - guess[p];
                }
                double[] volumeStep = new double[gas.size()];
                for (int g = 0; g < gas.size(); g++) volumeStep[g] = candidateVolume[g] - volumeGuess[g];
                // Safeguard Newton's trial, rather than repeatedly stepping outside
                // the positive-volume domain and halving the physical clock. This
                // line search changes only the detached iteration, never momentum.
                double line = 1;
                for (int g = 0; g < gas.size(); g++) if (!gas.get(g).atmosphere && gas.get(g).energy > 0
                    && gas.get(g).volume + nextVolume[g] + volumeStep[g] <= 0)
                    line = Math.min(line, .9 * (gas.get(g).volume + nextVolume[g]) / -volumeStep[g]);
                for (int p = 0; p < guess.length; p++) {
                    guess[p] += line * flowStep[p];
                }
                for (int g = 0; g < gas.size(); g++) volumeGuess[g] += line * volumeStep[g];
            }
            if (!converged || trial == null) {
                // If the trial spans a genuine pipe-mouth crossing, retry at that
                // contact before repeatedly halving the entire horizon. A converged
                // sealed-pocket reversal above always takes precedence over this
                // provisional event; no liquid or gas is advanced here.
                double contact = Math.min(mouthEvent, provisionalEvent);
                if (contact > 0 && contact < minutes * (1 - 1e-12))
                    return stationary(state, airState, nodes, count, contact, true);
                if (minutes < 1e-17) throw new IllegalStateException("Air/liquid iteration failed: guess=" + Arrays.toString(guess)
                    + "; initial=" + Arrays.toString(initial) + "; enabled=" + Arrays.toString(enabled)
                    + "; next=" + (trial == null ? "none" : Arrays.toString(trial.midpoint())) + "; pressure=" + Arrays.toString(pressure)
                    + "; gas=" + gas.stream().map(g -> g.volume + "/" + g.energy + "/" + g.atmosphere + "/" + g.ceiling).toList()
                    + "; parts=" + parts.stream().map(p -> p.edge + ":" + p.from + "->" + p.to + Arrays.toString(p.contents.data())).toList());
                return stationary(state, airState, nodes, count, minutes / 2, false);
            }
            return null;
        }

        private Result finish() {
            // First locate events with detached coefficients. Truncating an
            // inadmissible provisional move can reverse another parcel's transport
            // before its own mouth event and carry it beyond a different pipe end.
            var actualPrediction = PipeFilling.predictResolved(subTubes, totalNodes, content, origin, trial.midpoint(), inventory, supplied, material, minutes);
            allowed = Math.min(allowed, Math.min(actualPrediction.allowedMinutes(), prediction.allowedMinutes()));
            // Two independently moving plugs must locate their mutual contact, too.
            for (int e = 0; e < count; e++) {
                int[] ordered = partsByEdge[e];
                for (int i = 1; i < ordered.length; i++) {
                    int left = ordered[i - 1], right = ordered[i]; var a = parts.get(left); var b = parts.get(right);
                    double gap = (b.nascent ? 1 : b.contents.start(0)) - (a.nascent ? 0 : a.contents.end(a.contents.intervals() - 1));
                    double closing = trial.midpoint()[left] - trial.midpoint()[right];
                    if (closing > 0 && gap > 0) allowed = Math.min(allowed, gap * tubes.get(e).length() * PipeFilling.MB_PER_METRE * RotationMath.TICK_MINUTES / closing);
                }
            }
            if (allowed < minutes * (1 - 1e-12)) {
                if (allowed < 1e-18) {
                    var reflected = state.clone(); boolean changed = false;
                    for (int p = 0; p < parts.size(); p++) {
                        var part = parts.get(p); if (part.nascent || part.contents.fraction() > PipeContents.FRONT_ROUNDOFF) continue;
                        double q = trial.midpoint()[p];
                        boolean reversal = part.contents.start(0) == 0 && part.flow > 0 && q < 0
                            || part.contents.end(0) == 1 && part.flow < 0 && q > 0;
                        if (!reversal) continue;
                        var current = reflected[part.edge]; double[] values = current.flow().clone();
                        for (int i = 0; i < current.contents().intervals(); i++) if (current.contents().start(i) == part.contents.start(0)
                            && current.contents().end(i) == part.contents.end(0)) values[i] = -part.flow;
                        reflected[part.edge] = new PipeMotion(current.contents(), values, current.mergeLoss()); changed = true;
                    }
                    if (changed) {
                        // A contact reversal faster than the clock resolution is
                        // an energy-preserving pressure impulse. Complete only a
                        // bounded microscopic handoff, retaining actual water and
                        // recording any collision with the receiving liquid.
                        var completed = transferUnresolvedMouthParcels(tubes, reflected, airState, open, boundary, receives, datum, rpm, material);
                        boolean moved = false;
                        for (int e = 0; e < count; e++) moved |= completed.motion[e].contents() != reflected[e].contents();
                        if (moved) {
                            double[] delivered = handoff.delivered.clone();
                            for (int n = 0; n < nodes; n++) delivered[n] += completed.delivered[n];
                            var combined = new MouthTransfer(completed.motion, delivered, handoff.impulseLoss + completed.impulseLoss);
                            return sample(tubes, physicalTubes, nodes, beforeHandoff, airState, boundary, supplies, receives, datum,
                                atmosphere, airCeiling, rpm, material, minutes, open, released, returning, new Prepared(beforeHandoff, combined, airState));
                        }
                    }
                }
                if (allowed < 1e-18) throw new IllegalStateException("Air/liquid front event below clock precision: " + allowed + "/" + minutes
                    + "; move=" + actualPrediction.allowedMinutes() + "; prediction=" + prediction.allowedMinutes()
                    + "; midpoint=" + Arrays.toString(trial.midpoint())
                    + "; parts=" + parts.stream().map(p -> p.edge + ":" + p.from + "->" + p.to + Arrays.toString(p.contents.data())).toList());
                return stationary(state, airState, nodes, count, allowed, true);
            }
            var move = PipeFilling.sampleResolved(subTubes, totalNodes, content, origin, trial.midpoint(), inventory, supplied, material, minutes);
            var pieces = new ArrayList<List<PipeMotion.Piece>>(); for (int e = 0; e < count; e++) pieces.add(new ArrayList<>());
            double[] liquidOutward = new double[nodes], flow = new double[count], midpoint = new double[count], weights = new double[count];
            double[] fromFlow = new double[count], toFlow = new double[count];
            double[] torque = new double[count], head = new double[count]; double pumpWork = 0;
            for (int p = 0; p < parts.size(); p++) {
                var part = parts.get(p); var next = move.contents()[p];
                double storedFlow = trial.flow()[p];
                double transportedFraction = Math.max(0, 2 * move.inertia()[p] / fullMass[p] - content[p].fraction());
                double rounding = 64 * Math.ulp(content[p].fraction() + next.fraction());
                for (int i = 0; i < next.intervals(); i++) rounding += 4 * (Math.ulp(next.start(i)) + Math.ulp(next.end(i)));
                // Storing front endpoints rounds the actual mass. Within that
                // coordinate bound, retain the transported parcel's kinetic energy
                // rather than magnifying a small length error by a very large q².
                if (transportedFraction > 0 && next.fraction() > 0 && Math.abs(transportedFraction - next.fraction()) <= rounding)
                    storedFlow *= Math.sqrt(transportedFraction / next.fraction());
                for (int i = 0; i < next.intervals(); i++) pieces.get(part.edge).add(new PipeMotion.Piece(next.start(i), next.end(i), storedFlow));
                double weight = move.inertia()[p] / fullMass[p];
                if (!part.nascent && part.contents.start(0) == 0 && part.from < nodes) fromFlow[part.edge] = trial.midpoint()[p];
                if (!part.nascent && part.contents.end(part.contents.intervals() - 1) == 1 && part.to < nodes) toFlow[part.edge] = trial.midpoint()[p];
                flow[part.edge] += weight * storedFlow; midpoint[part.edge] += weight * trial.midpoint()[p]; weights[part.edge] += weight;
                torque[part.edge] += pumpGain[p] * trial.midpoint()[p];
                head[part.edge] += IdealFluidSolver.pressurePa(pumpGain[p] * rpm[part.edge]) / (material.density() * IdealFluidSolver.GRAVITY);
                pumpWork += minutes * pumpGain[p] * rpm[part.edge] * trial.midpoint()[p];
            }
            for (int n = 0; n < nodes; n++) liquidOutward[n] = move.waterOutward()[n];
            for (int n = 0; n < nodes; n++) liquidOutward[n] -= handoff.delivered[n] * RotationMath.TICK_MINUTES / minutes;
            PipeMotion[] nextMotion = new PipeMotion[count]; double projectionLoss = inputLoss;
            for (int p = 0; p < parts.size(); p++) projectionLoss += .5 * fullMass[p] * content[p].fraction()
                * (initial[p] - origin[p]) * (initial[p] + origin[p]);
            projectionLoss = Math.max(0, projectionLoss);
            for (int e = 0; e < count; e++) {
                nextMotion[e] = PipeMotion.of(pieces.get(e));
                projectionLoss += .5 * material.inertia(tubes.get(e).length()) * nextMotion[e].mergeLoss();
                nextMotion[e] = new PipeMotion(nextMotion[e].contents(), nextMotion[e].flow());
                if (weights[e] > 0) { flow[e] /= weights[e]; midpoint[e] /= weights[e]; }
            }
            // Distribute each region's conserved state into its new empty portions.
            // Region labels follow the neighbouring plug faces through this step.
            var airPieces = new ArrayList<List<PipeAir.Portion>>(); for (int e = 0; e < count; e++) airPieces.add(new ArrayList<>());
            double[] actualVolume = new double[gas.size()]; double newAirEnergy = 0;
            var destinations = new ArrayList<Gap>();
            var gapsByEdge = new ArrayList<List<Gap>>(count);
            for (int e = 0; e < count; e++) gapsByEdge.add(new ArrayList<>());
            for (var gap : grouped) gapsByEdge.get(gap.edge).add(gap);
            for (int e = 0; e < count; e++) {
                // Preserve the original part order while restricting face
                // matching to this pipe instead of scanning the whole network.
                int[] localParts = partsByEdge[e].clone(); Arrays.sort(localParts);
                var liquid = nextMotion[e].contents(); double start = 0;
                for (int i = 0; i <= liquid.intervals(); i++) {
                    double end = i < liquid.intervals() ? liquid.start(i) : 1;
                    if (end > start) {
                        int group = movingGroup(gapsByEdge.get(e), parts, localParts, move.contents(), start, end, nodes, gasAtNode);
                        destinations.add(new Gap(e, start, end, group));
                    }
                    if (i < liquid.intervals()) start = liquid.end(i);
                }
            }
            destinations = labelNewAir(destinations, physicalTubes, nodes, open);
            for (var gap : destinations) {
                double volume = PipeAir.volume(tubes.get(gap.edge).length(), gap.end - gap.start);
                if (gap.group >= 0) actualVolume[gap.group] += volume;
                else {
                    // New dry mouths inherit air through their actual dry node.
                    // Only an isolated wall-side void has no gas to inherit.
                    var tube = physicalTubes.get(gap.edge);
                    boolean vented = open[gap.edge] && (gap.start == 0 && boundary[tube.from()] && atmosphere[tube.from()]
                        || gap.end == 1 && boundary[tube.to()] && atmosphere[tube.to()]);
                    double mass = vented ? PipeAir.DENSITY * volume : 0;
                    double energy = vented ? PipeAir.internalEnergy(volume, PipeAir.ATMOSPHERE) : 0;
                    airPieces.get(gap.edge).add(new PipeAir.Portion(gap.start, gap.end, mass, energy));
                    newAirEnergy += PipeAir.availableEnergy(volume, mass, energy);
                }
            }
            double[] solvedWork = new double[gas.size()], workCompensation = new double[gas.size()], volumeRoundoff = new double[gas.size()];
            for (int n = nodes; n < totalNodes; n++) {
                int g = gasAtNode.get(n); double term = minutes * pressure[n] * trial.outward()[n] - workCompensation[g];
                double sum = solvedWork[g] + term; workCompensation[g] = (sum - solvedWork[g]) - term; solvedWork[g] = sum;
            }
            for (var gap : grouped) volumeRoundoff[gap.group] += PipeAir.volume(tubes.get(gap.edge).length(),
                4 * (Math.ulp(gap.start) + Math.ulp(gap.end)));
            for (var gap : destinations) if (gap.group >= 0) volumeRoundoff[gap.group] += PipeAir.volume(tubes.get(gap.edge).length(),
                4 * (Math.ulp(gap.start) + Math.ulp(gap.end)));
            double airChange = newAirEnergy, airOutput = 0, pressureWork = 0;
            for (int g = 0; g < gas.size(); g++) {
                var pocket = gas.get(g); var next = pocket.step(actualVolume[g]);
                if (!Double.isFinite(next.pressure) || !Double.isFinite(next.energy)) {
                    return stationary(state, airState, nodes, count, minutes / 2, false);
                }
                double work = next.gauge * next.change / PipeAir.JOULES_PER_ENERGY;
                double rounding = volumeRoundoff[g] + 8 * Math.ulp(pocket.volume + next.volume);
                double largestPressure = Math.max(Math.abs(PipeAir.pressure(pocket.volume, pocket.energy) - PipeAir.ATMOSPHERE),
                    Math.abs(PipeAir.pressure(next.volume, next.energy) - PipeAir.ATMOSPHERE));
                double workBound = largestPressure * rounding / PipeAir.JOULES_PER_ENERGY;
                double correction = work - solvedWork[g];
                // Position storage rounds a tiny gas volume even when the velocity
                // solve is converged. Preserve the solved pressure work in internal
                // energy (or vent output), only within its geometric ULP bound.
                // This cannot hide a resolved pressure or topology error.
                if (Math.abs(correction) > 1e-12 && Math.abs(correction) <= workBound) {
                    if (!pocket.atmosphere && Double.isInfinite(pocket.ceiling)) {
                        double energy = next.energy + correction * PipeAir.JOULES_PER_ENERGY;
                        if (!(energy >= 0) || !Double.isFinite(energy)) return stationary(state, airState, nodes, count, minutes / 2, false);
                        next = new AirStep(next.volume, next.mass, energy, next.pressure, next.output, next.change, next.gauge);
                    } else next = new AirStep(next.volume, next.mass, next.energy, next.pressure, next.output + correction, next.change, next.gauge);
                    work = solvedWork[g];
                }
                airStep[g] = next;
                airChange += PipeAir.availableEnergy(next.volume, next.mass, next.energy) - PipeAir.availableEnergy(pocket.volume, pocket.mass, pocket.energy);
                airOutput += next.output;
                pressureWork += work;
            }
            for (var gap : destinations) {
                if (gap.group < 0) continue;
                var next = airStep[gap.group]; double volume = PipeAir.volume(tubes.get(gap.edge).length(), gap.end - gap.start);
                double share = actualVolume[gap.group] == 0 ? 0 : volume / actualVolume[gap.group];
                airPieces.get(gap.edge).add(new PipeAir.Portion(gap.start, gap.end, next.mass * share, next.energy * share));
            }
            // Newly opened vacuum has no pre-existing gas to compress. Its ambient
            // displacement energy belongs to the dry source pressure and the total
            // liquid/air audit below, rather than this existing-gas identity.
            double compressionChange = airChange - newAirEnergy;
            if (Math.abs(compressionChange + airOutput + pressureWork) > 1e-9 * (Math.abs(compressionChange) + Math.abs(airOutput) + Math.abs(pressureWork)) + 1e-12)
                throw new IllegalStateException("Air compression work imbalance: " + compressionChange + "/" + airOutput + "/" + pressureWork);
            // The average air pressure used in the momentum solve must be the same
            // pressure that accounts for its stored energy, at the actual displacement.
            double solvedAirWork = 0;
            for (double work : solvedWork) solvedAirWork += work;
            if (Math.abs(solvedAirWork - pressureWork) > 1e-8 * (Math.abs(solvedAirWork) + Math.abs(pressureWork)) + 1e-11) {
                if (minutes < 1e-17) throw new IllegalStateException("Air/liquid contact work failed: " + solvedAirWork + " / " + pressureWork);
                else return stationary(state, airState, nodes, count, minutes / 2, false);
            }
            PipeAir[] nextAir = airPieces.stream().map(PipeAir::new).toArray(PipeAir[]::new);
            double liquidBefore = 0, liquidAfter = 0, liquidInput = 0;
            for (int e = 0; e < count; e++) {
                double capacity = tubes.get(e).length() * PipeFilling.MB_PER_METRE;
                liquidBefore += capacity * beforeHandoff[e].contents().fraction();
                liquidAfter += capacity * nextMotion[e].contents().fraction();
            }
            for (int n = 0; n < nodes; n++) if (boundary[n]) liquidInput += liquidOutward[n] * minutes / RotationMath.TICK_MINUTES;
            if (Math.abs(liquidAfter - liquidBefore - liquidInput) > 64 * Math.ulp(liquidBefore + liquidAfter) + 1e-9)
                throw new IllegalStateException("Air/liquid inventory imbalance: " + liquidBefore + " -> " + liquidAfter + "; input=" + liquidInput
                    + "; before=" + Arrays.stream(state).map(m -> Arrays.toString(m.contents().data())).toList()
                    + "; after=" + Arrays.stream(nextMotion).map(m -> Arrays.toString(m.contents().data())).toList()
                    + "; parts=" + parts.stream().map(p -> p.edge + ":" + p.from + "->" + p.to + Arrays.toString(p.contents.data())).toList()
                    + "; outward=" + Arrays.toString(move.waterOutward()));
            double captureLoss = 0;
            for (int e = 0; e < count; e++) captureLoss += .5 * material.inertia(tubes.get(e).length()) * beforeHandoff[e].mergeLoss();
            // Both stored kinetic and gravitational energy start before the actual
            // mouth transfer. Its delivered potential and collision loss belong to
            // this same interval; auditing gravity after removing the parcel would
            // count its inventory output without its original stored energy.
            if (!audit(tubes, beforeHandoff, nextMotion, material, captureLoss, projectionLoss, airChange, airOutput,
                boundary, datum, liquidOutward, parts, trial.midpoint(), minutes, pumpWork))
                return stationary(state, airState, nodes, count, minutes / 2, false);
            // A dry lane has a real gas pressure even though it owns no liquid
            // momentum edge. Publish that pressure for the junction's invasion
            // criterion; it must compare liquid against this gas, not atmosphere.
            double[] mouthPressure = Arrays.copyOf(trial.pressure(), nodes);
            for (int e = 0; e < count; e++) if (open[e]) {
                var tube = tubes.get(e);
                if (firstGap[e] >= 0 && !wet[tube.from()])
                    mouthPressure[tube.from()] = IdealFluidSolver.pressureForce(airStep[groupIndex[union.root(firstGap[e])]].gauge);
                if (lastGap[e] >= 0 && !wet[tube.to()])
                    mouthPressure[tube.to()] = IdealFluidSolver.pressureForce(airStep[groupIndex[union.root(lastGap[e])]].gauge);
            }
            return new Result(nextMotion, nextAir, liquidOutward, flow, midpoint, fromFlow, toFlow, mouthPressure, torque, head, pumpWork, airChange, airOutput,
                projectionLoss, minutes, true);
        }
    }

    private static int[][] indexParts(List<Part> parts, int edges, int nodes) {
        int[] counts = new int[edges];
        for (var part : parts) counts[part.edge]++;
        int[][] index = new int[edges][];
        for (int e = 0; e < edges; e++) index[e] = new int[counts[e]];
        Arrays.fill(counts, 0);
        for (int p = 0; p < parts.size(); p++) {
            int edge = parts.get(p).edge, at = counts[edge]++;
            double position = partPosition(parts.get(p), nodes);
            // Stable insertion preserves the original face order on equal keys.
            while (at > 0 && Double.compare(partPosition(parts.get(index[edge][at - 1]), nodes), position) > 0) {
                index[edge][at] = index[edge][at - 1]; at--;
            }
            index[edge][at] = p;
        }
        return index;
    }

    private static double partPosition(Part part, int nodes) {
        return part.nascent ? part.from >= nodes ? 1 : 0 : part.contents.start(0);
    }
    private static boolean reservoirBirth(Part part, boolean[] boundary, int nodes) {
        return part.nascent && (part.from < nodes && boundary[part.from] || part.to < nodes && boundary[part.to]);
    }
    /** A communicating mouth may be reached before a long implicit gas
     * reversal. Locate that initial transit only when the parcel has enough
     * actual energy to overcome both pockets along the path. A fast velocity
     * alone is insufficient for a microscopic plug between stiff air springs. */
    private static boolean canReachMouth(Part part, List<Part> parts, int direction, PipeFilling.Tube tube,
                                         List<Integer> gasAtNode, List<Gas> gas, IdealFluidSolver.Material material, double rpm) {
        if (part.flow * direction <= 0) return false;
        double distance = part.contents.travelToMouth(direction, 0), change = direction * PipeAir.volume(tube.length(), distance);
        int from = gasAtNode.get(part.from), to = gasAtNode.get(part.to);
        double cost = 0;
        if (from != to) {
            double firstChange = 0, secondChange = 0, scale = change / part.flow;
            // Other faces of a shared region move during this transit too.
            // Two approaching plugs must not each assume the other is fixed
            // and independently spend the same remaining gas volume.
            for (var other : parts) {
                int a = gasAtNode.get(other.from), b = gasAtNode.get(other.to);
                if (a == from) firstChange += other.flow * scale;
                if (b == from) firstChange -= other.flow * scale;
                if (a == to) secondChange += other.flow * scale;
                if (b == to) secondChange -= other.flow * scale;
            }
            var a = gas.get(from).stepChange(firstChange); var b = gas.get(to).stepChange(secondChange);
            if (!Double.isFinite(a.gauge) || !Double.isFinite(b.gauge)) return false;
            cost = -(a.gauge * firstChange + b.gauge * secondChange) / PipeAir.JOULES_PER_ENERGY;
        }
        double fraction = part.contents.fraction();
        double knownPressure = IdealFluidSolver.pressurePa(IdealFluidSolver.coupling(material, rpm, part.flow) * rpm
            - material.gravityForce(tube.toHeight() - tube.fromHeight())) * fraction;
        cost -= knownPressure * change / PipeAir.JOULES_PER_ENERGY;
        double kinetic = .5 * material.inertia(tube.length()) * fraction * part.flow * part.flow;
        return cost <= kinetic + 64 * Math.ulp(Math.abs(cost) + kinetic);
    }
    /** The model treats sub-resolution films as nonsealing liquid parcels.
     * Join their gas by the same mass/internal-energy sum as any open air path;
     * keep all water and its independent momentum. This explicit spatial closure
     * does not switch a microscopic piston on/off as its pressure oscillates.
     * Resolved water columns still seal and compress even a microscopic bubble. */
    private static void releaseUnresolvedAirFilms(List<PipeFilling.Tube> tubes, PipeMotion[] state,
                                                  List<Gap> gaps, Union union) {
        for (int e = 0; e < state.length; e++) {
            var liquid = state[e].contents(); double length = tubes.get(e).length();
            for (int i = 0; i < liquid.intervals(); i++) {
                if ((liquid.end(i) - liquid.start(i)) * length > MICROSCOPIC_FILM_METRES) continue;
                int before = -1, after = -1;
                for (int g = 0; g < gaps.size(); g++) if (gaps.get(g).edge == e) {
                    if (gaps.get(g).end == liquid.start(i)) before = union.root(g);
                    if (gaps.get(g).start == liquid.end(i)) after = union.root(g);
                }
                if (before < 0 || after < 0 || before == after) continue;
                union.join(before, after);
            }
        }
    }
    /** Coalesce contacts within the shared front precision, conserving liquid
     * volume to its roundoff bound and recording the momentum impulse. Stored
     * gas energy above the work roundoff bound retains its resolved interface. */
    static PipeMotion[] coalesceRoundoffContacts(List<PipeFilling.Tube> tubes, PipeMotion[] state, PipeAir[] air) {
        PipeMotion[] result = state.clone();
        for (int e = 0; e < state.length; e++) {
            var liquid = state[e].contents(); if (liquid.intervals() == 0) continue;
            var gas = air[e].fit(liquid, tubes.get(e).length());
            var pieces = new ArrayList<PipeMotion.Piece>(); double loss = state[e].mergeLoss();
            boolean changed = false;
            for (int i = 0; i < liquid.intervals(); i++) {
                double a = liquid.start(i), b = liquid.end(i), q = state[e].flow()[i];
                if (i == 0 && a > 0 && a <= PipeContents.FRONT_ROUNDOFF
                    && gas.portions().getFirst().energy() <= 1e-11 * PipeAir.JOULES_PER_ENERGY) {
                    b -= a; a = 0; changed = true;
                }
                if (i == liquid.intervals() - 1 && b < 1 && 1 - b <= PipeContents.FRONT_ROUNDOFF
                    && gas.portions().getLast().energy() <= 1e-11 * PipeAir.JOULES_PER_ENERGY) {
                    if (a > 0) a += 1 - b; b = 1; changed = true;
                }
                if (i > 0) {
                    double left = liquid.end(i - 1), right = liquid.start(i);
                    if (right - left <= PipeContents.FRONT_ROUNDOFF && gas.portions().stream().anyMatch(p -> p.start() == left && p.end() == right
                        && p.energy() <= 1e-11 * PipeAir.JOULES_PER_ENERGY)) {
                        var previous = pieces.removeLast();
                        double firstLength = previous.end() - previous.start(), secondLength = b - a;
                        double width = firstLength + secondLength;
                        loss += firstLength * secondLength / width * (previous.flow() - q) * (previous.flow() - q);
                        q = (firstLength * previous.flow() + secondLength * q) / width;
                        // Anchor the whole merged packet at a contacted mouth.
                        // Moving only its last piece would reopen the same gap.
                        if (b == 1) { a = 1 - width; if (previous.start() == 0) a = 0; }
                        else { a = previous.start(); b = a + width; }
                        changed = true;
                    }
                }
                pieces.add(new PipeMotion.Piece(a, b, q));
            }
            if (changed) {
                var joined = PipeMotion.of(pieces); result[e] = new PipeMotion(joined.contents(), joined.flow(), joined.mergeLoss() + loss);
            }
        }
        return result;
    }
    /** Detached contact geometry after both roundoff coalescence and actual
     * microscopic tail handoff. The caller selects its wet-junction layout
     * from this same state; inventory delivery is committed only by sample. */
    static Prepared prepareContacts(List<PipeFilling.Tube> tubes, PipeMotion[] motion, PipeAir[] air,
                                         boolean[] open, boolean[] boundary, boolean[] receives, double[] datum,
                                         double[] rpm, IdealFluidSolver.Material material) {
        var coalesced = coalesceRoundoffContacts(tubes, motion, air);
        air = air.clone();
        // Finish all eligible disjoint packets before restarting the global
        // clock. Each changed pass strictly reduces the finite parcel count.
        while (true) {
            var reduced = coarsenMicroscopicPackets(tubes, coalesced, air, material);
            if (Arrays.equals(reduced, coalesced)) break;
            coalesced = reduced;
        }
        var handoff = transferUnresolvedMouthParcels(tubes, coalesced, air, open, boundary, receives, datum, rpm, material);
        return new Prepared(coalesced, handoff, air);
    }
    /** A compact train of individually sub-resolution films can otherwise
     * accumulate arbitrarily many fast, unresolved gas modes. Each pass reduces
     * one train per edge, preserving liquid mass/centre/momentum and all gas mass
     * and internal energy. Both its extent and the energy affected are bounded;
     * a resolved liquid plug or pressure imbalance prevents this reduction. */
    private static PipeMotion[] coarsenMicroscopicPackets(List<PipeFilling.Tube> tubes, PipeMotion[] state, PipeAir[] air,
                                                          IdealFluidSolver.Material material) {
        var result = state.clone();
        for (int e = 0; e < state.length; e++) {
            var liquid = state[e].contents(); var tube = tubes.get(e);
            if (liquid.intervals() < 2) continue;
            for (int first = 0; first < liquid.intervals() - 1; first++) {
                if ((liquid.end(first) - liquid.start(first)) * tube.length() > MICROSCOPIC_FILM_METRES || liquid.start(first) == 0) continue;
                int last = first;
                while (last + 1 < liquid.intervals()
                    && (liquid.end(last + 1) - liquid.start(last + 1)) * tube.length() <= MICROSCOPIC_FILM_METRES
                    && liquid.end(last + 1) < 1
                    && (liquid.end(last + 1) - liquid.start(first)) * tube.length() <= MICROSCOPIC_PACKET_METRES) last++;
                if (last == first) continue;
                double width = 0, centre = 0, momentum = 0, kinetic = 0;
                for (int i = first; i <= last; i++) {
                    double w = liquid.end(i) - liquid.start(i), q = state[e].flow()[i];
                    width += w; centre += w * (liquid.start(i) + liquid.end(i)) / 2;
                    momentum += w * q; kinetic += w * q * q;
                }
                centre /= width;
                double a = centre - width / 2, b = a + width, stored = b - a;
                if (!(stored > 0)) continue;
                double q = momentum / stored, loss = kinetic - stored * q * q;
                if (loss < -64 * Math.ulp(kinetic) || .5 * material.inertia(tube.length()) * loss > MICROSCOPIC_MODE_ENERGY) continue;
                double left = first == 0 ? 0 : liquid.end(first - 1), right = last + 1 == liquid.intervals() ? 1 : liquid.start(last + 1);
                if (!(a > left && b < right)) continue;
                var fitted = air[e].fit(liquid, tube.length());
                double volume = 0, mass = 0, internal = 0, adiabat = 0;
                for (var portion : fitted.portions()) if (portion.start() >= left && portion.end() <= right) {
                    double v = PipeAir.volume(tube.length(), portion.end() - portion.start());
                    volume += v; mass += portion.mass(); internal += portion.energy();
                    adiabat += v * Math.pow(PipeAir.pressure(v, portion.energy()), 1 / PipeAir.GAMMA);
                }
                double relaxed = PipeAir.internalEnergy(volume, Math.pow(adiabat / volume, PipeAir.GAMMA));
                // Reversible pressure equalisation bounds the air spring energy
                // affected. Thermal energy itself is retained, never discarded.
                if (internal - relaxed > MICROSCOPIC_MODE_ENERGY * PipeAir.JOULES_PER_ENERGY + 64 * Math.ulp(internal)) continue;
                var pieces = new ArrayList<PipeMotion.Piece>();
                for (int i = 0; i < liquid.intervals(); i++) {
                    if (i == first) pieces.add(new PipeMotion.Piece(a, b, q));
                    if (i < first || i > last) pieces.add(new PipeMotion.Piece(liquid.start(i), liquid.end(i), state[e].flow()[i]));
                }
                var merged = PipeMotion.of(pieces);
                result[e] = new PipeMotion(merged.contents(), merged.flow(), state[e].mergeLoss() + Math.max(0, loss) + merged.mergeLoss());
                var portions = new ArrayList<PipeAir.Portion>();
                for (var portion : fitted.portions()) if (portion.start() < left || portion.end() > right) portions.add(portion);
                double share = (a - left) / (a - left + right - b);
                portions.add(new PipeAir.Portion(left, a, mass * share, internal * share));
                portions.add(new PipeAir.Portion(b, right, mass * (1 - share), internal * (1 - share)));
                portions.sort(java.util.Comparator.comparingDouble(PipeAir.Portion::start)); air[e] = new PipeAir(portions);
                break;
            }
        }
        return result;
    }
    /** Complete a sub-resolution parcel's passage through a zero-volume
     * junction. Its mass and kinetic energy move to the other arm;
     * retaining a vanishing tail as a wetted constraint creates a false jet.
     * A wet arm records the collision impulse; a full outgoing column passes
     * its actual displaced inventory to a real receiver. Resolved pressure
     * work still requires an ordinary timed event. */
    private static MouthTransfer transferUnresolvedMouthParcels(List<PipeFilling.Tube> tubes, PipeMotion[] state,
                                                               PipeAir[] air, boolean[] open, boolean[] boundary,
                                                               boolean[] receives, double[] datum, double[] rpm, IdealFluidSolver.Material material) {
        var result = state.clone(); double[] delivered = new double[boundary.length]; double impulseLoss = 0;
        double headBound = 0;
        for (double value : datum) headBound = Math.max(headBound, Math.abs(IdealFluidSolver.pressurePa(value)));
        for (int e = 0; e < rpm.length; e++) if (open[e] && rpm[e] != 0)
            headBound += Math.abs(IdealFluidSolver.pressurePa(IdealFluidSolver.coupling(material, rpm[e], state[e].average()) * rpm[e]));
        for (int e = 0; e < tubes.size(); e++) if (open[e]) {
            var liquid = result[e].contents(); var source = tubes.get(e);
            for (int i = 0; i < liquid.intervals(); i++) {
                double a = liquid.start(i), b = liquid.end(i), q = result[e].flow()[i];
                if (!(a == 0 && q < 0 || b == 1 && q > 0)) continue;
                int node = q < 0 ? source.from() : source.to(), destination = -1;
                double parcelLength = source.length() * (b - a);
                if (parcelLength <= 1e-9 && boundary[node] && receives[node]) {
                    // A real inventory can accept a sub-resolution film at its
                    // own mouth. Only its local gauge pressure and the air on
                    // this film's free face can do unintegrated work. Remote
                    // pump heads across dry space and common atmospheric
                    // pressure are not forces on this detached parcel.
                    double pressure = Math.abs(IdealFluidSolver.pressurePa(datum[node]));
                    var fitted = air[e].fit(liquid, source.length());
                    for (var portion : fitted.portions()) if (q > 0 ? portion.end() == a : portion.start() == b)
                        pressure += Math.abs(PipeAir.gaugePressure(PipeAir.volume(source.length(), portion.end() - portion.start()), portion.energy()));
                    if (pressure * PipeAir.volume(1, parcelLength) / PipeAir.JOULES_PER_ENERGY <= 1e-11) {
                        var remaining = new ArrayList<PipeMotion.Piece>();
                        for (int j = 0; j < liquid.intervals(); j++) if (j != i)
                            remaining.add(new PipeMotion.Piece(liquid.start(j), liquid.end(j), result[e].flow()[j]));
                        var reduced = PipeMotion.of(remaining);
                        result[e] = new PipeMotion(reduced.contents(), reduced.flow(), result[e].mergeLoss() + reduced.mergeLoss());
                        delivered[node] += parcelLength * PipeFilling.MB_PER_METRE;
                        impulseLoss += .5 * material.inertia(parcelLength) * q * q;
                        liquid = result[e].contents(); i--; continue;
                    }
                }
                // Use the same physical film resolution at internal mouths as
                // at inventory mouths. A coordinate epsilon made a nanometre
                // tail emit ever-smaller parcels at a junction before it could
                // finish crossing. Every route below still bounds unresolved
                // pressure/gravity work and accounts for its real liquid and
                // kinetic impulse.
                if (parcelLength > 1e-9) continue;
                for (int k = 0; k < tubes.size(); k++) if (k != e && open[k]
                    && (tubes.get(k).from() == node || tubes.get(k).to() == node)) {
                    if (destination >= 0) { destination = -2; break; } destination = k;
                }
                var route = fullColumnRoute(tubes, result, open, boundary, receives, node, e, source.length() * (b - a));
                int wetTarget = route.wetTarget;
                if (wetTarget >= 0) {
                    var target = tubes.get(wetTarget); var other = result[wetTarget].contents(); boolean forward = target.from() == route.wetNode;
                    int near = forward ? 0 : other.intervals() - 1;
                    double volume = source.length() * (b - a), oldVolume = target.length() * (other.end(near) - other.start(near));
                    double pressure = faceAirGaugePressure(air[e], liquid, source.length(), q > 0 ? a : b, q < 0)
                        + faceAirGaugePressure(air[wetTarget], other, target.length(), forward ? other.end(near) : other.start(near), forward);
                    double height = Math.max(Math.abs(target.fromHeight() - source.fromHeight()), Math.abs(target.toHeight() - source.fromHeight()));
                    if ((pressure + headBound + material.density() * IdealFluidSolver.GRAVITY * height) * PipeAir.volume(1, volume) / PipeAir.JOULES_PER_ENERGY <= 1e-11) {
                        double incoming = Math.copySign(Math.abs(q), forward ? 1 : -1), previous = result[wetTarget].flow()[near];
                        double momentum = (oldVolume * previous + volume * incoming) / (oldVolume + volume);
                        double collision = .5 * material.inertia(1) * oldVolume * volume / (oldVolume + volume)
                            * (previous - incoming) * (previous - incoming);
                        var remaining = new ArrayList<PipeMotion.Piece>(); var merged = new ArrayList<PipeMotion.Piece>();
                        for (int j = 0; j < liquid.intervals(); j++) if (j != i)
                            remaining.add(new PipeMotion.Piece(liquid.start(j), liquid.end(j), result[e].flow()[j]));
                        for (int j = 0; j < other.intervals(); j++) {
                            double start = other.start(j), end = other.end(j), speed = result[wetTarget].flow()[j];
                            if (j == near) {
                                if (forward) end += volume / target.length(); else start -= volume / target.length();
                                speed = momentum * Math.sqrt((oldVolume + volume) / (target.length() * (end - start)));
                            }
                            merged.add(new PipeMotion.Piece(start, end, speed));
                        }
                        var reduced = PipeMotion.of(remaining); var enlarged = PipeMotion.of(merged);
                        result[e] = new PipeMotion(reduced.contents(), reduced.flow(), result[e].mergeLoss() + reduced.mergeLoss());
                        result[wetTarget] = new PipeMotion(enlarged.contents(), enlarged.flow(), result[wetTarget].mergeLoss() + enlarged.mergeLoss());
                        impulseLoss += collision; liquid = result[e].contents(); i--; continue;
                    }
                }
                if (destination == -2 && route.receiver < 0) {
                    // Resolve an unrepresentable branch contact once,
                    // instead of keeping its vanishing incoming tail as a jet
                    // source. Equal-area empty outlets have equal newly admitted
                    // inertia per volume; their contact projection has a common
                    // outgoing speed. Its collision energy is recorded explicitly.
                    var outlets = new ArrayList<Integer>();
                    double pressure = faceAirGaugePressure(air[e], liquid, source.length(), q > 0 ? a : b, q < 0);
                    double height = 0, volume = source.length() * (b - a);
                    for (int k = 0; k < tubes.size(); k++) if (k != e && open[k]
                        && (tubes.get(k).from() == node || tubes.get(k).to() == node)) {
                        var target = tubes.get(k); var other = result[k].contents(); boolean forward = target.from() == node;
                        double width = volume / target.length();
                        // A touching, already full incoming arm cannot store
                        // this parcel. Keep the genuinely available dry exits
                        // rather than letting that arm block every other exit.
                        if (other.intervals() > 0 && (forward ? other.start(0) <= width
                            : other.end(other.intervals() - 1) >= 1 - width)) continue;
                        outlets.add(k); pressure += faceAirGaugePressure(air[k], other, target.length(), forward ? 0 : 1, forward);
                        height = Math.max(height, Math.max(Math.abs(target.fromHeight() - source.fromHeight()), Math.abs(target.toHeight() - source.fromHeight())));
                    }
                    double kinetic = .5 * material.inertia(volume) * q * q;
                    double bound = (pressure + headBound + material.density() * IdealFluidSolver.GRAVITY * height)
                        * PipeAir.volume(1, volume) / PipeAir.JOULES_PER_ENERGY;
                    // The fork's kinetic impulse is accounted for exactly
                    // below, including a fast microscopic incoming parcel.
                    // Bound only the unresolved pressure/gravity work; its
                    // kinetic energy must not keep a false wet inlet alive.
                    if (!outlets.isEmpty() && bound <= 1e-11) {
                        boolean representable = true;
                        for (int k : outlets) {
                            var target = tubes.get(k); double width = volume / outlets.size() / target.length();
                            if (!(width > 0 && width <= 1) || target.from() != node && !(1 - width < 1)) { representable = false; break; }
                        }
                        if (representable) {
                            for (int k : outlets) {
                                var target = tubes.get(k); var other = result[k].contents(); boolean forward = target.from() == node;
                                double share = volume / outlets.size(), width = share / target.length();
                                double start = forward ? 0 : 1 - width, end = forward ? width : 1;
                                double speed = Math.abs(q) / outlets.size() * Math.sqrt(share / (target.length() * (end - start)));
                                var added = new ArrayList<PipeMotion.Piece>();
                                for (int j = 0; j < other.intervals(); j++) added.add(new PipeMotion.Piece(other.start(j), other.end(j), result[k].flow()[j]));
                                added.add(new PipeMotion.Piece(start, end, forward ? speed : -speed));
                                var transferred = PipeMotion.of(added);
                                result[k] = new PipeMotion(transferred.contents(), transferred.flow(), result[k].mergeLoss() + transferred.mergeLoss());
                            }
                            var remaining = new ArrayList<PipeMotion.Piece>();
                            for (int j = 0; j < liquid.intervals(); j++) if (j != i)
                                remaining.add(new PipeMotion.Piece(liquid.start(j), liquid.end(j), result[e].flow()[j]));
                            var reduced = PipeMotion.of(remaining);
                            result[e] = new PipeMotion(reduced.contents(), reduced.flow(), result[e].mergeLoss() + reduced.mergeLoss());
                            impulseLoss += kinetic * (1 - 1d / (outlets.size() * outlets.size()));
                            liquid = result[e].contents(); i--; continue;
                        }
                    }
                }
                if (destination < 0 || result[destination].contents() == PipeContents.FULL) {
                    // A vanishing tail at a branched wet contact must not be
                    // retained as a persistent liquid inlet. Through an already
                    // full outgoing column, an incompressible displacement goes
                    // directly to its real receiving inventory. Keep that actual
                    // inventory transfer; only a sub-roundoff contact impulse
                    // may be resolved without a representable time interval.
                    int receiver = route.receiver;
                    double volume = source.length() * (b - a), capacity = volume * PipeFilling.MB_PER_METRE;
                    double kinetic = .5 * material.inertia(volume) * q * q;
                    double pressure = faceAirGaugePressure(air[e], liquid, source.length(), q > 0 ? a : b, q < 0);
                    double height = receiver < 0 ? 0 : tubes.stream().mapToDouble(t -> Math.max(Math.abs(t.fromHeight() - source.fromHeight()), Math.abs(t.toHeight() - source.fromHeight()))).max().orElse(0);
                    double workBound = (pressure + headBound + material.density() * IdealFluidSolver.GRAVITY * height)
                        * PipeAir.volume(1, volume) / PipeAir.JOULES_PER_ENERGY;
                    // Entering the real receiving inventory accounts for the
                    // entire incoming kinetic energy as an outlet impulse.
                    // Bound the unintegrated pressure/gravity work, rather than
                    // refusing a fast microscopic parcel whose kinetic output
                    // is already recorded exactly.
                    if (receiver < 0 || workBound > 1e-11) continue;
                    var remaining = new ArrayList<PipeMotion.Piece>();
                    for (int j = 0; j < liquid.intervals(); j++) if (j != i)
                        remaining.add(new PipeMotion.Piece(liquid.start(j), liquid.end(j), result[e].flow()[j]));
                    var reduced = PipeMotion.of(remaining);
                    result[e] = new PipeMotion(reduced.contents(), reduced.flow(), result[e].mergeLoss() + reduced.mergeLoss());
                    delivered[receiver] += capacity; impulseLoss += kinetic;
                    liquid = result[e].contents(); i--; continue;
                }
                var target = tubes.get(destination); var other = result[destination].contents();
                boolean forward = target.from() == node;
                double volume = source.length() * (b - a), width = volume / target.length();
                double start = forward ? 0 : 1 - width, end = forward ? width : 1;
                if (!(end > start) || end > 1 || start < 0 || other.intervals() > 0
                    && (forward ? end >= other.start(0) : start <= other.end(other.intervals() - 1))) continue;
                double pressure = faceAirGaugePressure(air[e], liquid, source.length(), q > 0 ? a : b, q < 0)
                    + faceAirGaugePressure(air[destination], other, target.length(), forward ? 0 : 1, forward);
                double height = Math.max(Math.abs(target.fromHeight() - source.fromHeight()), Math.abs(target.toHeight() - source.fromHeight()));
                if ((pressure + headBound + material.density() * IdealFluidSolver.GRAVITY * height) * PipeAir.volume(1, volume) / PipeAir.JOULES_PER_ENERGY > 1e-11) continue;
                var left = new ArrayList<PipeMotion.Piece>(); var right = new ArrayList<PipeMotion.Piece>();
                for (int j = 0; j < liquid.intervals(); j++) if (j != i)
                    left.add(new PipeMotion.Piece(liquid.start(j), liquid.end(j), result[e].flow()[j]));
                for (int j = 0; j < other.intervals(); j++)
                    right.add(new PipeMotion.Piece(other.start(j), other.end(j), result[destination].flow()[j]));
                // Affine endpoint rounding changes only sub-resolution volume.
                // Retain its kinetic energy even for a very fast, tiny parcel.
                double speed = Math.abs(q) * Math.sqrt(volume / (target.length() * (end - start)));
                right.add(new PipeMotion.Piece(start, end, forward ? speed : -speed));
                var remaining = PipeMotion.of(left); var transferred = PipeMotion.of(right);
                result[e] = new PipeMotion(remaining.contents(), remaining.flow(), result[e].mergeLoss() + remaining.mergeLoss());
                result[destination] = new PipeMotion(transferred.contents(), transferred.flow(), result[destination].mergeLoss() + transferred.mergeLoss());
                liquid = result[e].contents(); i--;
            }
        }
        return new MouthTransfer(result, delivered, impulseLoss);
    }
    private static LiquidRoute fullColumnRoute(List<PipeFilling.Tube> tubes, PipeMotion[] state, boolean[] open,
                                               boolean[] boundary, boolean[] receives, int start, int source, double volume) {
        boolean[] visited = new boolean[boundary.length]; int[] queue = new int[boundary.length];
        int head = 0, tail = 1; queue[0] = start; visited[start] = true;
        while (head < tail) {
            int node = queue[head++]; if (boundary[node] && receives[node]) return new LiquidRoute(node, -1, -1);
            for (int e = 0; e < tubes.size(); e++) if (e != source && open[e]) {
                var tube = tubes.get(e); var liquid = state[e].contents();
                int next = tube.from() == node ? tube.to() : tube.to() == node ? tube.from() : -1;
                if (next < 0 || liquid.intervals() == 0) continue;
                boolean forward = tube.from() == node; int near = forward ? 0 : liquid.intervals() - 1;
                boolean touching = forward ? liquid.start(near) == 0 : liquid.end(near) == 1;
                double outward = (forward ? 1 : -1) * state[e].flow()[near];
                // A partial touching packet can also receive a head-on
                // collision. Its signed momentum is handled by the wet-arm
                // impulse above. Only an outgoing full column may pass an
                // incompressible displacement to a farther inventory.
                if (!touching || liquid == PipeContents.FULL && outward <= 0) continue;
                if (liquid == PipeContents.FULL) {
                    if (!visited[next]) { visited[next] = true; queue[tail++] = next; }
                } else {
                    double width = volume / tube.length();
                    boolean room = forward ? liquid.end(near) + width < (liquid.intervals() > 1 ? liquid.start(1) : 1)
                        : liquid.start(near) - width > (near > 0 ? liquid.end(near - 1) : 0);
                    if (room) return new LiquidRoute(-1, e, node);
                }
            }
        }
        return new LiquidRoute(-1, -1, -1);
    }
    private static double faceAirGaugePressure(PipeAir air, PipeContents liquid, double length, double face, boolean after) {
        // A separated pocket elsewhere in the same pipe does not press on this
        // parcel. Only the gas touching the displaced face enters its work bound.
        // The common atmosphere reference cancels between the displaced volumes.
        for (var portion : air.fit(liquid, length).portions()) if (after ? portion.start() == face : portion.end() == face)
            return Math.abs(PipeAir.gaugePressure(PipeAir.volume(length, portion.end() - portion.start()), portion.energy()));
        return 0;
    }
    private static ArrayList<Gap> labelNewAir(List<Gap> gaps, List<PipeFilling.Tube> tubes, int nodes, boolean[] open) {
        var union = new Union(gaps.size()); int[] atNode = new int[nodes]; Arrays.fill(atNode, -1);
        for (int i = 0; i < gaps.size(); i++) {
            var gap = gaps.get(i); if (!open[gap.edge]) continue; var tube = tubes.get(gap.edge);
            for (int side = 0; side < 2; side++) {
                int node = side == 0 ? tube.from() : tube.to();
                // Incoming air can populate a newly dry mouth even while
                // another arm still contains water at this junction. Existing
                // sealed regions keep their own labels until actual contact.
                if (side == 0 ? gap.start != 0 : gap.end != 1) continue;
                if (atNode[node] < 0) atNode[node] = i; else union.join(atNode[node], i);
            }
        }
        int[] label = new int[gaps.size()]; Arrays.fill(label, -1);
        for (int i = 0; i < gaps.size(); i++) if (gaps.get(i).group >= 0) label[union.root(i)] = gaps.get(i).group;
        var result = new ArrayList<Gap>();
        for (int i = 0; i < gaps.size(); i++) {
            var gap = gaps.get(i); result.add(gap.group >= 0 ? gap : new Gap(gap.edge, gap.start, gap.end, label[union.root(i)]));
        }
        return result;
    }
    /** Actual liquid mass, gravity and gas storage must account for the same
     * work as the momentum and shaft solves, including inventory energy output. */
    private static boolean audit(List<PipeFilling.Tube> tubes, PipeMotion[] before, PipeMotion[] after,
                               IdealFluidSolver.Material material, double inputLoss, double projectionLoss,
                               double airChange, double airOutput, boolean[] boundary, double[] datum, double[] waterOutward,
                               List<Part> parts, double[] midpoint, double minutes, double pumpWork) {
        double kineticBefore = inputLoss, kineticAfter = 0, gravityChange = 0, pressureWork = 0, transportedPotential = 0, kineticOutput = 0;
        double base = tubes.stream().mapToDouble(PipeFilling.Tube::fromHeight).min().orElse(0);
        double[] height = new double[boundary.length];
        for (int e = 0; e < tubes.size(); e++) {
            var tube = tubes.get(e); var first = before[e].contents(); var last = after[e].contents();
            double inertia = material.inertia(tube.length());
            for (int i = 0; i < first.intervals(); i++) kineticBefore += .5 * inertia * (first.end(i) - first.start(i)) * before[e].flow()[i] * before[e].flow()[i];
            for (int i = 0; i < last.intervals(); i++) kineticAfter += .5 * inertia * (last.end(i) - last.start(i)) * after[e].flow()[i] * after[e].flow()[i];
            gravityChange += tube.length() * PipeFilling.MB_PER_METRE * RotationMath.TICK_MINUTES
                * (material.gravityForce(tube.fromHeight() - base) * (last.fraction() - first.fraction())
                    + material.gravityForce(tube.toHeight() - tube.fromHeight()) * (last.moment() - first.moment()));
            height[tube.from()] = tube.fromHeight(); height[tube.to()] = tube.toHeight();
        }
        for (int n = 0; n < boundary.length; n++) if (boundary[n]) {
            pressureWork += minutes * datum[n] * waterOutward[n];
            transportedPotential += minutes * material.gravityForce(height[n] - base) * waterOutward[n];
        }
        for (int p = 0; p < parts.size(); p++) if (midpoint[p] != 0) {
            var part = parts.get(p); int destination = midpoint[p] > 0 ? part.to : part.from;
            if (destination < boundary.length && boundary[destination]) {
                double fraction = Math.max(0, Math.min(1, -waterOutward[destination] / Math.abs(midpoint[p])));
                kineticOutput += minutes * fraction * material.outletForce(midpoint[p]) * midpoint[p];
            }
        }
        double error = kineticAfter - kineticBefore + gravityChange + airChange + projectionLoss + airOutput + kineticOutput
            - pumpWork - pressureWork - transportedPotential;
        double scale = Math.abs(kineticAfter - kineticBefore) + Math.abs(gravityChange) + Math.abs(airChange)
            + projectionLoss + Math.abs(airOutput) + kineticOutput + Math.abs(pumpWork) + Math.abs(pressureWork) + Math.abs(transportedPotential);
        if (Double.isFinite(error) && Math.abs(error) <= 1e-9 * scale + 32 * Math.ulp(kineticBefore + kineticAfter) + 1e-11) return true;
        // A detached nonlinear trial may need finer geometry/force resolution.
        // Reject its entire state and retry; accepted steps keep the same bound.
        if (Double.isFinite(error) && minutes >= 1e-17) return false;
        else
            throw new IllegalStateException("Air/liquid work imbalance: " + error + " (kinetic " + (kineticAfter - kineticBefore)
                + ", gravity " + gravityChange + ", air " + airChange + ", pump " + pumpWork + ", inventory " + pressureWork
                + ", potential " + transportedPotential + ", output " + (kineticOutput + airOutput) + ", impulse " + projectionLoss + ")");
    }
    /** One detached Newton solve can change weights without changing topology. */
    private static final class ComplianceGraph {
        private List<IdealFluidSolver.Edge> edges;
        private ReducedFluidGraph graph;
        IdealFluidSolver.Trial sample(List<IdealFluidSolver.Edge> next, boolean[] fixed, boolean[] active,
                                     double[] initial, double[] inertia, double[] force, double[] datum, double minutes) {
            boolean matches = graph != null && graph.matches(fixed, active) && edges.size() == next.size();
            if (matches) for (int e = 0; e < edges.size(); e++)
                if (edges.get(e).from() != next.get(e).from() || edges.get(e).to() != next.get(e).to()) { matches = false; break; }
            if (!matches) {
                edges = next;
                graph = new ReducedFluidGraph(fixed.length, next, fixed, active);
            }
            return graph.sample(initial, inertia, force, datum, minutes);
        }
    }
    /** Newton response of liquid volume to the shared gas pressures. These
     * tangent terms disappear at convergence; they store no fictitious energy. */
    private static IdealFluidSolver.Trial coupledAir(List<IdealFluidSolver.Edge> edges, boolean[] enabled, boolean[] borders, double[] origin, double[] mass,
                                                     double[] applied, double[] pressure, double minutes, int nodes,
                                                     List<Integer> gasAtNode, List<Gas> gas, AirStep[] air, double[] candidateVolume,
                                                     ReducedFluidGraph graph, ComplianceGraph compliance) {
        int totalNodes = pressure.length;
        int[] contact = new int[gas.size()]; Arrays.fill(contact, -1);
        double[] slope = new double[gas.size()];
        for (int g = 0; g < gas.size(); g++) slope[g] = IdealFluidSolver.pressureForce(gas.get(g).slope(air[g]));
        for (int n = nodes; n < totalNodes; n++) {
            int g = gasAtNode.get(n);
            if (slope[g] < 0 && contact[g] < 0) contact[g] = n;
        }
        int variables = 0; for (int n : contact) if (n >= 0) variables++;
        boolean precise = false;
        if (variables == 0) {
            try { return graph.sample(origin, mass, applied, pressure, minutes); }
            catch (IdealFluidSolver.ConstraintConvergenceException | IdealFluidSolver.WorkConvergenceException failure) { precise = true; }
        }
        int auxiliaryNodes = Math.max(1, variables);
        boolean[] fixed = Arrays.copyOf(borders, totalNodes + auxiliaryNodes);
        Arrays.fill(fixed, totalNodes, fixed.length, true);
        double[] datum = Arrays.copyOf(pressure, fixed.length);
        var augmented = new ArrayList<IdealFluidSolver.Edge>(); boolean[] sharedSides = new boolean[edges.size()];
        for (var edge : edges) {
            int from = edge.from(), to = edge.to();
            if (from >= nodes && contact[gasAtNode.get(from)] >= 0) from = contact[gasAtNode.get(from)];
            if (to >= nodes && contact[gasAtNode.get(to)] >= 0) to = contact[gasAtNode.get(to)];
            // A dry return path can connect both faces to the same air region.
            // Their pressures cancel and this plug changes no gas volume.
            sharedSides[augmented.size()] = from == to;
            augmented.add(from == to ? edge : new IdealFluidSolver.Edge(from, to, edge.inertia()));
        }
        double[] initial = new double[origin.length + variables], inertia = Arrays.copyOf(mass, initial.length), force = Arrays.copyOf(applied, initial.length);
        boolean[] active = Arrays.copyOf(enabled, initial.length);
        for (int k = 0; k < sharedSides.length; k++) if (sharedSides[k]) active[k] = false;
        // Solve directly for the midpoint, with real initial momentum on the
        // right-hand side. Giving a stiff gas edge an artificial initial flow
        // would introduce enormous cancelling kinetic energies and forces.
        for (int k = 0; k < edges.size(); k++) force[k] += 2 * mass[k] * origin[k] / minutes;
        int e = edges.size(), ground = totalNodes;
        for (int g = 0; g < gas.size(); g++) if (contact[g] >= 0) {
            int node = contact[g]; fixed[node] = false;
            // A cancelled compliance edge puts the gas-pressure tangent in the
            // same continuity solve as liquid momentum. This avoids subtracting
            // almost equal absolute pressures across microscopic water parcels.
            inertia[e] = -.5 * slope[g] * minutes * minutes * IdealFluidSolver.MB_VOLUME / RotationMath.TICK_MINUTES;
            // Encode the offset directly as this arm's own pressure datum.
            // Graph.sample removes the common pressure per real component;
            // separate fixed nodes never join independent pipes or erase a
            // small offset by subtracting it from a distant enormous one.
            datum[ground] = pressure[node] - slope[g] * air[g].change;
            active[e] = true; augmented.add(new IdealFluidSolver.Edge(node, ground++, inertia[e])); e++;
        }
        IdealFluidSolver.Trial coupled;
        if (precise) coupled = preciseCompliance(augmented, fixed, active, inertia, force, datum, minutes);
        else try {
            coupled = compliance.sample(augmented, fixed, active, initial, inertia, force, datum, minutes);
        } catch (IdealFluidSolver.ConstraintConvergenceException | IdealFluidSolver.WorkConvergenceException failure) {
            coupled = preciseCompliance(augmented, fixed, active, inertia, force, datum, minutes);
        }
        // Gas volume is a separate Newton unknown. Its compliance-edge flux
        // resolves volume changes too small to obtain by subtracting two fast
        // liquid velocities; the final geometry/work audit remains independent.
        int shunt = edges.size();
        for (int g = 0; g < gas.size(); g++) if (contact[g] >= 0)
            candidateVolume[g] = -coupled.midpoint()[shunt++] * minutes / RotationMath.TICK_MINUTES * IdealFluidSolver.MB_VOLUME;
        double[] actualPressure = Arrays.copyOf(coupled.pressure(), totalNodes);
        for (int n = nodes; n < totalNodes; n++) if (contact[gasAtNode.get(n)] >= 0)
            actualPressure[n] = actualPressure[contact[gasAtNode.get(n)]];
        System.arraycopy(actualPressure, 0, pressure, 0, totalNodes);
        double[] midpoint = Arrays.copyOf(coupled.midpoint(), origin.length);
        for (int k = 0; k < sharedSides.length; k++) if (sharedSides[k] && enabled[k]) midpoint[k] = minutes * force[k] / (2 * mass[k]);
        double[] actualOutward = new double[totalNodes];
        for (int k = 0; k < edges.size(); k++) {
            actualOutward[edges.get(k).from()] += midpoint[k]; actualOutward[edges.get(k).to()] -= midpoint[k];
        }
        double[] next = new double[origin.length];
        double change = 0, work = 0, boundaryWork = 0;
        for (int k = 0; k < edges.size(); k++) {
            next[k] = 2 * midpoint[k] - origin[k];
            change += .5 * mass[k] * (next[k] - origin[k]) * (next[k] + origin[k]);
            work += minutes * applied[k] * midpoint[k];
        }
        for (int n = 0; n < totalNodes; n++) if (borders[n]) boundaryWork += minutes * actualPressure[n] * actualOutward[n];
        return new IdealFluidSolver.Trial(next, midpoint, actualPressure, actualOutward,
            change, work, boundaryWork, 0, origin);
    }
    /** Rare fallback for an ill-conditioned Newton tangent. Reconstruct its
     * flows from the pressure equation in extended precision, rather than
     * subtracting enormous unconstrained velocities in double precision.
     * This changes only the detached pressure trial; actual liquid and gas
     * work are still checked independently before any state is committed. */
    private static IdealFluidSolver.Trial preciseCompliance(List<IdealFluidSolver.Edge> edges, boolean[] boundary,
                                                            boolean[] active, double[] mass, double[] force,
                                                            double[] datum, double minutes) {
        int nodes = boundary.length; boolean[] incident = new boolean[nodes];
        var union = new Union(nodes); double smallest = Double.POSITIVE_INFINITY, largest = 0;
        for (int e = 0; e < edges.size(); e++) if (active[e]) {
            var edge = edges.get(e); incident[edge.from()] = incident[edge.to()] = true;
            union.join(edge.from(), edge.to()); smallest = Math.min(smallest, mass[e]); largest = Math.max(largest, mass[e]);
        }
        boolean[] fixed = boundary.clone(), grounded = new boolean[nodes];
        for (int n = 0; n < nodes; n++) if (fixed[n]) grounded[union.root(n)] = true;
        for (int n = 0; n < nodes; n++) if (incident[n] && !grounded[union.root(n)]) {
            fixed[n] = true; grounded[union.root(n)] = true;
        }
        int[] variable = new int[nodes]; Arrays.fill(variable, -1); int count = 0;
        for (int n = 0; n < nodes; n++) if (incident[n] && !fixed[n]) variable[n] = count++;
        var precision = new MathContext(Math.max(80, (int) Math.ceil(Math.log10(largest) - Math.log10(smallest)) + 40));
        BigDecimal[][] matrix = new BigDecimal[count][count]; BigDecimal[] rhs = new BigDecimal[count];
        for (int i = 0; i < count; i++) { Arrays.fill(matrix[i], BigDecimal.ZERO); rhs[i] = BigDecimal.ZERO; }
        BigDecimal[] weight = new BigDecimal[edges.size()], forcing = new BigDecimal[edges.size()], pressure = new BigDecimal[nodes];
        for (int n = 0; n < nodes; n++) pressure[n] = BigDecimal.valueOf(boundary[n] ? datum[n] : 0);
        for (int e = 0; e < edges.size(); e++) if (active[e]) {
            var edge = edges.get(e); int a = variable[edge.from()], b = variable[edge.to()];
            weight[e] = BigDecimal.ONE.divide(BigDecimal.valueOf(mass[e]), precision); forcing[e] = BigDecimal.valueOf(force[e]);
            BigDecimal known = forcing[e];
            if (a < 0) known = known.add(pressure[edge.from()], precision);
            if (b < 0) known = known.subtract(pressure[edge.to()], precision);
            BigDecimal flux = known.multiply(weight[e], precision);
            if (a >= 0) { matrix[a][a] = matrix[a][a].add(weight[e], precision); rhs[a] = rhs[a].subtract(flux, precision); }
            if (b >= 0) { matrix[b][b] = matrix[b][b].add(weight[e], precision); rhs[b] = rhs[b].add(flux, precision); }
            if (a >= 0 && b >= 0) {
                matrix[a][b] = matrix[a][b].subtract(weight[e], precision); matrix[b][a] = matrix[b][a].subtract(weight[e], precision);
            }
        }
        // Every component has a pressure datum, so the conductance matrix is
        // positive definite. Decimal elimination retains its small shunts.
        for (int column = 0; column < count; column++) {
            if (matrix[column][column].signum() <= 0) throw new IdealFluidSolver.ConstraintConvergenceException();
            for (int row = column + 1; row < count; row++) if (matrix[row][column].signum() != 0) {
                BigDecimal factor = matrix[row][column].divide(matrix[column][column], precision);
                for (int k = column + 1; k < count; k++) matrix[row][k] = matrix[row][k].subtract(factor.multiply(matrix[column][k], precision), precision);
                rhs[row] = rhs[row].subtract(factor.multiply(rhs[column], precision), precision);
            }
        }
        BigDecimal[] solved = new BigDecimal[count];
        for (int row = count - 1; row >= 0; row--) {
            BigDecimal value = rhs[row];
            for (int k = row + 1; k < count; k++) value = value.subtract(matrix[row][k].multiply(solved[k], precision), precision);
            solved[row] = value.divide(matrix[row][row], precision);
        }
        for (int n = 0; n < nodes; n++) if (variable[n] >= 0) pressure[n] = solved[variable[n]];
        double[] midpoint = new double[edges.size()], next = new double[edges.size()], outward = new double[nodes];
        BigDecimal dt = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(2), precision);
        for (int e = 0; e < edges.size(); e++) if (active[e]) {
            var edge = edges.get(e);
            midpoint[e] = forcing[e].add(pressure[edge.from()], precision).subtract(pressure[edge.to()], precision)
                .multiply(weight[e], precision).multiply(dt, precision).doubleValue();
            if (!Double.isFinite(midpoint[e])) throw new IdealFluidSolver.ConstraintConvergenceException();
            next[e] = 2 * midpoint[e]; outward[edge.from()] += midpoint[e]; outward[edge.to()] -= midpoint[e];
        }
        return new IdealFluidSolver.Trial(next, midpoint, Arrays.stream(pressure).mapToDouble(BigDecimal::doubleValue).toArray(), outward, 0, 0, 0, 0);
    }
    private static int movingGroup(List<Gap> gaps, List<Part> parts, int[] localParts, PipeContents[] next,
                                   double start, double end, int nodes, List<Integer> gasAtNode) {
        // Follow the actual bounding liquid faces. Spatial-overlap matching
        // would exchange gas inventory between independently moving pockets.
        int nearest = -1;
        double nearestError = Double.POSITIVE_INFINITY;
        for (var gap : gaps) {
            double a = gap.start, b = gap.end;
            for (int p : localParts) {
                var part = parts.get(p);
                var liquid = next[p];
                boolean left = part.nascent ? gap.start == 0 && part.from < nodes
                    : part.contents.end(part.contents.intervals() - 1) == gap.start;
                boolean right = part.nascent ? gap.end == 1 && part.to < nodes : part.contents.start(0) == gap.end;
                if (left) a = liquid.intervals() == 0 ? 0 : liquid.end(liquid.intervals() - 1);
                if (right) b = liquid.intervals() == 0 ? 1 : liquid.start(0);
            }
            if (a == start && b == end) return gap.group;
            double error = Math.max(Math.abs(a - start), Math.abs(b - end));
            if (error <= 16 * Math.ulp(1d) && error < nearestError) {
                nearest = gap.group;
                nearestError = error;
            }
        }
        for (int p : localParts) {
            var part = parts.get(p); if (part.nascent || next[p].intervals() == 0) continue;
            int group = -1;
            double error = Double.POSITIVE_INFINITY;
            if (start == 0 && part.contents.start(0) == 0 && part.from >= nodes) {
                group = gasAtNode.get(part.from);
                error = Math.abs(end - next[p].start(0));
            }
            if (end == 1 && part.contents.end(part.contents.intervals() - 1) == 1 && part.to >= nodes) {
                double otherError = Math.abs(start - next[p].end(next[p].intervals() - 1));
                if (otherError < error) { group = gasAtNode.get(part.to); error = otherError; }
            }
            if (error == 0) return group;
            if (error <= 16 * Math.ulp(1d) && error < nearestError) {
                nearest = group;
                nearestError = error;
            }
        }
        return nearest;
    }
    private static double[] gasDisplacement(List<Part> parts, double[] flow, List<Integer> gasAtNode,
                                             int nodes, int groups, double minutes) {
        double[] rate = new double[groups], compensation = new double[groups];
        for (int p = 0; p < parts.size(); p++) for (int side = 0; side < 2; side++) {
            var part = parts.get(p); int node = side == 0 ? part.from : part.to;
            if (node < nodes) continue;
            int group = gasAtNode.get(node); double term = (side == 0 ? flow[p] : -flow[p]) - compensation[group];
            double sum = rate[group] + term; compensation[group] = (sum - rate[group]) - term; rate[group] = sum;
        }
        for (int g = 0; g < groups; g++) rate[g] *= minutes / RotationMath.TICK_MINUTES * IdealFluidSolver.MB_VOLUME;
        return rate;
    }
    private static int groupAt(List<Gap> gaps, int edge, double point, boolean after) {
        for (var gap : gaps) if (gap.edge == edge && (after ? gap.start <= point && point < gap.end : gap.start < point && point <= gap.end)) return gap.group;
        throw new IllegalStateException("Liquid face has no air region");
    }
    private static int front(List<Integer> gasAtNode, int group) { int node = gasAtNode.size(); gasAtNode.add(group); return node; }
    private static void vent(Gas gas, int node, boolean[] atmosphere, double[] ceiling) {
        gas.atmosphere |= atmosphere[node]; gas.ceiling = Math.min(gas.ceiling, ceiling[node]);
    }
    private static Result stationary(PipeMotion[] state, PipeAir[] air, int nodes, int edges, double minutes, boolean converged) {
        return new Result(state, air, new double[nodes], new double[edges], new double[edges], new double[edges], new double[edges], new double[nodes], new double[edges], new double[edges],
            0, 0, 0, 0, minutes, converged);
    }
}
