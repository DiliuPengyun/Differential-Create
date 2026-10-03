package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Contact-aware, one-dimensional closure for an existing pipe junction.
 * Opposite ports form straight free-slip lanes while the other lanes are dry.
 * A wall, contacting transverse liquid, or positive liquid pressure couples the
 * lanes. This changes pressure constraints, never applies a routing percentage.
 * Port numbers are opposite pairs (0/1, 2/3, 4/5), independent of Minecraft. */
public final class DirectionalFluidJunctions {
    private record Arm(int edge, boolean start, int direction) {}
    private static final byte LANES = 0, CONTACT = 1, FREE = 2;
    private record Junction(int node, int[] axisNodes, int[] portNodes, List<Arm> arms) {}
    private final List<IdealFluidSolver.Edge> originalEdges;
    private final List<PipeFilling.Tube> originalTubes;
    private final List<Junction> junctions = new ArrayList<>();
    private final Map<String, Layout> layouts = new LinkedHashMap<>();
    private final int nodes;

    public DirectionalFluidJunctions(int originalNodes, List<IdealFluidSolver.Edge> edges,
                                    List<PipeFilling.Tube> tubes, int[] fromDirections, int[] toDirections) {
        if (edges.size() != tubes.size() || edges.size() != fromDirections.length || edges.size() != toDirections.length)
            throw new IllegalArgumentException("Junction geometry does not match pipe channels");
        originalEdges = List.copyOf(edges); originalTubes = List.copyOf(tubes);
        var incident = new ArrayList<List<Arm>>(originalNodes);
        for (int n = 0; n < originalNodes; n++) incident.add(new ArrayList<>());
        for (int e = 0; e < edges.size(); e++) {
            var edge = edges.get(e);
            if (fromDirections[e] >= 0) incident.get(edge.from()).add(new Arm(e, true, fromDirections[e]));
            if (toDirections[e] >= 0) incident.get(edge.to()).add(new Arm(e, false, toDirections[e]));
        }
        int count = originalNodes;
        for (int n = 0; n < originalNodes; n++) if (incident.get(n).size() >= 3) {
            int[] axisNodes = {-1, -1, -1};
            int[] portNodes = {-1, -1, -1, -1, -1, -1};
            for (var arm : incident.get(n)) {
                if (arm.direction >= 6) throw new IllegalArgumentException("Invalid junction direction");
                if (portNodes[arm.direction] >= 0) throw new IllegalArgumentException("Duplicate junction port");
                portNodes[arm.direction] = count++;
                int axis = arm.direction / 2;
                if (axisNodes[axis] < 0) axisNodes[axis] = portNodes[arm.direction];
            }
            junctions.add(new Junction(n, axisNodes, portNodes, List.copyOf(incident.get(n))));
        }
        nodes = count;
    }

    public int nodes() { return nodes; }
    public int junctions() { return junctions.size(); }

    public Layout select(PipeContents[] contents) {
        byte[] modes = new byte[junctions.size()];
        for (int j = 0; j < modes.length; j++) {
            var junction = junctions.get(j);
            int wetAxes = 0, ports = 0;
            for (var arm : junction.arms) {
                ports |= 1 << arm.direction;
                if (touches(arm, contents)) wetAxes |= 1 << (arm.direction / 2);
            }
            modes[j] = wetAxes == 0 ? FREE : Integer.bitCount(wetAxes) > 1 ? CONTACT : LANES;
            // With no straight continuation the stationary wall supplies the
            // turning reaction. Empty space must not act as a liquid source.
            for (var arm : junction.arms) if (touches(arm, contents) && (ports & (1 << (arm.direction ^ 1))) == 0)
                modes[j] = CONTACT;
        }
        return layout(modes, new int[modes.length]);
    }

    /** A free side can be invaded by positive liquid pressure, not by an
     * unconditional preference for a branch. Re-solve all pump forces together
     * after opening contact; the caller has not moved any actual inventory. */
    public Layout pressurise(Layout current, PipeContents[] contents, double[] pressure, double[] flow,
                             IdealFluidSolver.Material material) {
        byte[] modes = current.modes.clone(); boolean changed = false;
        for (int j = 0; j < modes.length; j++) if (modes[j] == LANES) {
            var junction = junctions.get(j);
            double scale = 0, incoming = 0, kinetic = 0;
            for (int node : junction.axisNodes) if (node >= 0) scale = Math.max(scale, Math.abs(pressure[node]));
            for (var arm : junction.arms) if (touches(arm, contents)) {
                double outward = arm.start ? flow[arm.edge] : -flow[arm.edge];
                if (outward < 0) {
                    double head = Math.abs(material.outletForce(outward));
                    incoming -= outward; kinetic -= outward * head; scale = Math.max(scale, head);
                }
            }
            // The inventory/moving-mass formulation carries the incoming kinetic
            // energy in its junction head. A coasting jet must spend no transverse
            // pressure simply because it brings kinetic energy to an empty lane.
            double kineticHead = incoming == 0 ? 0 : kinetic / incoming;
            double roundoff = 64 * Math.ulp(scale);
            for (var arm : junction.arms) if (touches(arm, contents)
                && pressure[junction.axisNodes[arm.direction / 2]] - kineticHead > roundoff) {
                modes[j] = CONTACT; changed = true; break;
            }
        }
        return changed ? layout(modes, current.detached) : current;
    }

    /** A gap cannot feed empty space into a wetted pressure junction. Release
     * that mouth to atmospheric pressure until its real liquid front arrives;
     * keep any liquid farther along the arm moving independently. */
    public Layout releaseDryInlets(Layout current, PipeContents[] contents, double[] flow) {
        int[] detached = current.detached.clone(); boolean changed = false;
        for (int j = 0; j < detached.length; j++) if (current.modes[j] == CONTACT) {
            var junction = junctions.get(j);
            double scale = 0;
            for (var arm : junction.arms) scale = Math.max(scale, Math.abs(flow[arm.edge]));
            for (var arm : junction.arms) if (!touches(arm, contents) && (detached[j] & (1 << arm.direction)) == 0) {
                double outward = arm.start ? flow[arm.edge] : -flow[arm.edge];
                if (outward < -64 * Math.ulp(scale)) {
                    detached[j] |= 1 << arm.direction; changed = true;
                }
            }
        }
        return changed ? layout(current.modes, detached) : current;
    }

    private static boolean touches(Arm arm, PipeContents[] contents) {
        return arm.start ? contents[arm.edge].atStart() : contents[arm.edge].atEnd();
    }

    private Layout layout(byte[] modes, int[] detached) {
        String key = Arrays.toString(modes) + Arrays.toString(detached);
        var cached = layouts.get(key); if (cached != null) return cached;
        int[] from = new int[originalEdges.size()], to = new int[from.length];
        boolean[] free = new boolean[nodes];
        for (int e = 0; e < from.length; e++) { from[e] = originalEdges.get(e).from(); to[e] = originalEdges.get(e).to(); }
        for (int j = 0; j < modes.length; j++) {
            var junction = junctions.get(j);
            int[] degree = new int[3];
            for (int node : junction.portNodes) if (node >= 0) free[node] = true;
            for (var arm : junction.arms) {
                int axis = arm.direction / 2; degree[axis]++;
                int node = (detached[j] & (1 << arm.direction)) != 0 ? junction.portNodes[arm.direction]
                    : modes[j] == CONTACT ? junction.node : modes[j] == FREE ? junction.portNodes[arm.direction] : junction.axisNodes[axis];
                if (arm.start) from[arm.edge] = node; else to[arm.edge] = node;
            }
            if (modes[j] == CONTACT) free[junction.node] = false;
            else if (modes[j] == LANES) for (int axis = 0; axis < 3; axis++) if (junction.axisNodes[axis] >= 0)
                free[junction.axisNodes[axis]] = degree[axis] == 1;
        }
        var edges = new ArrayList<IdealFluidSolver.Edge>(from.length);
        var tubes = new ArrayList<PipeFilling.Tube>(from.length);
        for (int e = 0; e < from.length; e++) {
            edges.add(new IdealFluidSolver.Edge(from[e], to[e], originalEdges.get(e).inertia()));
            var tube = originalTubes.get(e);
            tubes.add(new PipeFilling.Tube(from[e], to[e], tube.length(), tube.fromHeight(), tube.toHeight()));
        }
        var result = new Layout(modes, detached, free, List.copyOf(edges), List.copyOf(tubes));
        // Contact changes can have many combinations in a large network. Keep
        // geometry caches bounded; eviction never discards physical state.
        if (layouts.size() >= 16) layouts.remove(layouts.keySet().iterator().next());
        layouts.put(key, result);
        return result;
    }

    public final class Layout {
        private final byte[] modes;
        private final int[] detached;
        private final boolean[] free;
        private final List<IdealFluidSolver.Edge> edges;
        private final List<PipeFilling.Tube> tubes;
        private ReducedFluidGraph reduced;
        private Layout(byte[] modes, int[] detached, boolean[] free, List<IdealFluidSolver.Edge> edges, List<PipeFilling.Tube> tubes) {
            this.modes = modes; this.detached = detached; this.free = free; this.edges = edges; this.tubes = tubes;
        }
        public List<PipeFilling.Tube> tubes() { return tubes; }
        public int joined() { int count = 0; for (byte value : modes) if (value == CONTACT) count++; return count; }
        public boolean[] boundary(boolean[] original) {
            boolean[] result = Arrays.copyOf(original, nodes);
            for (int n = 0; n < nodes; n++) result[n] |= free[n];
            return result;
        }
        public ReducedFluidGraph graph(boolean[] originalBoundary, boolean[] enabled) {
            boolean[] actual = boundary(originalBoundary);
            if (reduced == null || !reduced.matches(actual, enabled)) reduced = new ReducedFluidGraph(nodes, edges, actual, enabled);
            return reduced;
        }
    }
}
