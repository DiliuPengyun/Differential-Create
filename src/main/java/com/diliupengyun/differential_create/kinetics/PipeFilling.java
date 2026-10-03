package com.diliupengyun.differential_create.kinetics;

import java.util.List;

/** Inventory-conserving one-dimensional advection. A fixed connection graph transports
 * liquid and displaces unmodelled empty space; only liquid contributes inertia and weight. */
public final class PipeFilling {
    public static final double MB_PER_METRE = IdealFluidSolver.AREA / IdealFluidSolver.MB_VOLUME;
    private PipeFilling() {}
    public record Tube(int from, int to, double length, double fromHeight, double toHeight) {}
    public record Move(PipeContents[] contents, double[] waterOutward, double[] inertia,
                       double[] correction, double allowedMinutes) {}

    public static Move sample(List<Tube> tubes, int nodes, PipeContents[] contents, double[] origin,
                              double[] midpoint, boolean[] inventory, double[] supplied,
                              IdealFluidSolver.Material material, double minutes) {
        double[] inward = new double[nodes], water = new double[nodes];
        double limit = minutes;
        for (int e = 0; e < tubes.size(); e++) {
            Tube tube = tubes.get(e); double q = midpoint[e];
            if (q != 0) limit = Math.min(limit, contents[e].travelToMouth(q)
                * tube.length * MB_PER_METRE * RotationMath.TICK_MINUTES / Math.abs(q));
        }
        // Numerical face events can coalesce at machine precision. Integrating the
        // actual outgoing interval keeps their tiny inventory exact, in either direction.
        double dt = Math.min(minutes, limit);
        double[] exiting = new double[tubes.size()];
        for (int e = 0; e < tubes.size(); e++) {
            Tube tube = tubes.get(e); double q = midpoint[e];
            double displacement = q * dt / (RotationMath.TICK_MINUTES * tube.length * MB_PER_METRE);
            exiting[e] = q == 0 ? 0 : contents[e].exiting(displacement) / Math.abs(displacement);
            int destination = q > 0 ? tube.to : tube.from;
            inward[destination] += Math.abs(q); water[destination] += Math.abs(q) * exiting[e];
        }
        double[] fraction = new double[nodes];
        for (int node = 0; node < nodes; node++) fraction[node] = inventory[node] ? supplied[node]
            : inward[node] == 0 ? 0 : Math.min(1, water[node] / inward[node]);
        var next = new PipeContents[tubes.size()];
        double[] mass = new double[tubes.size()], correction = new double[tubes.size()], outward = new double[nodes];
        // The caller shortens the common mechanical/fluid interval before committing.
        for (int e = 0; e < tubes.size(); e++) {
            Tube tube = tubes.get(e); PipeContents old = contents[e]; double q = midpoint[e];
            double capacity = tube.length * MB_PER_METRE;
            double displacement = q * dt / (RotationMath.TICK_MINUTES * capacity);
            double inlet = fraction[q > 0 ? tube.from : tube.to];
            next[e] = old.move(displacement, inlet);
            double waterFrom = q > 0 ? q * inlet : q * exiting[e];
            double waterTo = q > 0 ? q * exiting[e] : q * inlet;
            outward[tube.from] += waterFrom; outward[tube.to] -= waterTo;
            double fullMass = material.inertia(tube.length);
            double outlet = exiting[e];
            double admitted = Math.abs(displacement) * (inlet - outlet);
            mass[e] = fullMass * Math.max(0, old.fraction() + .5 * admitted);
            if (q == 0) {
                correction[e] = -material.gravityForce(tube.toHeight - tube.fromHeight) * old.fraction();
                continue;
            }
            double nextFlow = 2 * q - origin[e];
            // Analytically cancel the transported endpoint potential and mass flux.
            // Subtracting nearly equal stored energies and dividing by a tiny flow
            // would manufacture a noisy force as a supported column approaches rest.
            double gravityFraction = old.gravityFraction(displacement, inlet);
            correction[e] = -material.gravityForce(tube.toHeight - tube.fromHeight) * gravityFraction
                - .25 * fullMass * Math.signum(q) * (inlet - outlet) / (RotationMath.TICK_MINUTES * capacity)
                    * (nextFlow * nextFlow + origin[e] * origin[e]);
        }
        return new Move(next, outward, mass, correction, limit);
    }
}
