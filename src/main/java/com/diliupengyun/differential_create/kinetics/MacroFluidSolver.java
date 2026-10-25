package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded, dissipative finite-volume model. One actual fluid store per pipe;
 * unfilled space uses the ambient pressure datum without an air inventory.
 * A mouth/front change takes effect on the following fixed update.
 * There is no contact-time search, parcel subdivision or physical-step retry. */
public final class MacroFluidSolver {
    public static final double MAX_STEP = RotationMath.TICK_MINUTES;
    public static final int CONTACT_PASSES = 3;
    public static final double MIN_WET_FRACTION = 1e-3;
    private MacroFluidSolver() {}

    public record Cell(double capacity, double volume, double bottom, double top,
                       double rpm, double pumpFlow) {
        public Cell {
            if (!(capacity > 0) || !(volume >= 0) || !Double.isFinite(volume)
                || !(top >= bottom))
                throw new IllegalArgumentException("Invalid macro fluid cell");
        }
        public double fraction() { return Math.min(1, volume / capacity); }
        public double surface() { return bottom + (top - bottom) * fraction(); }
        public double empty() { return Math.max(0, capacity - volume); }
    }
    /** Face signs are +1 on a pump's front, -1 on its back, and 0 otherwise. */
    public record Link(int from, int to, double length, double faceHeight,
                       int fromPump, int toPump, boolean open) {}
    /** Available source inventory for this update, including its owned port
     * buffer. A wet mouth does not promise an unlimited replenishing flow. */
    public record Boundary(double head, boolean supplies, boolean receives, boolean exposed, double available) {
        public Boundary(double head, boolean supplies, boolean receives, boolean exposed) {
            this(head, supplies, receives, exposed, supplies ? Double.POSITIVE_INFINITY : 0);
        }
        public Boundary {
            if (!(available >= 0)) throw new IllegalArgumentException("Invalid source inventory");
        }
    }
    public static final class Result {
        public final double[] volume, flow, outward, torque, head;
        public final int solves;
        private final List<Cell> cells;
        private final List<Link> links;
        private Result(List<Cell> cells, List<Link> links,
                       double[] volume, double[] flow, double[] outward,
                       double[] torque, double[] head, int solves) {
            this.cells = cells; this.links = links;
            this.volume = volume; this.flow = flow; this.outward = outward;
            this.torque = torque; this.head = head; this.solves = solves;
        }
        /** A capacity/inventory limit scales transfers, never the physical clock. */
        public Result scale(double scale) {
            if (scale >= 1) return this;
            scale = Math.max(0, scale);
            double[] next = new double[volume.length], q = flow.clone(), out = outward.clone(), t = torque.clone();
            for (int i = 0; i < next.length; i++) next[i] = cells.get(i).volume + scale * (volume[i] - cells.get(i).volume);
            for (int e = 0; e < q.length; e++) q[e] *= scale;
            for (int n = 0; n < out.length; n++) out[n] *= scale;
            for (int n = 0; n < t.length; n++) t[n] *= scale;
            return new Result(cells, links, next, q, out, t, head, solves);
        }
    }
    public static Result sample(List<Cell> cells, List<Link> links, Boundary[] boundaries,
                                double[] initial, IdealFluidSolver.Material material, double minutes) {
        return sample(cells, links, boundaries, initial, material, minutes, Long.MAX_VALUE);
    }
    public static Result stationary(List<Cell> cells, List<Link> links, int boundaries, double minutes) {
        double[] stock = cells.stream().mapToDouble(Cell::volume).toArray();
        return new Result(cells, links, stock, new double[links.size()], new double[cells.size() + boundaries],
            new double[cells.size()], new double[cells.size()], 0);
    }
    public static Result sample(List<Cell> cells, List<Link> links, Boundary[] boundaries,
                                double[] initial, IdealFluidSolver.Material material, double minutes, long deadline) {
        if (!(minutes > 0) || !Double.isFinite(minutes) || initial.length != links.size())
            throw new IllegalArgumentException("Invalid macro fluid interval");
        int count = cells.size(), nodes = count + boundaries.length;
        if (deadline != Long.MAX_VALUE && System.nanoTime() >= deadline) return stationary(cells, links, boundaries.length, minutes);
        boolean[] fixed = new boolean[nodes], enabled = new boolean[links.size()];
        double[] pressure = new double[nodes], storage = new double[nodes];
        double[] forces = new double[links.size()], origin = initial.clone();
        double[] fromGain = new double[links.size()], toGain = new double[links.size()];
        var edges = new ArrayList<IdealFluidSolver.Edge>(links.size());
        double heightDatum = cells.stream().mapToDouble(Cell::bottom).min().orElse(0);
        boolean[] pumpCell = new boolean[count];
        for (var link : links) if (link.open) {
            pumpCell[link.from] |= link.fromPump != 0;
            if (link.to < count) pumpCell[link.to] |= link.toPump != 0;
        }
        boolean sourceLimits = false;
        for (int n = 0; n < count; n++) {
            var cell = cells.get(n);
            boolean partial = !full(cell) && !(pumpCell[n] && resolvedLiquid(cell));
            sourceLimits |= partial;
            double rise = material.gravityForce(cell.top - cell.bottom);
            // Partial ordinary pipes transmit pressure and change stock together:
            // p_new = p_old + rise/capacity * (V_new - V_old).
            // Their finite storage belongs in the existing pressure matrix, not
            // in a zero-divergence constraint triggered by wet mouths.
            fixed[n] = partial && rise == 0;
            if (partial && rise > 0)
                storage[n] = cell.capacity * RotationMath.TICK_MINUTES / (rise * minutes * minutes);
            // A wet impeller remains the existing through-flow abstraction.
            // Preserve its real prime and full curve without inventing fluid.
            pressure[n] = material.gravityForce(cell.surface() - heightDatum);
        }
        for (int b = 0; b < boundaries.length; b++) {
            fixed[count + b] = true;
            pressure[count + b] = boundaries[b].head - material.gravityForce(heightDatum);
        }
        for (int e = 0; e < links.size(); e++) {
            var link = links.get(e); var from = cells.get(link.from);
            var to = link.to < count ? cells.get(link.to) : null;
            double fraction = .5 * (from.fraction() + (to == null ? boundaries[link.to - count].supplies ? 1 : 0 : to.fraction()));
            double inertia = material.inertia(link.length) * Math.max(.25, fraction), effective = inertia;
            enabled[e] = link.open && (from.volume > 0 || to != null && to.volume > 0
                || to == null && boundaries[link.to - count].supplies);
            sourceLimits |= to == null && boundaries[link.to - count].supplies
                && boundaries[link.to - count].available < .5 * from.capacity;
            sourceLimits |= to == null && boundaries[link.to - count].exposed
                && boundaries[link.to - count].receives;
            double tangent = 0;
            if (link.fromPump != 0) {
                fromGain[e] = .5 * wetImpeller(from) * coupling(material, from.rpm, from.pumpFlow);
                forces[e] += link.fromPump * fromGain[e] * from.rpm;
                tangent += .5 * wetImpeller(from) * pumpSlope(material, from.rpm);
            }
            if (to != null && link.toPump != 0) {
                toGain[e] = .5 * wetImpeller(to) * coupling(material, to.rpm, to.pumpFlow);
                forces[e] -= link.toPump * toGain[e] * to.rpm;
                tangent += .5 * wetImpeller(to) * pumpSlope(material, to.rpm);
            }
            // Original inventories do not preserve the arriving column's
            // momentum. Account for that boundary loss; otherwise a balanced
            // pair of tanks can sustain the old flow after reversing a pump.
            if (to == null) forces[e] -= material.outletForce(initial[e]);
            effective -= minutes * tangent;
            forces[e] -= tangent * initial[e];
            origin[e] *= inertia / effective;
            edges.add(new IdealFluidSolver.Edge(link.from, link.to, effective));
        }
        boolean[] pressureEdges = enabled.clone();
        boolean[] starvedReceiver = sourceLimits ? new boolean[count] : null;
        double[] resolvedPressure = sourceLimits ? new double[nodes] : null;
        double[] prescribed = new double[links.size()]; Arrays.fill(prescribed, Double.NaN);
        double[] flow = new double[links.size()]; int solves = 0;
        for (int pass = 0; pass < CONTACT_PASSES; pass++) {
            solves++;
            boolean pressureSolved = true;
            try {
                flow = new IdealFluidSolver.Graph(nodes, edges, fixed, pressureEdges, storage, deadline)
                    .storedFlow(origin, forces, pressure, minutes, prescribed, resolvedPressure);
            } catch (IdealFluidSolver.WorkBudgetException exhausted) {
                return stationary(cells, links, boundaries.length, minutes);
            } catch (IdealFluidSolver.ConstraintConvergenceException | IdealFluidSolver.WorkConvergenceException failure) {
                pressureSolved = false;
                // A bounded local fallback retains inventory and uses hydrostatic
                // head. Do not retry the world clock or enter extended-precision loops.
                for (int e = 0; e < links.size(); e++) {
                    var link = links.get(e);
                    flow[e] = Double.isFinite(prescribed[e]) ? prescribed[e] : enabled[e] ? origin[e] + minutes / edges.get(e).inertia()
                        * (forces[e] + pressure[link.from] - pressure[link.to]) : 0;
                }
            }
            boolean changed = false;
            for (int e = 0; e < flow.length; e++) if (enabled[e] && flow[e] != 0) {
                var link = links.get(e); int donor = flow[e] > 0 ? link.from : link.to, receiver = flow[e] > 0 ? link.to : link.from;
                boolean supplies = donor < count ? wet(cells.get(donor), link.faceHeight, material)
                    : boundaries[donor - count].supplies;
                boolean receives = receiver < count || boundaries[receiver - count].receives;
                if (!supplies || !receives) {
                    if (!supplies && receiver < count && !material.isGas()
                        && (full(cells.get(receiver)) || pumpCell[receiver] && resolvedLiquid(cells.get(receiver))) && !fixed[receiver]
                        && storage[receiver] == 0 && starvedReceiver != null
                        && (donor < count || boundaries[donor - count].exposed)) {
                        // Closing a dry inlet may let an unsupported column
                        // recede. It cannot pin a pressure-supported junction
                        // to ambient head merely because a side arm is dry.
                        // Use the same resolved-head release check below.
                        // Partial cells already retain their storage law.
                        starvedReceiver[receiver] = true;
                    }
                    enabled[e] = false; pressureEdges[e] = false; prescribed[e] = 0; flow[e] = 0; changed = true;
                }
            }
            try {
                if (sourceLimits && pass + 1 < CONTACT_PASSES) changed |= releaseStarvedCells(cells, links, boundaries, pumpCell, enabled, pressureEdges,
                    prescribed, starvedReceiver, fixed, storage, flow, pressure, pressureSolved ? resolvedPressure : null,
                    material, minutes, deadline);
            }
            catch (IdealFluidSolver.WorkBudgetException exhausted) { return stationary(cells, links, boundaries.length, minutes); }
            if (!changed) break;
        }
        try { limitTransfers(cells, links, fixed, storage, enabled, minutes, flow, deadline); }
        catch (IdealFluidSolver.WorkBudgetException exhausted) { return stationary(cells, links, boundaries.length, minutes); }
        double[] net = new double[nodes], activity = new double[nodes];
        double scale = 1;
        for (int e = 0; e < flow.length; e++) {
            var link = links.get(e);
            if (!enabled[e] || !Double.isFinite(flow[e])) flow[e] = 0;
            double transfer = flow[e] * minutes / RotationMath.TICK_MINUTES;
            net[link.from] -= transfer; net[link.to] += transfer;
            activity[link.from] += Math.abs(transfer); activity[link.to] += Math.abs(transfer);
            // A finite-volume update transports at most half a cell per edge.
            // Fast impellers trade unresolved propagation detail for a fixed cost.
            double size = cells.get(link.from).capacity;
            if (link.to < count) size = Math.min(size, cells.get(link.to).capacity);
            // This is a rate limit, independent of shortened machinery slices.
            // A tiny mechanical event cannot bypass the coarse transport cap.
            if (Math.abs(flow[e]) > .5 * size) scale = Math.min(scale, .5 * size / Math.abs(flow[e]));
        }
        for (int n = 0; n < count; n++) {
            var cell = cells.get(n);
            double rounding = 128 * Math.ulp(cell.capacity + activity[n]);
            if (net[n] < -rounding) scale = Math.min(scale, cell.volume / -net[n]);
            if (net[n] > rounding) scale = Math.min(scale, Math.max(0, cell.capacity - cell.volume) / net[n]);
        }
        scale = Math.max(0, Math.min(1, scale));
        double[] next = new double[count], torque = new double[count], head = new double[count], outward = new double[nodes];
        for (int n = 0; n < count; n++) next[n] = Math.max(0, cells.get(n).volume + scale * net[n]);
        for (int e = 0; e < flow.length; e++) {
            var link = links.get(e); flow[e] *= scale;
            outward[link.from] += flow[e]; outward[link.to] -= flow[e];
            var from = cells.get(link.from);
            torque[link.from] += .5 * wetImpeller(from)
                * IdealFluidSolver.pumpReaction(material, from.rpm, link.fromPump * flow[e]);
            if (link.to < count) {
                var to = cells.get(link.to);
                torque[link.to] += .5 * wetImpeller(to)
                    * IdealFluidSolver.pumpReaction(material, to.rpm, -link.toPump * flow[e]);
            }
        }
        for (int n = 0; n < count; n++) if (cells.get(n).rpm != 0 || cells.get(n).pumpFlow != 0) {
            var cell = cells.get(n);
            head[n] = IdealFluidSolver.pressurePa(wetImpeller(cell) * coupling(material, cell.rpm, cell.pumpFlow) * cell.rpm)
                / (material.density() * IdealFluidSolver.GRAVITY);
        }
        try { settleTrace(cells, links, boundaries, pumpCell, material, minutes, next, flow, outward, deadline); }
        catch (IdealFluidSolver.WorkBudgetException exhausted) { return stationary(cells, links, boundaries.length, minutes); }
        return new Result(cells, links, next, flow, outward, torque, head, solves);
    }
    /** A full ordinary cell may use its own water when an arriving stream
     * cannot replenish it. Remove its zero-divergence constraint within the
     * existing contact passes; retain the actual free-surface storage law.
     * A wet impeller with no replenishing inflow can also spend its actual prime.
     * Full downstream cells keep their pressure constraint until the receding
     * front reaches them. No air state or additional pressure pass is needed. */
    private static boolean releaseStarvedCells(List<Cell> cells, List<Link> links, Boundary[] boundaries,
                                              boolean[] pumps, boolean[] enabled, boolean[] pressureEdges, double[] prescribed,
                                              boolean[] starvedReceiver, boolean[] fixed, double[] storage,
                                              double[] flow, double[] freePressure, double[] resolvedPressure, IdealFluidSolver.Material material,
                                              double minutes, long deadline) {
        int count = cells.size(); double[] incoming = new double[count + boundaries.length], outgoing = new double[incoming.length];
        for (int e = 0; e < links.size(); e++) if (enabled[e]) {
            if ((e & 255) == 0) budget(deadline);
            var link = links.get(e); double q = Math.copySign(Math.min(Math.abs(flow[e]), rateLimit(cells, link)), flow[e]);
            // Even zero flow can be the impossible equilibrium of a completely
            // full pipe open to air at both ends. Its resolved pressure, rather
            // than a pre-existing stream, decides whether fullness can persist.
            if (link.to >= count && boundaries[link.to - count].exposed
                && boundaries[link.to - count].receives
                && (full(cells.get(link.from)) && !pumps[link.from]
                    || pumps[link.from] && resolvedLiquid(cells.get(link.from))
                        && !boundaries[link.to - count].supplies)) starvedReceiver[link.from] = true;
            int donor = q > 0 ? link.from : link.to, receiver = q > 0 ? link.to : link.from;
            outgoing[donor] += Math.abs(q); incoming[receiver] += Math.abs(q);
        }
        boolean changed = false;
        for (int e = 0; e < links.size(); e++) if (enabled[e] && flow[e] != 0) {
            if ((e & 255) == 0) budget(deadline);
            var link = links.get(e); int donor = flow[e] > 0 ? link.from : link.to, receiver = flow[e] > 0 ? link.to : link.from;
            if (donor < count && (receiver >= count || material.isGas() || pumps[receiver]
                || fixed[receiver] || storage[receiver] != 0 || !full(cells.get(receiver)))) continue;
            double available = donor < count ? cells.get(donor).volume : boundaries[donor - count].available;
            available = available * RotationMath.TICK_MINUTES / minutes + (donor < count ? incoming[donor] : 0);
            if (!(outgoing[donor] > available + 128 * Math.ulp(available + outgoing[donor]))) continue;
            // Prescribe the real available incoming transfer before resolving
            // the receiving column. Scaling its whole through-flow afterwards
            // would merely lock all previously full inventories again.
            prescribed[e] = Math.copySign(Math.min(Math.abs(flow[e]), rateLimit(cells, link)), flow[e])
                * Math.max(0, Math.min(1, available / outgoing[donor]));
            pressureEdges[e] = false; flow[e] = prescribed[e];
            if (receiver < count && !material.isGas() && !pumps[receiver] && !fixed[receiver]
                && storage[receiver] == 0 && full(cells.get(receiver))) starvedReceiver[receiver] = true;
            changed = true;
        }
        if (resolvedPressure != null) for (int n = 0; n < count; n++) if (starvedReceiver[n] && !fixed[n] && storage[n] == 0) {
            if ((n & 255) == 0) budget(deadline);
            // Scarce supply alone does not prove a pipe will drain. Release
            // only when maintaining fullness would require head below its
            // free surface. A positive pressure must retain the full-cell
            // constraint, especially in driven horizontal delivery runs.
            double rounding = 128 * Math.ulp(Math.abs(resolvedPressure[n]) + Math.abs(freePressure[n]));
            double rise = material.gravityForce(cells.get(n).top - cells.get(n).bottom);
            // Through-flow is an approximation of a supplied wet impeller,
            // not a permanent lock on its last 0.1 mB. Once its inflow is dry,
            // the same storage law lets it discharge its own real water. A
            // supplied impeller still retains its existing through-flow curve.
            boolean unprovidedPump = pumps[n] && incoming[n] <= 128 * Math.ulp(incoming[n] + outgoing[n]);
            if (pumps[n] && !unprovidedPump) continue;
            if (rise > 0 && (unprovidedPump || resolvedPressure[n] < freePressure[n] - rounding)) {
                storage[n] = cells.get(n).capacity * RotationMath.TICK_MINUTES / (rise * minutes * minutes);
                changed = true;
            }
        }
        return changed;
    }
    private static double rateLimit(List<Cell> cells, Link link) {
        return .5 * Math.min(cells.get(link.from).capacity, link.to < cells.size() ? cells.get(link.to).capacity : Double.POSITIVE_INFINITY);
    }
    /** Limit a starved/filling branch without slowing unrelated available
     * inflow. Full pressure cells and wet impellers keep one shared flow scale,
     * so limiting never breaks their simultaneous through-flow constraint.
     * Three linear sweeps suffice for ordinary fronts; the final conservative
     * global bound below covers any remaining coupled limits in a wide graph. */
    private static void limitTransfers(List<Cell> cells, List<Link> links, boolean[] fixed, double[] storage,
                                       boolean[] enabled, double minutes, double[] flow, long deadline) {
        boolean stores = false;
        for (int n = 0; n < cells.size(); n++) stores |= fixed[n] || storage[n] > 0;
        if (!stores) return;
        budget(deadline);
        int count = cells.size(); int[] group = new int[links.size()], first = new int[count], degree = new int[count];
        Arrays.fill(first, -1);
        for (int e = 0; e < group.length; e++) group[e] = e;
        for (int e = 0; e < links.size(); e++) if (enabled[e]) {
            if ((e & 255) == 0) budget(deadline);
            var link = links.get(e);
            coupleCell(group, first, degree, fixed, storage, link.from, e);
            if (link.to < count) coupleCell(group, first, degree, fixed, storage, link.to, e);
        }
        int[][] incident = new int[count][]; double[][] transfer = new double[count][];
        int[] used = new int[count]; double[] scales = new double[group.length]; Arrays.fill(scales, 1);
        for (int n = 0; n < count; n++) { incident[n] = new int[degree[n]]; transfer[n] = new double[degree[n]]; }
        for (int e = 0; e < links.size(); e++) if (enabled[e]) {
            if ((e & 255) == 0) budget(deadline);
            var link = links.get(e); int g = root(group, e); group[e] = g;
            double size = cells.get(link.from).capacity;
            if (link.to < count) size = Math.min(size, cells.get(link.to).capacity);
            if (Math.abs(flow[e]) > .5 * size) scales[g] = Math.min(scales[g], .5 * size / Math.abs(flow[e]));
            double amount = flow[e] * minutes / RotationMath.TICK_MINUTES;
            addTransfer(incident, transfer, used, link.from, g, -amount);
            if (link.to < count) addTransfer(incident, transfer, used, link.to, g, amount);
        }
        for (int pass = 0; pass < CONTACT_PASSES; pass++) {
            boolean changed = false;
            for (int n = 0; n < count; n++) {
                if ((n & 255) == 0) budget(deadline);
                double incoming = 0, outgoing = 0;
                for (int k = 0; k < used[n]; k++) {
                    double amount = transfer[n][k] * scales[incident[n][k]];
                    if (amount > 0) incoming += amount; else outgoing -= amount;
                }
                var cell = cells.get(n); double rounding = 128 * Math.ulp(cell.capacity + incoming + outgoing);
                boolean drain = outgoing - incoming > cell.volume + rounding;
                boolean fill = incoming - outgoing > cell.empty() + rounding;
                if (!drain && !fill) continue;
                double ratio = Math.max(0, Math.min(1, drain ? (cell.volume + incoming) / outgoing : (cell.empty() + outgoing) / incoming));
                for (int k = 0; k < used[n]; k++) if (drain ? transfer[n][k] < 0 : transfer[n][k] > 0)
                    scales[incident[n][k]] *= ratio;
                changed = true;
            }
            if (!changed) break;
        }
        for (int e = 0; e < flow.length; e++) if (enabled[e]) flow[e] *= scales[group[e]];
        budget(deadline);
    }
    private static void budget(long deadline) {
        if (deadline != Long.MAX_VALUE && System.nanoTime() >= deadline) throw new IdealFluidSolver.WorkBudgetException();
    }
    private static void coupleCell(int[] group, int[] first, int[] degree, boolean[] fixed, double[] storage, int n, int edge) {
        degree[n]++;
        if (fixed[n] || storage[n] > 0) return;
        if (first[n] < 0) first[n] = edge;
        else group[root(group, edge)] = root(group, first[n]);
    }
    private static void addTransfer(int[][] incident, double[][] transfer, int[] used, int n, int group, double amount) {
        int k = 0; while (k < used[n] && incident[n][k] != group) k++;
        if (k == used[n]) { incident[n][k] = group; used[n]++; }
        transfer[n][k] += amount;
    }
    private static int root(int[] groups, int n) {
        while (groups[n] != n) { groups[n] = groups[groups[n]]; n = groups[n]; }
        return n;
    }
    /** Below-resolution liquid does not form a pressure column or prime a pump,
     * but still falls through an open vertical connection. Move only the trace
     * present before this pass, so it crosses at most one face per fixed tick.
     * This conservative local settling does not launch another graph solve. */
    private static void settleTrace(List<Cell> cells, List<Link> links, Boundary[] boundaries, boolean[] pumps,
                                    IdealFluidSolver.Material material, double minutes,
                                    double[] volume, double[] flow, double[] outward, long deadline) {
        if (material.isGas()) return;
        double[] trace = new double[cells.size()];
        for (int n = 0; n < trace.length; n++) if ((!pumps[n] || !resolvedLiquid(cells.get(n)))
            && volume[n] < cells.get(n).capacity * MIN_WET_FRACTION) trace[n] = volume[n];
        for (int e = 0; e < links.size(); e++) {
            if ((e & 255) == 0) budget(deadline);
            var link = links.get(e);
            if (!link.open || link.to >= cells.size() || pumps[link.from] || pumps[link.to]) continue;
            var from = cells.get(link.from); var to = cells.get(link.to);
            int upper, lower;
            if (Math.abs(from.bottom - link.faceHeight) < 1e-8 && Math.abs(to.top - link.faceHeight) < 1e-8) {
                upper = link.from; lower = link.to;
            } else if (Math.abs(to.bottom - link.faceHeight) < 1e-8 && Math.abs(from.top - link.faceHeight) < 1e-8) {
                upper = link.to; lower = link.from;
            } else continue;
            // The pressure solve may already have used this edge's allowance.
            // Settling shares the same signed rate cap rather than adding a
            // second allowance to a nearly empty, fast-moving cell.
            double sign = upper == link.from ? 1 : -1;
            double remainingRate = Math.max(0, .5 * Math.min(from.capacity, to.capacity) - sign * flow[e]);
            double rateLimit = remainingRate * minutes / RotationMath.TICK_MINUTES;
            double moved = Math.min(rateLimit, Math.min(trace[upper], Math.max(0, cells.get(lower).capacity - volume[lower])));
            if (!(moved > 0)) continue;
            trace[upper] -= moved; volume[upper] -= moved; volume[lower] += moved;
            double q = sign * moved * RotationMath.TICK_MINUTES / minutes;
            flow[e] += q; outward[link.from] += q; outward[link.to] -= q;
        }
        drainHorizontalTrace(cells, links, boundaries, pumps, material, minutes, trace, volume, flow, outward, deadline);
    }
    /** Resolve only the remaining sub-resolution droplets. A route through
     * equally low cells to an exposed lower mouth or lower elbow lets them
     * leave as real outflow. Sealed runs and raised reservoirs retain water.
     * A dry impeller has no pump head or hydraulic work, but is not a shutoff
     * valve. Let these real drops pass without releasing a wet pump's prime.
     * Each original droplet crosses at most one face in this fixed update. */
    private static void drainHorizontalTrace(List<Cell> cells, List<Link> links, Boundary[] boundaries, boolean[] pumps,
                                             IdealFluidSolver.Material material, double minutes, double[] trace,
                                             double[] volume, double[] flow, double[] outward, long deadline) {
        boolean any = false;
        for (double amount : trace) any |= amount > 0;
        if (!any) return;
        int count = cells.size();
        int[] route = new int[count], distance = new int[count], queue = new int[count];
        Arrays.fill(route, -1); Arrays.fill(distance, -1);
        int tail = 0;
        double half = .5 * Math.sqrt(IdealFluidSolver.AREA);
        for (int e = 0; e < links.size(); e++) {
            if ((e & 255) == 0) budget(deadline);
            var link = links.get(e);
            if (!link.open) continue;
            if (link.to < count) {
                for (int direction = 0; direction < 2; direction++) {
                    int donor = direction == 0 ? link.from : link.to, receiver = direction == 0 ? link.to : link.from;
                    var cell = cells.get(donor); var lower = cells.get(receiver);
                    // A level run can empty into the lower floor of an elbow.
                    // Test its current receiving surface after normal transfers.
                    double surface = lower.bottom + (lower.top - lower.bottom) * Math.min(1, volume[receiver] / lower.capacity);
                    if (pumps[donor] && resolvedLiquid(cell) || pumps[receiver] && resolvedLiquid(lower)
                        || volume[donor] >= cell.capacity * MIN_WET_FRACTION
                        || Math.abs(cell.bottom - (link.faceHeight - half)) > 1e-8
                        || lower.bottom >= cell.bottom - 1e-8 || surface > cell.bottom + 1e-8
                        || volume[receiver] >= lower.capacity) continue;
                    if (distance[donor] < 0) {
                        distance[donor] = 0; route[donor] = e; queue[tail++] = donor;
                    }
                }
                continue;
            }
            var cell = cells.get(link.from);
            if (pumps[link.from] && resolvedLiquid(cell)
                || volume[link.from] >= cell.capacity * MIN_WET_FRACTION) continue;
            if (Math.abs(cell.bottom - (link.faceHeight - half)) > 1e-8
                && Math.abs(cell.bottom - link.faceHeight) > 1e-8) continue;
            var boundary = boundaries[link.to - count];
            double bottomHead = material.gravityForce(cell.bottom);
            // A native machine may both supply and receive real stock at an
            // exposed mouth. Its ability to extract does not obstruct this
            // downhill receipt; the head and capacity still decide admission.
            if (!boundary.exposed || !boundary.receives
                || boundary.head > bottomHead + 128 * Math.ulp(Math.abs(bottomHead))) continue;
            if (distance[link.from] < 0) {
                distance[link.from] = 0; route[link.from] = e; queue[tail++] = link.from;
            }
        }
        if (tail == 0) return;
        int[] first = new int[count], neighbor = new int[2 * links.size()], edge = new int[neighbor.length], next = new int[neighbor.length];
        Arrays.fill(first, -1); int used = 0;
        for (int e = 0; e < links.size(); e++) {
            if ((e & 255) == 0) budget(deadline);
            var link = links.get(e);
            if (!link.open || link.to >= count) continue;
            var from = cells.get(link.from); var to = cells.get(link.to);
            if (pumps[link.from] && resolvedLiquid(from) || pumps[link.to] && resolvedLiquid(to)
                || volume[link.from] >= from.capacity * MIN_WET_FRACTION
                || volume[link.to] >= to.capacity * MIN_WET_FRACTION
                || Math.abs(from.bottom - (link.faceHeight - half)) > 1e-8
                || Math.abs(to.bottom - (link.faceHeight - half)) > 1e-8) continue;
            neighbor[used] = link.to; edge[used] = e; next[used] = first[link.from]; first[link.from] = used++;
            neighbor[used] = link.from; edge[used] = e; next[used] = first[link.to]; first[link.to] = used++;
        }
        for (int head = 0; head < tail; head++) {
            if ((head & 255) == 0) budget(deadline);
            int n = queue[head];
            for (int p = first[n]; p >= 0; p = next[p]) if (distance[neighbor[p]] < 0) {
                int other = neighbor[p]; distance[other] = distance[n] + 1;
                route[other] = edge[p]; queue[tail++] = other;
            }
        }
        for (int n = 0; n < count; n++) if (trace[n] > 0 && route[n] >= 0) {
            if ((n & 255) == 0) budget(deadline);
            int e = route[n]; var link = links.get(e);
            double sign = n == link.from ? 1 : -1;
            int receiver = n == link.from ? link.to : link.from;
            double space = receiver < count ? cells.get(receiver).capacity - volume[receiver] : Double.POSITIVE_INFINITY;
            double allowance = Math.max(0, rateLimit(cells, link) - sign * flow[e]) * minutes / RotationMath.TICK_MINUTES;
            double moved = Math.min(allowance, Math.min(space, Math.min(trace[n], volume[n])));
            if (!(moved > 0)) continue;
            trace[n] -= moved; volume[n] -= moved;
            if (receiver < count) volume[receiver] += moved;
            double q = sign * moved * RotationMath.TICK_MINUTES / minutes;
            flow[e] += q; outward[link.from] += q; outward[link.to] -= q;
        }
    }
    private static boolean resolvedLiquid(Cell cell) { return cell.volume >= cell.capacity * MIN_WET_FRACTION; }
    // Pressure continuity must not switch back to finite storage for a few
    // floating-point ulps of unused capacity. Keep the actual stock unchanged.
    private static boolean full(Cell cell) { return cell.empty() <= 128 * Math.ulp(cell.capacity); }
    private static double wetImpeller(Cell cell) { return resolvedLiquid(cell) ? 1 : 0; }
    static boolean wet(Cell cell, double face, IdealFluidSolver.Material material) {
        // Sub-resolution residue remains real inventory and can receive more
        // liquid, but cannot throttle the entire connected graph via 1e-100 flows.
        return resolvedLiquid(cell) && touches(cell, face, material);
    }
    static boolean touches(Cell cell, double face, IdealFluidSolver.Material material) {
        // Actual contact and local droplet discharge have no pressure-column
        // resolution threshold. They spend even a real sub-resolution parcel.
        // A horizontal mouth spans the bore height. Using its centre as the
        // lowest wet point left a permanent puddle in an elbow's bottom arm.
        double lip = face > cell.bottom + 1e-8 && face < cell.top - 1e-8
            ? face - .5 * Math.sqrt(IdealFluidSolver.AREA) : face;
        return cell.volume > 0 && (material.isGas() || full(cell)
            || cell.top - cell.bottom < .02 || cell.surface() >= lip - 1e-10);
    }
    private static double coupling(IdealFluidSolver.Material material, double rpm, double flow) {
        return IdealFluidSolver.coupling(material, rpm, flow);
    }
    private static double pumpSlope(IdealFluidSolver.Material material, double rpm) {
        return -material.pumpScale() / IdealFluidSolver.REFERENCE_RPM / IdealFluidSolver.REFERENCE_FLOW * Math.abs(rpm);
    }
}
