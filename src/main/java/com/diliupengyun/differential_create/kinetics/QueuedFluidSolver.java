package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One shared pressure projection for full incompressible multi-fluid cells.
 * Phase inventories are transported separately by ordered queues, never compressed. */
public final class QueuedFluidSolver {
    public static final int CONTACT_PASSES = 3;
    /** Rotation and cached flow use the positive world axis; the block arrow
     * independently selects the pump's pressure/reaction polarity. */
    public record Cell(double capacity, double height, double density, double gravityDensity, double rpm, double pumpFlow, int pumpPolarity) {
        public Cell(double capacity, double height, double density, double gravityDensity, double rpm, double pumpFlow) {
            this(capacity, height, density, gravityDensity, rpm, pumpFlow, 1);
        }
        public Cell {
            if (!(capacity > 0) || !(density > 0) || gravityDensity < 0 || Math.abs(pumpPolarity) != 1
                || !Double.isFinite(capacity + height + density + gravityDensity + rpm + pumpFlow))
                throw new IllegalArgumentException("Invalid queued fluid cell");
        }
        public IdealFluidSolver.Material material() { return new IdealFluidSolver.Material(density, false); }
    }
    public record Boundary(double pressure, boolean supplies, boolean receives, double density) {}
    public record Link(int from, int to, double length, double faceHeight, int fromPump, int toPump, boolean open) {}
    @FunctionalInterface public interface Contact { boolean accepts(int link, boolean forward); }
    public record Result(double[] flow, double[] torque, double[] head, int solves) {
        public Result scale(double scale, List<Cell> cells, List<Link> links) {
            double[] moved = flow.clone(); for (int i = 0; i < moved.length; i++) moved[i] *= scale;
            return finish(moved, cells, links, solves);
        }
    }
    private QueuedFluidSolver() {}
    public static Result stationary(int cells, int links) { return new Result(new double[links], new double[cells], new double[cells], 0); }
    private static void budget(long deadline) {
        if (System.nanoTime() >= deadline) throw new IdealFluidSolver.WorkBudgetException();
    }
    public static Result sample(List<Cell> cells, List<Link> links, Boundary[] boundaries, double[] initial,
                                double minutes, long deadline, Contact contact) {
        if (!(minutes > 0) || !Double.isFinite(minutes) || links.size() != initial.length)
            throw new IllegalArgumentException("Invalid queued fluid interval");
        int count = cells.size(), nodes = count + boundaries.length;
        boolean[] fixed = new boolean[nodes], active = new boolean[links.size()];
        double[] pressure = new double[nodes], force = new double[links.size()], origin = initial.clone();
        var edges = new ArrayList<IdealFluidSolver.Edge>(links.size());
        // A common static head is only a pressure gauge. Remove it before the
        // projection so cancellation noise cannot initiate a whole-world bucket.
        double reference = boundaries.length == 0 ? 0 : boundaries[0].pressure;
        for (int b = 0; b < boundaries.length; b++) {
            fixed[count + b] = true; pressure[count + b] = boundaries[b].pressure - reference;
        }
        int solves = 0;
        try {
            budget(deadline);
            for (int e = 0; e < links.size(); e++) {
                if ((e & 127) == 0) budget(deadline);
                var l = links.get(e); var a = cells.get(l.from); var b = l.to < count ? cells.get(l.to) : null;
                double density = .5 * (a.density + (b == null ? boundaries[l.to - count].density : b.density));
                var medium = new IdealFluidSolver.Material(density, false);
                double inertia = medium.inertia(l.length), slope = 0;
                force[e] = IdealFluidSolver.pressureForce(IdealFluidSolver.GRAVITY *
                    (a.gravityDensity * (a.height - l.faceHeight) + (b == null ? 0 : b.gravityDensity * (l.faceHeight - b.height))));
                if (l.fromPump != 0) {
                    force[e] += .5 * l.fromPump * coupling(a, a.pumpFlow) * a.rpm;
                    slope += .5 * pumpSlope(a);
                }
                if (b != null && l.toPump != 0) {
                    force[e] -= .5 * l.toPump * coupling(b, b.pumpFlow) * b.rpm;
                    slope += .5 * pumpSlope(b);
                }
                if (b == null) force[e] -= medium.outletForce(initial[e]);
                double effective = inertia - minutes * slope;
                force[e] -= slope * initial[e]; origin[e] *= inertia / effective;
                edges.add(new IdealFluidSolver.Edge(l.from, l.to, effective)); active[e] = l.open;
            }
            double[] flow = new double[links.size()];
            for (int pass = 0; pass < CONTACT_PASSES; pass++) {
                budget(deadline); solves++;
                pruneSealedBranches(count, nodes, links, active, deadline);
                flow = new IdealFluidSolver.Graph(nodes, edges, fixed, active, deadline).storedFlow(origin, force, pressure, minutes);
                boolean changed = false;
                for (int e = 0; e < flow.length; e++) if (active[e] && flow[e] != 0) {
                    var l = links.get(e); boolean forward = flow[e] > 0;
                    boolean external = l.to < count || (forward ? boundaries[l.to - count].receives : boundaries[l.to - count].supplies);
                    if (!external || !contact.accepts(e, forward)) { active[e] = false; changed = true; }
                }
                if (!changed) break;
                if (pass == CONTACT_PASSES - 1) return stationary(count, links.size());
            }
            double[] outgoing = new double[count]; double scale = 1;
            for (int e = 0; e < flow.length; e++) {
                var l = links.get(e); double q = flow[e], size = cells.get(l.from).capacity;
                if (l.to < count) size = Math.min(size, cells.get(l.to).capacity);
                if (q != 0) scale = Math.min(scale, .5 * size / Math.abs(q));
                if (q > 0) outgoing[l.from] += q; else if (l.to < count) outgoing[l.to] -= q;
            }
            // A parcel crosses at most one cell per tick, even at a six-way junction.
            for (int n = 0; n < count; n++) if (outgoing[n] > 0)
                scale = Math.min(scale, .5 * cells.get(n).capacity / (outgoing[n] * minutes / RotationMath.TICK_MINUTES));
            for (int e = 0; e < flow.length; e++) flow[e] *= Math.max(0, Math.min(1, scale));
            return finish(flow, cells, links, solves);
        } catch (IdealFluidSolver.WorkBudgetException | IdealFluidSolver.ConstraintConvergenceException | IdealFluidSolver.WorkConvergenceException deferred) {
            return stationary(count, links.size());
        }
    }
    /** A full, incompressible blind branch has exactly zero through flow.
     * Remove it before projection, including when a valve closes on old motion;
     * this avoids cancellation residues at a sealed leaf becoming a new parcel. */
    private static void pruneSealedBranches(int cells, int nodes, List<Link> links, boolean[] active, long deadline) {
        int[] first = new int[nodes], next = new int[2 * links.size()], edge = new int[next.length], degree = new int[nodes];
        Arrays.fill(first, -1);
        for (int e = 0; e < links.size(); e++) if (active[e]) {
            if ((e & 127) == 0) budget(deadline);
            var l = links.get(e); int at = e * 2;
            edge[at] = e; next[at] = first[l.from]; first[l.from] = at; degree[l.from]++;
            edge[at + 1] = e; next[at + 1] = first[l.to]; first[l.to] = at + 1; degree[l.to]++;
        }
        int[] queue = new int[cells]; int head = 0, tail = 0;
        for (int n = 0; n < cells; n++) if (degree[n] == 1) queue[tail++] = n;
        while (head < tail) {
            if ((head & 127) == 0) budget(deadline);
            int n = queue[head++];
            for (int at = first[n]; at >= 0; at = next[at]) if (active[edge[at]]) {
                int e = edge[at]; var l = links.get(e); active[e] = false; degree[n]--;
                int other = l.from == n ? l.to : l.from;
                if (--degree[other] == 1 && other < cells) queue[tail++] = other;
            }
        }
    }
    private static double coupling(Cell c, double through) {
        // One signed coupling supplies both fluid force and shaft reaction.
        // In particular its finite standstill value needs no remembered sign.
        return c.pumpPolarity * IdealFluidSolver.coupling(c.material(), c.rpm, through);
    }
    private static double pumpSlope(Cell c) {
        return -c.material().pumpScale() / IdealFluidSolver.REFERENCE_RPM / IdealFluidSolver.REFERENCE_FLOW * Math.abs(c.rpm);
    }
    private static Result finish(double[] flow, List<Cell> cells, List<Link> links, int solves) {
        double[] torque = new double[cells.size()], head = new double[cells.size()], through = new double[cells.size()];
        for (int e = 0; e < links.size(); e++) {
            var l = links.get(e); double q = flow[e];
            if (l.fromPump != 0) through[l.from] += .5 * l.fromPump * q;
            if (l.to < cells.size() && l.toPump != 0) through[l.to] -= .5 * l.toPump * q;
        }
        for (int n = 0; n < cells.size(); n++) {
            var c = cells.get(n); double coefficient = coupling(c, through[n]);
            torque[n] = -coefficient * through[n];
            head[n] = IdealFluidSolver.pressurePa(coefficient * c.rpm) / (1000 * IdealFluidSolver.GRAVITY);
        }
        return new Result(flow, torque, head, solves);
    }
}
