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
    public record Prediction(double[] waterOutward, double[] inertia, double[] correction, double allowedMinutes) {}

    public static Move sample(List<Tube> tubes, int nodes, PipeContents[] contents, double[] origin,
                              double[] midpoint, boolean[] inventory, double[] supplied,
                              IdealFluidSolver.Material material, double minutes) {
        return sample(tubes, nodes, contents, origin, midpoint, inventory, supplied, material, minutes, true);
    }

    /** Detached coefficients for the requested horizon, even when its provisional
     * trajectory crosses a face. The caller must resolve allowedMinutes before commit. */
    public static Prediction predict(List<Tube> tubes, int nodes, PipeContents[] contents, double[] origin,
                                     double[] midpoint, boolean[] inventory, double[] supplied,
                                     IdealFluidSolver.Material material, double minutes) {
        var result = sample(tubes, nodes, contents, origin, midpoint, inventory, supplied, material, minutes, false);
        return new Prediction(result.waterOutward, result.inertia, result.correction, result.allowedMinutes);
    }

    private static Move sample(List<Tube> tubes, int nodes, PipeContents[] contents, double[] origin,
                               double[] midpoint, boolean[] inventory, double[] supplied,
                               IdealFluidSolver.Material material, double minutes, boolean truncate) {
        double[] inward = new double[nodes], water = new double[nodes];
        double limit = minutes;
        for (int e = 0; e < tubes.size(); e++) {
            Tube tube = tubes.get(e); double q = midpoint[e];
            if (q != 0) limit = Math.min(limit, mouthTime(contents[e].travelToMouth(q)
                * tube.length * MB_PER_METRE * RotationMath.TICK_MINUTES, origin[e], q, minutes));
        }
        // Numerical face events can coalesce at machine precision. Integrating the
        // actual outgoing interval keeps their tiny inventory exact, in either direction.
        double dt = truncate ? Math.min(minutes, limit) : minutes;
        double[] transport = midpoint;
        if (dt < minutes) {
            transport = new double[midpoint.length];
            for (int e = 0; e < transport.length; e++) transport[e] = origin[e] + (midpoint[e] - origin[e]) * (dt / minutes);
        }
        double[] exiting = new double[tubes.size()];
        for (int e = 0; e < tubes.size(); e++) {
            Tube tube = tubes.get(e); double q = transport[e];
            double displacement = q * dt / (RotationMath.TICK_MINUTES * tube.length * MB_PER_METRE);
            var old = contents[e];
            // A subnormal iterative flow can underflow to zero displacement.
            // Use the mouth's limiting phase instead of dividing zero by zero;
            // retain the flow itself and impose no minimum physical rate.
            exiting[e] = displacement != 0 ? old.exiting(displacement) / Math.abs(displacement)
                : q != 0 && old.intervals() != 0 && (q > 0 ? old.end(old.intervals() - 1) == 1 : old.start(0) == 0) ? 1 : 0;
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
            Tube tube = tubes.get(e); PipeContents old = contents[e]; double q = transport[e];
            double capacity = tube.length * MB_PER_METRE;
            double displacement = q * dt / (RotationMath.TICK_MINUTES * capacity);
            double inlet = fraction[q > 0 ? tube.from : tube.to];
            next[e] = truncate ? old.move(displacement, inlet) : old;
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

    /** Locate a front along the detached step's linear velocity trajectory.
     * Scaling a full-step midpoint as though it were the initial velocity makes
     * an accelerating near-rest column repeatedly accept almost zero time. */
    private static double mouthTime(double distance, double initial, double midpoint, double minutes) {
        double speed = Math.abs(midpoint);
        if (speed * minutes <= distance) return minutes;
        double first = Math.copySign(1, midpoint) * initial;
        double acceleration = (speed - first) / minutes;
        if (acceleration == 0) return distance / speed;
        double root = Math.sqrt(Math.max(0, first * first + 4 * acceleration * distance));
        return first >= 0 ? 2 * distance / (first + root) : (root - first) / (2 * acceleration);
    }
}
