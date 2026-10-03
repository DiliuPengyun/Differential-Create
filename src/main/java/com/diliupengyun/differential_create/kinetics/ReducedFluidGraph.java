package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Eliminates degree-two pipe nodes exactly. Layout is cached until a connection
 * or blocked boundary changes; fronts only update numeric mass and force arrays. */
public final class ReducedFluidGraph {
    private record Channel(int from, int to, int[] signedEdges) {}
    private final int nodes;
    private final List<IdealFluidSolver.Edge> edges;
    private final boolean[] boundary, active;
    private final int[] index;
    private final List<Channel> channels = new ArrayList<>();
    private final boolean[] stopped;
    private final int reducedNodes;
    private record NumericGraph(double[] mass, IdealFluidSolver.Graph graph) {}
    private final List<NumericGraph> numericGraphs = new ArrayList<>();

    public ReducedFluidGraph(int nodes, List<IdealFluidSolver.Edge> edges, boolean[] boundary, boolean[] active) {
        this.nodes = nodes; this.edges = edges; this.boundary = boundary.clone(); this.active = active.clone();
        var adjacent = new ArrayList<List<Integer>>(nodes);
        for (int i = 0; i < nodes; i++) adjacent.add(new ArrayList<>());
        for (int e = 0; e < edges.size(); e++) if (active[e]) {
            adjacent.get(edges.get(e).from()).add(e); adjacent.get(edges.get(e).to()).add(e);
        }
        boolean[] keep = new boolean[nodes], visited = new boolean[nodes];
        for (int i = 0; i < nodes; i++) keep[i] = boundary[i] || adjacent.get(i).size() != 2;
        int[] queue = new int[nodes];
        for (int root = 0; root < nodes; root++) if (!visited[root]) {
            int count = 1; queue[0] = root; visited[root] = true; boolean retained = keep[root];
            for (int head = 0; head < count; head++) for (int e : adjacent.get(queue[head])) {
                var edge = edges.get(e); int next = edge.from() == queue[head] ? edge.to() : edge.from();
                if (!visited[next]) { visited[next] = true; queue[count++] = next; retained |= keep[next]; }
            }
            if (!retained) keep[root] = true;
        }
        index = new int[nodes]; Arrays.fill(index, -1); int count = 0;
        for (int i = 0; i < nodes; i++) if (keep[i]) index[i] = count++;
        reducedNodes = count;
        boolean[] used = new boolean[edges.size()];
        for (int start = 0; start < nodes; start++) if (keep[start]) for (int first : adjacent.get(start)) if (!used[first]) {
            var chain = new ArrayList<Integer>(); int node = start, e = first;
            do {
                used[e] = true; var edge = edges.get(e);
                boolean forward = edge.from() == node; chain.add((forward ? 1 : -1) * (e + 1));
                node = forward ? edge.to() : edge.from();
                if (keep[node]) break;
                var next = adjacent.get(node); e = next.get(0) == e ? next.get(1) : next.get(0);
            } while (true);
            channels.add(new Channel(start, node, chain.stream().mapToInt(Integer::intValue).toArray()));
        }
        stopped = new boolean[channels.size()];
        int[] degree = new int[nodes];
        var incident = new ArrayList<List<Integer>>(nodes);
        for (int i = 0; i < nodes; i++) incident.add(new ArrayList<>());
        for (int c = 0; c < channels.size(); c++) {
            var channel = channels.get(c); degree[channel.from]++; degree[channel.to]++;
            incident.get(channel.from).add(c); incident.get(channel.to).add(c);
        }
        int head = 0, tail = 0;
        for (int i = 0; i < nodes; i++) if (!boundary[i] && degree[i] == 1) queue[tail++] = i;
        while (head < tail) {
            int leaf = queue[head++];
            for (int c : incident.get(leaf)) if (!stopped[c]) {
                stopped[c] = true; var channel = channels.get(c);
                int next = channel.from == leaf ? channel.to : channel.from;
                if (!boundary[next] && --degree[next] == 1) queue[tail++] = next;
            }
        }
    }
    public boolean matches(boolean[] boundary, boolean[] active) {
        return Arrays.equals(this.boundary, boundary) && Arrays.equals(this.active, active);
    }
    public int channels() { return channels.size(); }
    /** Empty-space matrix weights have no physical momentum to retain at a held
     * dry front. Any representable liquid in the channel keeps its real inertia. */
    public boolean emptyBoundaryChannel(int node, PipeContents[] contents) {
        boolean found = false;
        for (var channel : channels) if (channel.from == node || channel.to == node) {
            found = true;
            for (int signed : channel.signedEdges) if (contents[Math.abs(signed) - 1].fraction() != 0) return false;
        }
        return found;
    }
    public boolean stationary() {
        for (boolean closed : stopped) if (!closed) return false;
        return true;
    }
    public IdealFluidSolver.Trial sample(double[] initial, double[] inertia, double[] force, double[] pressure, double minutes) {
        var reducedEdges = new ArrayList<IdealFluidSolver.Edge>();
        double[] channelMass = new double[channels.size()], channelFlow = new double[channels.size()], channelForce = new double[channels.size()];
        int[] sampleIndex = new int[channels.size()]; Arrays.fill(sampleIndex, -1);
        for (int c = 0; c < channels.size(); c++) {
            var channel = channels.get(c);
            double uniform = initial[Math.abs(channel.signedEdges[0]) - 1] * Math.signum(channel.signedEdges[0]);
            boolean compatible = true;
            for (int signed : channel.signedEdges) {
                int e = Math.abs(signed) - 1; double sign = Math.signum(signed);
                compatible &= initial[e] * sign == uniform;
                channelMass[c] += inertia[e]; channelFlow[c] += inertia[e] * initial[e] * sign; channelForce[c] += force[e] * sign;
            }
            channelFlow[c] = compatible ? uniform : channelFlow[c] / channelMass[c];
            if (!stopped[c] && channel.from != channel.to) {
                sampleIndex[c] = reducedEdges.size();
                reducedEdges.add(new IdealFluidSolver.Edge(index[channel.from], index[channel.to], channelMass[c]));
            }
        }
        double[] origin = new double[reducedEdges.size()], forcing = new double[origin.length], datum = new double[reducedNodes];
        boolean[] borders = new boolean[reducedNodes], enabled = new boolean[origin.length]; Arrays.fill(enabled, true);
        for (int i = 0; i < nodes; i++) if (index[i] >= 0) { borders[index[i]] = boundary[i]; datum[index[i]] = pressure[i]; }
        for (int c = 0; c < channels.size(); c++) if (sampleIndex[c] >= 0) {
            origin[sampleIndex[c]] = channelFlow[c]; forcing[sampleIndex[c]] = channelForce[c];
        }
        IdealFluidSolver.Graph solver = null;
        for (var cached : numericGraphs) if (Arrays.equals(cached.mass, channelMass)) { solver = cached.graph; break; }
        if (solver == null) {
            solver = new IdealFluidSolver.Graph(reducedNodes, reducedEdges, borders, enabled);
            if (numericGraphs.size() == 4) numericGraphs.remove(0);
            numericGraphs.add(new NumericGraph(channelMass.clone(), solver));
        }
        var trial = solver.sample(origin, forcing, datum, minutes);
        double[] next = new double[edges.size()], midpoint = new double[next.length], outward = new double[nodes], actualPressure = new double[nodes];
        double[] actualOrigin = new double[next.length];
        for (int i = 0; i < nodes; i++) if (index[i] >= 0) actualPressure[i] = trial.pressure()[index[i]];
        double change = 0, work = 0, boundaryWork = 0, initialEnergy = 0, originEnergy = 0;
        for (int e = 0; e < initial.length; e++) initialEnergy += .5 * inertia[e] * initial[e] * initial[e];
        for (int c = 0; c < channels.size(); c++) {
            var channel = channels.get(c); int k = sampleIndex[c];
            double q0 = stopped[c] ? 0 : k < 0 ? channelFlow[c] : trial.origin()[k];
            double q1 = stopped[c] ? 0 : k < 0 ? q0 + minutes * channelForce[c] / channelMass[c] : trial.flow()[k];
            int node = channel.from;
            for (int signed : channel.signedEdges) {
                int e = Math.abs(signed) - 1; double sign = Math.signum(signed); var edge = edges.get(e);
                actualOrigin[e] = sign * q0; next[e] = sign * q1; midpoint[e] = sign * .5 * (q0 + q1);
                outward[edge.from()] += midpoint[e]; outward[edge.to()] -= midpoint[e];
                change += .5 * inertia[e] * (q1 - q0) * (q1 + q0); work += minutes * force[e] * midpoint[e];
                originEnergy += .5 * inertia[e] * q0 * q0;
                int destination = sign > 0 ? edge.to() : edge.from();
                if (index[destination] < 0) actualPressure[destination] = actualPressure[node]
                    + sign * (force[e] - inertia[e] * (q1 - q0) * sign / minutes);
                node = destination;
            }
        }
        for (int i = 0; i < nodes; i++) if (boundary[i]) boundaryWork += minutes * pressure[i] * outward[i];
        double error = change - work - boundaryWork;
        if (Math.abs(error) > 1e-10 * (Math.abs(change) + Math.abs(work) + Math.abs(boundaryWork)) + 1e-12)
            throw new IllegalStateException("Reduced fluid work imbalance: " + error);
        return new IdealFluidSolver.Trial(next, midpoint, actualPressure, outward, change, work, boundaryWork, Math.max(0, initialEnergy - originEnergy), actualOrigin);
    }
}
