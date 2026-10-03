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
    public static double pumpSlope(Material fluid, double rpm, double flow) {
        double direction = flow == 0 ? Math.signum(rpm) : Math.signum(flow);
        return -fluid.pumpScale() / REFERENCE_RPM / REFERENCE_FLOW * rpm * direction;
    }

    public record Edge(int from, int to, double inertia) {
        public Edge {
            if (from < 0 || to < 0 || from == to || !(inertia > 0) || !Double.isFinite(inertia))
                throw new IllegalArgumentException("Invalid pipe inertance");
        }
    }
    public record Trial(double[] flow, double[] midpoint, double[] pressure, double[] outward,
                        double energyChange, double forceWork, double boundaryWork, double projectionLoss, double[] origin) {
        public Trial(double[] flow, double[] midpoint, double[] pressure, double[] outward,
                     double energyChange, double forceWork, double boundaryWork, double projectionLoss) {
            this(flow, midpoint, pressure, outward, energyChange, forceWork, boundaryWork, projectionLoss, flow.clone());
        }
    }

    public static final class Graph {
        private final int nodes;
        private final List<Edge> edges;
        private final boolean[] boundary, active;
        private final int[] variable;
        private final double[] diagonal;
        private final int variables;
        private FlowTree flowTree;
        private final PressureForest forest;
        private final PressureBand band;

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
            forest = PressureForest.create(variables, edges, this.active, variable);
            band = forest == null ? PressureBand.create(variables, edges, this.active, variable, diagonal) : null;
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
                Math.max(0, energy(initial) - energy(origin)), origin);
        }

        private void correct(double[] flow, double[] pressure) {
            if (variables == 0) return;
            double[] rhs = new double[variables], compensation = new double[variables], scale = new double[variables];
            for (int refinement = 0; refinement < 5; refinement++) {
                Arrays.fill(rhs, 0); Arrays.fill(compensation, 0); Arrays.fill(scale, 0);
                for (int e = 0; e < edges.size(); e++) if (active[e]) {
                    Edge edge = edges.get(e);
                    accumulate(rhs, compensation, scale, variable[edge.from], -flow[e]);
                    accumulate(rhs, compensation, scale, variable[edge.to], flow[e]);
                }
                boolean continuous = true;
                for (int i = 0; i < variables; i++) continuous &= Math.abs(rhs[i]) <= 8 * Math.ulp(scale[i]);
                if (continuous) return;
                if (refinement == 4) {
                    if (flowTree == null) {
                        boolean[] fixed = new boolean[nodes];
                        for (int i = 0; i < nodes; i++) fixed[i] = variable[i] < 0;
                        flowTree = FlowTree.create(nodes, edges, active, fixed);
                    }
                    if (flowTree.closeRoundoff(flow, edges, active, variable)) return;
                    throw new IllegalStateException("Fluid flow correction did not converge");
                }
                double[] solution = solve(rhs);
                for (int i = 0; i < nodes; i++) if (variable[i] >= 0) pressure[i] += solution[variable[i]];
                for (int e = 0; e < edges.size(); e++) if (active[e]) {
                    Edge edge = edges.get(e); int a = variable[edge.from], b = variable[edge.to];
                    flow[e] += ((a < 0 ? 0 : solution[a]) - (b < 0 ? 0 : solution[b])) / edge.inertia;
                }
                // A dry segment can be 1e12 lighter than a wet one. Subtracting its
                // large unconstrained impulse loses digits even when pressure's
                // linear residual is small. Refine the resulting physical flows;
                // this is the same constraint solve, with no damping or cutoff.
            }
        }

        private static void accumulate(double[] rhs, double[] compensation, double[] scale, int node, double value) {
            if (node < 0) return;
            double term = value - compensation[node], next = rhs[node] + term;
            compensation[node] = (next - rhs[node]) - term; rhs[node] = next;
            scale[node] += Math.abs(value);
        }

        private double[] solve(double[] rhs) {
            if (forest != null) return forest.solve(rhs);
            if (band != null) return band.solve(rhs);
            double[] x = new double[variables], r = rhs.clone(), z = new double[variables], p = new double[variables], ap = new double[variables];
            double scale = 0, rz = 0;
            for (int i = 0; i < variables; i++) {
                z[i] = r[i] / diagonal[i]; p[i] = z[i]; rz += r[i] * z[i]; scale += rhs[i] * rhs[i] / diagonal[i];
            }
            if (scale == 0) return x;
            for (int iteration = 0; iteration < Math.max(64, variables * 4); iteration++) {
                double pap = multiply(p, ap);
                if (!(pap > 0) || !Double.isFinite(pap)) throw new IllegalStateException("Singular fluid pressure constraint");
                double alpha = rz / pap;
                for (int i = 0; i < variables; i++) { x[i] += alpha * p[i]; r[i] -= alpha * ap[i]; }
                double next = 0;
                for (int i = 0; i < variables; i++) { z[i] = r[i] / diagonal[i]; next += r[i] * z[i]; }
                if (next <= 1e-26 * scale) {
                    // Accept a fresh residual, not only the recursively updated vector.
                    multiply(x, ap); next = 0;
                    for (int i = 0; i < variables; i++) { r[i] = rhs[i] - ap[i]; z[i] = r[i] / diagonal[i]; next += r[i] * z[i]; }
                    if (next <= 1e-24 * scale || roundoffResidual(rhs, x, r)) return x;
                    System.arraycopy(z, 0, p, 0, variables); rz = next; continue;
                }
                double beta = next / rz;
                for (int i = 0; i < variables; i++) p[i] = z[i] + beta * p[i];
                rz = next;
            }
            multiply(x, ap);
            for (int i = 0; i < variables; i++) r[i] = rhs[i] - ap[i];
            if (roundoffResidual(rhs, x, r)) return x;
            throw new IllegalStateException("Fluid pressure solve did not converge");
        }
        private boolean roundoffResidual(double[] rhs, double[] x, double[] residual) {
            double[] magnitude = new double[variables];
            for (int i = 0; i < variables; i++) magnitude[i] = Math.abs(rhs[i]);
            for (int e = 0; e < edges.size(); e++) if (active[e]) {
                Edge edge = edges.get(e); int a = variable[edge.from], b = variable[edge.to];
                // Bound rounding from both pressure operands before subtracting
                // nearly equal values across a very light pipe. Actual flow is
                // checked and refined separately, at its own much smaller scale.
                double value = ((a < 0 ? 0 : Math.abs(x[a])) + (b < 0 ? 0 : Math.abs(x[b]))) / edge.inertia;
                if (a >= 0) magnitude[a] += value;
                if (b >= 0) magnitude[b] += value;
            }
            for (int i = 0; i < variables; i++)
                if (!Double.isFinite(magnitude[i]) || !(Math.abs(residual[i]) <= 8 * Math.ulp(magnitude[i]))) return false;
            return true;
        }
        private double multiply(double[] x, double[] result) {
            Arrays.fill(result, 0);
            double quadratic = 0;
            for (int e = 0; e < edges.size(); e++) if (active[e]) {
                Edge edge = edges.get(e); int a = variable[edge.from], b = variable[edge.to];
                double difference = (a < 0 ? 0 : x[a]) - (b < 0 ? 0 : x[b]);
                double value = difference / edge.inertia;
                if (a >= 0) result[a] += value;
                if (b >= 0) result[b] -= value;
                // The edge energy form stays positive even when x and A*x
                // contain cancelling terms from adjacent dry segments.
                quadratic = Math.fma(difference, value, quadratic);
            }
            return quadratic;
        }

        /** Close cancellation roundoff by summing incident flows on a spanning
         * forest. This is used only after pressure refinement; changes are bounded
         * by accumulated floating-point ULPs, never by a physical flow cutoff. */
        private record FlowTree(int[] order, int[] parentEdge, List<List<Integer>> incident) {
            static FlowTree create(int nodes, List<Edge> edges, boolean[] active, boolean[] fixed) {
                var incident = new ArrayList<List<Integer>>(nodes);
                for (int i = 0; i < nodes; i++) incident.add(new ArrayList<>());
                for (int e = 0; e < edges.size(); e++) if (active[e]) {
                    incident.get(edges.get(e).from).add(e); incident.get(edges.get(e).to).add(e);
                }
                int[] queue = new int[nodes], parent = new int[nodes], order = new int[nodes];
                Arrays.fill(parent, -1);
                boolean[] visited = fixed.clone(); int tail = 0, count = 0;
                for (int i = 0; i < nodes; i++) if (fixed[i]) queue[tail++] = i;
                for (int head = 0; head < tail; head++) for (int e : incident.get(queue[head])) {
                    var edge = edges.get(e); int node = edge.from == queue[head] ? edge.to : edge.from;
                    if (!visited[node]) { visited[node] = true; parent[node] = e; queue[tail++] = node; order[count++] = node; }
                }
                return new FlowTree(Arrays.copyOf(order, count), parent, incident);
            }
            boolean closeRoundoff(double[] flow, List<Edge> edges, boolean[] active, int[] variable) {
                double maximum = 0;
                for (double q : flow) maximum = Math.max(maximum, Math.abs(q));
                double bound = 32 * Math.max(1, order.length) * Math.ulp(maximum);
                double[] closed = flow.clone();
                for (int k = order.length - 1; k >= 0; k--) {
                    int node = order[k], parent = parentEdge[node]; double sum = 0, compensation = 0;
                    for (int e : incident.get(node)) if (e != parent) {
                        double value = (edges.get(e).from == node ? closed[e] : -closed[e]) - compensation;
                        double next = sum + value; compensation = (next - sum) - value; sum = next;
                    }
                    double value = edges.get(parent).from == node ? -sum : sum;
                    if (!Double.isFinite(value) || Math.abs(value - flow[parent]) > bound) return false;
                    closed[parent] = value;
                }
                double[] rhs = new double[order.length], compensation = new double[order.length], scale = new double[order.length];
                for (int e = 0; e < edges.size(); e++) if (active[e]) {
                    var edge = edges.get(e);
                    accumulate(rhs, compensation, scale, variable[edge.from], -closed[e]);
                    accumulate(rhs, compensation, scale, variable[edge.to], closed[e]);
                }
                for (int i = 0; i < rhs.length; i++) if (Math.abs(rhs[i]) > 8 * Math.ulp(scale[i])) return false;
                System.arraycopy(closed, 0, flow, 0, flow.length);
                return true;
            }
        }

        /** Exact Schur elimination when the unknown-pressure graph is a forest.
         * Long branched networks can have exponentially small distant flows;
         * a global CG residual cannot resolve every local continuity constraint.
         * Positive shunt conductances avoid subtracting almost equal diagonals
         * when a dry channel is 1e12 lighter than its liquid-filled neighbour. */
        private record PressureForest(int[] order, int[] parent, double[] weight, double[] ground) {
            static PressureForest create(int count, List<Edge> edges, boolean[] active, int[] variable) {
                var adjacent = new ArrayList<List<Integer>>(count);
                for (int i = 0; i < count; i++) adjacent.add(new ArrayList<>());
                double[] ground = new double[count], weight = new double[count];
                for (int e = 0; e < edges.size(); e++) if (active[e]) {
                    var edge = edges.get(e); int a = variable[edge.from], b = variable[edge.to];
                    if (a >= 0 && b >= 0) { adjacent.get(a).add(e); adjacent.get(b).add(e); }
                    else if (a >= 0) ground[a] += 1 / edge.inertia;
                    else if (b >= 0) ground[b] += 1 / edge.inertia;
                }
                int[] parent = new int[count], parentEdge = new int[count], order = new int[count];
                Arrays.fill(parent, -2); Arrays.fill(parentEdge, -1);
                int tail = 0;
                for (int root = 0; root < count; root++) if (parent[root] == -2) {
                    int head = tail; parent[root] = -1; order[tail++] = root;
                    while (head < tail) {
                        int node = order[head++];
                        for (int e : adjacent.get(node)) if (e != parentEdge[node]) {
                            var edge = edges.get(e); int a = variable[edge.from], b = variable[edge.to], next = a == node ? b : a;
                            if (parent[next] != -2) return null; // A loop uses the existing pressure solver.
                            parent[next] = node; parentEdge[next] = e; weight[next] = 1 / edge.inertia; order[tail++] = next;
                        }
                    }
                }
                return new PressureForest(order, parent, weight, ground);
            }
            double[] solve(double[] rhs) {
                double[] shunt = ground.clone(), reduced = rhs.clone(), result = new double[rhs.length];
                for (int k = order.length - 1; k >= 0; k--) {
                    int node = order[k], p = parent[node];
                    if (p < 0) continue;
                    double ratio = weight[node] / (weight[node] + shunt[node]);
                    shunt[p] += ratio * shunt[node];
                    reduced[p] = Math.fma(ratio, reduced[node], reduced[p]);
                }
                for (int node : order) {
                    int p = parent[node];
                    double divisor = shunt[node] + weight[node];
                    if (!(divisor > 0)) throw new IllegalStateException("Singular fluid pressure forest");
                    result[node] = p < 0 ? reduced[node] / divisor : Math.fma(weight[node], result[p], reduced[node]) / divisor;
                }
                return result;
            }
        }

        /** Bounded banded LDL factor for compact loop networks. The factor is
         * immutable and reusable for different forces/initial velocities. Wide
         * arbitrary graphs and numerically poor pivots retain the CG path. */
        private record PressureBand(int count, int width, double[] factor, int[] permutation) {
            static PressureBand create(int count, List<Edge> edges, boolean[] active, int[] variable, double[] diagonal) {
                int[] permutation = ordering(count, edges, active, variable);
                int width = 0;
                double smallest = Double.POSITIVE_INFINITY, largest = 0;
                for (int e = 0; e < edges.size(); e++) if (active[e]) {
                    var edge = edges.get(e); int a = variable[edge.from], b = variable[edge.to];
                    if (a >= 0 && b >= 0) width = Math.max(width, Math.abs(permutation[a] - permutation[b]));
                    smallest = Math.min(smallest, edge.inertia); largest = Math.max(largest, edge.inertia);
                }
                // Direct floating-point pivots are unsuitable for the extreme
                // wet/dry mass ratio. This only selects a numerical algorithm;
                // all masses and physical constraints stay unchanged in CG.
                if (largest / smallest > 1e6 || width > 64 || (long) count * (width + 1) > 262144) return null;
                int stride = width + 1;
                double[] factor = new double[count * stride];
                for (int i = 0; i < count; i++) factor[permutation[i] * stride] = diagonal[i];
                for (int e = 0; e < edges.size(); e++) if (active[e]) {
                    var edge = edges.get(e); int a = variable[edge.from], b = variable[edge.to];
                    if (a >= 0 && b >= 0) {
                        a = permutation[a]; b = permutation[b];
                        factor[Math.max(a, b) * stride + Math.abs(a - b)] -= 1 / edge.inertia;
                    }
                }
                for (int row = 0; row < count; row++) {
                    int start = Math.max(0, row - width);
                    for (int column = start; column < row; column++) {
                        double value = factor[row * stride + row - column];
                        for (int k = start; k < column; k++) value = Math.fma(
                            -factor[row * stride + row - k] * factor[k * stride], factor[column * stride + column - k], value);
                        factor[row * stride + row - column] = value / factor[column * stride];
                    }
                    double value = factor[row * stride];
                    for (int k = start; k < row; k++) {
                        double lower = factor[row * stride + row - k];
                        value = Math.fma(-lower * lower, factor[k * stride], value);
                    }
                    if (!(value > 64 * Math.ulp(factor[row * stride])) || !Double.isFinite(value)) return null;
                    factor[row * stride] = value;
                }
                return new PressureBand(count, width, factor, permutation);
            }
            private static int[] ordering(int count, List<Edge> edges, boolean[] active, int[] variable) {
                var adjacent = new ArrayList<List<Integer>>(count);
                for (int i = 0; i < count; i++) adjacent.add(new ArrayList<>());
                for (int e = 0; e < edges.size(); e++) if (active[e]) {
                    var edge = edges.get(e); int a = variable[edge.from], b = variable[edge.to];
                    if (a >= 0 && b >= 0) { adjacent.get(a).add(b); adjacent.get(b).add(a); }
                }
                for (var neighbours : adjacent) neighbours.sort(java.util.Comparator
                    .comparingInt((Integer n) -> adjacent.get(n).size()).thenComparingInt(n -> n));
                int[] order = new int[count], permutation = new int[count];
                boolean[] visited = new boolean[count]; int tail = 0;
                while (tail < count) {
                    int root = -1;
                    for (int n = 0; n < count; n++) if (!visited[n] && (root < 0 || adjacent.get(n).size() < adjacent.get(root).size())) root = n;
                    int head = tail; order[tail++] = root; visited[root] = true;
                    while (head < tail) for (int next : adjacent.get(order[head++])) if (!visited[next]) {
                        visited[next] = true; order[tail++] = next;
                    }
                }
                for (int i = 0; i < count; i++) permutation[order[i]] = count - 1 - i;
                return permutation;
            }
            double[] solve(double[] rhs) {
                int stride = width + 1;
                double[] result = new double[count];
                for (int i = 0; i < count; i++) result[permutation[i]] = rhs[i];
                for (int row = 0; row < count; row++)
                    for (int column = Math.max(0, row - width); column < row; column++)
                        result[row] = Math.fma(-factor[row * stride + row - column], result[column], result[row]);
                for (int row = 0; row < count; row++) result[row] /= factor[row * stride];
                for (int row = count - 1; row >= 0; row--)
                    for (int column = row + 1; column < Math.min(count, row + width + 1); column++)
                        result[row] = Math.fma(-factor[column * stride + column - row], result[column], result[row]);
                double[] original = new double[count];
                for (int i = 0; i < count; i++) original[i] = result[permutation[i]];
                return original;
            }
        }
    }
}
