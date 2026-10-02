package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Incompressible, frictionless pipe inertances. Time is minutes, flow is mB/tick.
 * Pressures are generalized forces, so force * flow is mechanical power in su.
 * Junction pressure projects motion onto continuity; it never creates energy. */
public final class IdealFluidSolver {
    public static final double MB_VOLUME = 1e-6;
    public static final double AREA = 1e-4;
    public static final double WATTS_PER_SU = 1.0 / 3;
    public static final double GRAVITY = 9.81;
    public static final double REFERENCE_RPM = 64;
    public static final double REFERENCE_FLOW = 32;
    public static final double REFERENCE_HEAD = 16;
    public static final double MAX_STEP = 1.0 / 1200 / 8;
    private static final double FLOW_TO_SI = 20 * MB_VOLUME;

    private IdealFluidSolver() {}

    public record Material(double density, boolean isGas) {
        public Material {
            if (!(density > 0) || !Double.isFinite(density)) throw new IllegalArgumentException("Positive fluid mass required");
        }
        public double inertia(double length) {
            return density * length / AREA * FLOW_TO_SI * FLOW_TO_SI / (60 * WATTS_PER_SU);
        }
        public double gravityForce(double height) {
            return isGas ? 0 : pressureForce(density * GRAVITY * height);
        }
        public double pumpScale() { return pressureForce(density * GRAVITY * REFERENCE_HEAD); }
        /** Kinetic energy carried out to an original inventory, outside the pipe model. */
        public double outletForce(double flow) {
            double velocity = FLOW_TO_SI * flow / AREA;
            return Math.copySign(pressureForce(.5 * density * velocity * velocity), flow);
        }
    }
    public static double pressureForce(double pascals) { return pascals * FLOW_TO_SI / WATTS_PER_SU; }
    public static double pressurePa(double force) { return force * WATTS_PER_SU / FLOW_TO_SI; }

    /** One shared coefficient gives both pressure and finite zero-speed torque.
     * This is a reversible ideal game impeller, not a measured centrifugal pump. */
    public static double coupling(Material fluid, double rpm, double flow) {
        return fluid.pumpScale() / REFERENCE_RPM
            * (Math.abs(rpm) / REFERENCE_RPM - Math.abs(flow) / REFERENCE_FLOW);
    }

    public record Edge(int from, int to, double inertia) {
        public Edge {
            if (from < 0 || to < 0 || from == to || !(inertia > 0) || !Double.isFinite(inertia))
                throw new IllegalArgumentException("Invalid pipe inertance");
        }
    }
    public record Trial(double[] flow, double[] midpoint, double[] pressure, double[] outward,
                        double energyChange, double forceWork, double boundaryWork, double projectionLoss) {}

    public static final class Graph {
        private final int nodes;
        private final List<Edge> edges;
        private final boolean[] boundary, active;
        private final int[] variable;
        private final double[] diagonal;
        private final int variables;

        public Graph(int nodes, List<Edge> edges, boolean[] boundary, boolean[] active) {
            if (nodes < 0 || boundary.length != nodes || active.length != edges.size())
                throw new IllegalArgumentException("Invalid fluid graph");
            this.nodes = nodes; this.edges = List.copyOf(edges);
            this.boundary = boundary.clone(); this.active = active.clone();
            var adjacency = new ArrayList<List<Integer>>(nodes);
            for (int i = 0; i < nodes; i++) adjacency.add(new ArrayList<>());
            for (int e = 0; e < edges.size(); e++) {
                Edge edge = edges.get(e);
                if (edge.from >= nodes || edge.to >= nodes) throw new IllegalArgumentException("Pipe node outside graph");
                if (active[e]) { adjacency.get(edge.from).add(edge.to); adjacency.get(edge.to).add(edge.from); }
            }
            boolean[] fixed = boundary.clone(), visited = new boolean[nodes];
            // Each closed component needs a pressure datum, never an invented mass or drain.
            int[] queue = new int[nodes];
            for (int root = 0; root < nodes; root++) if (!visited[root]) {
                int count = 1; queue[0] = root; visited[root] = true;
                boolean grounded = fixed[root];
                for (int head = 0; head < count; head++) for (int next : adjacency.get(queue[head])) if (!visited[next]) {
                    visited[next] = true; queue[count++] = next; grounded |= fixed[next];
                }
                if (!grounded) fixed[root] = true;
            }
            variable = new int[nodes]; Arrays.fill(variable, -1);
            int count = 0;
            for (int i = 0; i < nodes; i++) if (!fixed[i]) variable[i] = count++;
            variables = count; diagonal = new double[count];
            for (int e = 0; e < edges.size(); e++) if (active[e]) {
                Edge edge = edges.get(e); double weight = 1 / edge.inertia;
                if (variable[edge.from] >= 0) diagonal[variable[edge.from]] += weight;
                if (variable[edge.to] >= 0) diagonal[variable[edge.to]] += weight;
            }
        }

        public double energy(double[] flow) {
            double energy = 0;
            for (int e = 0; e < edges.size(); e++) energy += .5 * edges.get(e).inertia * flow[e] * flow[e];
            return energy;
        }

        /** A topology/blocked-port impulse removes incompatible motion with its loss recorded. */
        public double[] project(double[] flow) {
            double[] result = flow.clone();
            for (int e = 0; e < result.length; e++) if (!active[e]) result[e] = 0;
            correct(result, new double[nodes]);
            return result;
        }

        public Trial sample(double[] initial, double[] forces, double[] boundaryPressure, double minutes) {
            if (initial.length != edges.size() || forces.length != edges.size() || boundaryPressure.length != nodes
                || !(minutes > 0) || !Double.isFinite(minutes)) throw new IllegalArgumentException("Invalid fluid step");
            double[] origin = project(initial), next = origin.clone(), pressure = new double[nodes];
            for (int i = 0; i < nodes; i++) if (boundary[i]) pressure[i] = boundaryPressure[i];
            for (int e = 0; e < edges.size(); e++) if (active[e]) {
                Edge edge = edges.get(e);
                next[e] += minutes / edge.inertia * (forces[e] + pressure[edge.from] - pressure[edge.to]);
            }
            double[] impulse = new double[nodes];
            correct(next, impulse);
            for (int i = 0; i < nodes; i++) if (!boundary[i]) pressure[i] = impulse[i] / minutes;
            double[] midpoint = new double[edges.size()], outward = new double[nodes];
            double energyChange = 0, forceWork = 0, boundaryWork = 0;
            for (int e = 0; e < edges.size(); e++) {
                Edge edge = edges.get(e);
                midpoint[e] = .5 * (next[e] + origin[e]);
                outward[edge.from] += midpoint[e]; outward[edge.to] -= midpoint[e];
                energyChange += .5 * edge.inertia * (next[e] - origin[e]) * (next[e] + origin[e]);
                if (active[e]) forceWork += minutes * forces[e] * midpoint[e];
            }
            for (int i = 0; i < nodes; i++) if (boundary[i]) boundaryWork += minutes * boundaryPressure[i] * outward[i];
            double error = energyChange - forceWork - boundaryWork;
            if (Math.abs(error) > 1e-10 * (Math.abs(energyChange) + Math.abs(forceWork) + Math.abs(boundaryWork)) + 1e-12)
                throw new IllegalStateException("Fluid work imbalance: " + error);
            return new Trial(next, midpoint, pressure, outward, energyChange, forceWork, boundaryWork,
                Math.max(0, energy(initial) - energy(origin)));
        }

        private void correct(double[] flow, double[] pressure) {
            if (variables == 0) return;
            double[] rhs = new double[variables];
            for (int e = 0; e < edges.size(); e++) if (active[e]) {
                Edge edge = edges.get(e);
                if (variable[edge.from] >= 0) rhs[variable[edge.from]] -= flow[e];
                if (variable[edge.to] >= 0) rhs[variable[edge.to]] += flow[e];
            }
            double[] solution = solve(rhs);
            for (int i = 0; i < nodes; i++) if (variable[i] >= 0) pressure[i] = solution[variable[i]];
            for (int e = 0; e < edges.size(); e++) if (active[e]) {
                Edge edge = edges.get(e);
                flow[e] += (pressure[edge.from] - pressure[edge.to]) / edge.inertia;
            }
        }

        private double[] solve(double[] rhs) {
            double[] x = new double[variables], r = rhs.clone(), z = new double[variables], p = new double[variables], ap = new double[variables];
            double scale = 0, rz = 0;
            for (int i = 0; i < variables; i++) {
                z[i] = r[i] / diagonal[i]; p[i] = z[i]; rz += r[i] * z[i]; scale += rhs[i] * rhs[i] / diagonal[i];
            }
            if (scale == 0) return x;
            for (int iteration = 0; iteration < Math.max(64, variables * 4); iteration++) {
                multiply(p, ap);
                double pap = dot(p, ap);
                if (!(pap > 0) || !Double.isFinite(pap)) throw new IllegalStateException("Singular fluid pressure constraint");
                double alpha = rz / pap;
                for (int i = 0; i < variables; i++) { x[i] += alpha * p[i]; r[i] -= alpha * ap[i]; }
                double next = 0;
                for (int i = 0; i < variables; i++) { z[i] = r[i] / diagonal[i]; next += r[i] * z[i]; }
                if (next <= 1e-26 * scale) {
                    // Accept a fresh residual, not only the recursively updated vector.
                    multiply(x, ap); next = 0;
                    for (int i = 0; i < variables; i++) { r[i] = rhs[i] - ap[i]; z[i] = r[i] / diagonal[i]; next += r[i] * z[i]; }
                    if (next <= 1e-24 * scale) return x;
                    System.arraycopy(z, 0, p, 0, variables); rz = next; continue;
                }
                double beta = next / rz;
                for (int i = 0; i < variables; i++) p[i] = z[i] + beta * p[i];
                rz = next;
            }
            throw new IllegalStateException("Fluid pressure solve did not converge");
        }
        private void multiply(double[] x, double[] result) {
            Arrays.fill(result, 0);
            for (int e = 0; e < edges.size(); e++) if (active[e]) {
                Edge edge = edges.get(e); int a = variable[edge.from], b = variable[edge.to];
                double value = ((a < 0 ? 0 : x[a]) - (b < 0 ? 0 : x[b])) / edge.inertia;
                if (a >= 0) result[a] += value;
                if (b >= 0) result[b] -= value;
            }
        }
        private static double dot(double[] a, double[] b) {
            double sum = 0;
            for (int i = 0; i < a.length; i++) sum = Math.fma(a[i], b[i], sum);
            return sum;
        }
    }
}
