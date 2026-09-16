package com.diliupengyun.differential_create.kinetics;

import java.util.List;

/**
 * Finite-stiffness rotational graph. Angles are calibrated turns and time is minutes.
 * A link measures q*w[a]-w[b] on its output shaft and exerts (-q*T,+T).
 * Inertialess transmission nodes satisfy torque balance; no artificial rotor mass is added.
 * The implicit midpoint rule preserves quadratic kinetic/elastic energy in the undamped
 * problem. Damping and friction are passive. Trial integration never mutates its inputs.
 */
public final class TorsionSolver {

    public record Body(double inertia, double drive, double damping, double load, double drag) {}
    public record Link(int a, int b, double ratio, double stiffness, double damping,
                       double strength, double friction) {}
    public record Result(double duration, double[] speed, double[] twist, double[] angle,
                         double[] travel, double[] torque, boolean[] slipping, int broken) {}
    private record Step(double[] speed, double[] twist, double[] velocity, double[] torque,
                        boolean[] slipping, int broken) {}
    private TorsionSolver() {}

    public static Result advance(List<Body> bodies, List<Link> links, double[] initialSpeed,
                                 double[] initialTwist, double duration, double maxStep) {
        if (!(maxStep > 0) || duration < 0) throw new IllegalArgumentException("Invalid physical interval");
        int n = bodies.size(), m = links.size();
        double[] speed = initialSpeed.clone(), twist = initialTwist.clone();
        double[] angle = new double[n], travel = new double[n], torque = new double[m];
        boolean[] slipping = new boolean[m];
        if (m == 0) {
            for (int i = 0; i < n; i++) {
                Body b = bodies.get(i);
                var motion = RotationMath.motion(speed[i], b.drive, b.damping, b.load, b.inertia, b.drag, duration);
                speed[i] = motion.speed(); angle[i] = motion.angle(); travel[i] = motion.travel();
            }
            return new Result(duration, speed, twist, angle, travel, torque, slipping, -1);
        }
        double elapsed = 0;
        while (elapsed < duration) {
            double h = Math.min(maxStep, duration - elapsed);
            Step next = step(bodies, links, speed, twist, h);
            // Locate the first strength crossing before committing velocity or removing a link.
            if (next.broken >= 0) {
                double low = 0, high = h;
                for (int k = 0; k < 26 && high - low > 1e-12; k++) {
                    double mid = (low + high) / 2;
                    Step trial = step(bodies, links, speed, twist, mid);
                    if (trial.broken < 0) low = mid;
                    else { high = mid; next = trial; }
                }
                h = high;
                next = step(bodies, links, speed, twist, h);
            }
            for (int i = 0; i < n; i++) {
                angle[i] += h * next.velocity[i];
                // Midpoint travel is exact while direction is constant; split a reversal's triangle.
                if (speed[i] * next.speed[i] < 0) {
                    double a = Math.abs(speed[i]), b = Math.abs(next.speed[i]);
                    travel[i] += h * (a * a + b * b) / (2 * (a + b));
                } else travel[i] += h * Math.abs(next.velocity[i]);
            }
            elapsed += h;
            speed = next.speed; twist = next.twist; torque = next.torque; slipping = next.slipping;
            if (next.broken >= 0)
                return new Result(elapsed, speed, twist, angle, travel, torque, slipping, next.broken);
        }
        return new Result(elapsed, speed, twist, angle, travel, torque, slipping, -1);
    }

    private static Step step(List<Body> bodies, List<Link> links, double[] previous,
                             double[] twist, double h) {
        int n = bodies.size(), m = links.size();
        double[] v = previous.clone(), coefficients = new double[m], constants = new double[m];
        double[] signs = new double[n], fixed = new double[n];
        boolean[] sticking = new boolean[n], limited = new boolean[m];
        for (int i = 0; i < n; i++) {
            Body b = bodies.get(i);
            signs[i] = Math.signum(previous[i] == 0 ? b.drive : previous[i]);
            sticking[i] = b.load > 0 && previous[i] == 0 && Math.abs(b.drive) <= b.load;
        }
        for (int iteration = 0; iteration < 80; iteration++) {
            double[] diag = new double[n], rhs = new double[n];
            for (int i = 0; i < n; i++) {
                Body b = bodies.get(i);
                diag[i] = 2 * b.inertia / h + b.damping + 2 * b.drag * Math.abs(v[i]);
                rhs[i] = 2 * b.inertia / h * previous[i] + b.drive - b.load * signs[i]
                    + b.drag * v[i] * Math.abs(v[i]);
                fixed[i] = b.inertia > 0 ? previous[i] / 2 : 0;
            }
            for (int e = 0; e < m; e++) {
                Link link = links.get(e);
                double slope = link.damping + h * link.stiffness / 2;
                double raw = link.stiffness * twist[e] + slope * (link.ratio * v[link.a] - v[link.b]);
                coefficients[e] = limited[e] ? 0 : slope;
                constants[e] = limited[e] ? Math.copySign(link.friction, raw) : link.stiffness * twist[e];
                if (link.friction == 0) { coefficients[e] = 0; constants[e] = 0; }
                rhs[link.a] -= link.ratio * constants[e];
                rhs[link.b] += constants[e];
            }
            double[] next = solve(links, coefficients, diag, rhs, sticking, fixed, v);
            boolean changed = false;
            double maxChange = 0;
            double[] forces = new double[n];
            for (int e = 0; e < m; e++) {
                Link link = links.get(e);
                double raw = link.stiffness * twist[e] + (link.damping + h * link.stiffness / 2)
                    * (link.ratio * next[link.a] - next[link.b]);
                boolean cap = Math.abs(raw) > link.friction + 1e-8;
                if (cap != limited[e]) { limited[e] = cap; changed = true; }
                double t = Math.clamp(raw, -link.friction, link.friction);
                forces[link.a] -= link.ratio * t; forces[link.b] += t;
            }
            for (int i = 0; i < n; i++) {
                Body b = bodies.get(i);
                maxChange = Math.max(maxChange, Math.abs(next[i] - v[i]) / (1 + Math.abs(next[i])));
                if (b.load == 0) continue;
                double reaction = b.drive - b.damping * next[i] - b.drag * next[i] * Math.abs(next[i])
                    + forces[i] - 2 * b.inertia / h * (next[i] - previous[i]);
                if (sticking[i]) {
                    if (Math.abs(reaction) > b.load + 1e-7) {
                        sticking[i] = false; signs[i] = Math.signum(reaction); changed = true;
                    }
                } else {
                    double end = b.inertia > 0 ? 2 * next[i] - previous[i] : next[i];
                    if (end * signs[i] < -1e-9 || signs[i] == 0) {
                        sticking[i] = true; changed = true;
                    }
                }
            }
            v = next;
            if (!changed && maxChange < 1e-9) break;
            if (iteration == 79) throw new IllegalStateException("Torsion active set did not converge");
        }
        double[] speed = new double[n], nextTwist = new double[m], torque = new double[m];
        int broken = -1;
        for (int i = 0; i < n; i++) {
            speed[i] = bodies.get(i).inertia > 0 ? 2 * v[i] - previous[i] : v[i];
            if (sticking[i]) speed[i] = 0;
            if (Math.abs(speed[i]) < 1e-9) speed[i] = 0;
            if (!Double.isFinite(speed[i])) throw new IllegalStateException("Non-finite torsional speed");
        }
        for (int e = 0; e < m; e++) {
            Link link = links.get(e);
            double delta = link.ratio * v[link.a] - v[link.b];
            double slope = link.damping + h * link.stiffness / 2;
            double raw = link.stiffness * twist[e] + slope * delta;
            torque[e] = Math.clamp(raw, -link.friction, link.friction);
            limited[e] = Math.abs(raw) > link.friction + 1e-8;
            // Return mapping: slip is in series with the elastic/damped contact. No spring wind-up.
            double elasticRate = limited[e] ? (torque[e] - link.stiffness * twist[e]) / slope : delta;
            nextTwist[e] = link.friction == 0 ? 0 : twist[e] + h * elasticRate;
            double peak = limited[e] ? Math.abs(torque[e]) : Math.max(Math.abs(torque[e]),
                Math.abs(link.stiffness * nextTwist[e] + link.damping * elasticRate));
            if (peak >= link.strength && broken < 0) broken = e;
        }
        return new Step(speed, nextTwist, v, torque, limited, broken);
    }

    /** Sparse preconditioned conjugate gradients, including exact pinned static-friction nodes. */
    private static double[] solve(List<Link> links, double[] weights, double[] base, double[] rhs,
                                  boolean[] fixed, double[] values, double[] guess) {
        int n = base.length;
        double[] diagonal = base.clone(), b = rhs.clone(), x = guess.clone();
        for (int e = 0; e < links.size(); e++) {
            Link l = links.get(e); double w = weights[e];
            diagonal[l.a] += w * l.ratio * l.ratio; diagonal[l.b] += w;
            if (fixed[l.a] && !fixed[l.b]) b[l.b] += w * l.ratio * values[l.a];
            if (fixed[l.b] && !fixed[l.a]) b[l.a] += w * l.ratio * values[l.b];
        }
        for (int i = 0; i < n; i++) {
            if (fixed[i]) { b[i] = values[i]; x[i] = values[i]; diagonal[i] = 1; }
            // An isolated unloaded massless port has a free speed, not an invented inertia.
            else if (diagonal[i] == 0) { b[i] = guess[i]; diagonal[i] = 1; base[i] = 1; }
        }
        double[] ax = multiply(links, weights, base, fixed, x);
        double[] r = new double[n], z = new double[n], p = new double[n];
        double rz = 0, scale = 1;
        for (int i = 0; i < n; i++) {
            r[i] = b[i] - ax[i]; z[i] = r[i] / diagonal[i]; p[i] = z[i];
            rz += r[i] * z[i]; scale += b[i] * b[i] / diagonal[i];
        }
        for (int k = 0; k < Math.max(32, n * 3) && rz > 1e-24 * scale; k++) {
            double[] ap = multiply(links, weights, base, fixed, p);
            double denominator = dot(p, ap);
            if (denominator <= 1e-30) break; // consistent free rotation in a massless component
            double alpha = rz / denominator;
            for (int i = 0; i < n; i++) { x[i] += alpha * p[i]; r[i] -= alpha * ap[i]; z[i] = r[i] / diagonal[i]; }
            double nextRz = dot(r, z), beta = nextRz / rz;
            for (int i = 0; i < n; i++) p[i] = z[i] + beta * p[i];
            rz = nextRz;
        }
        return x;
    }

    private static double[] multiply(List<Link> links, double[] weights, double[] base, boolean[] fixed, double[] x) {
        double[] result = new double[x.length];
        for (int i = 0; i < x.length; i++) result[i] = fixed[i] ? x[i] : base[i] * x[i];
        for (int e = 0; e < links.size(); e++) {
            Link l = links.get(e);
            double force = weights[e] * (l.ratio * (fixed[l.a] ? 0 : x[l.a]) - (fixed[l.b] ? 0 : x[l.b]));
            if (!fixed[l.a]) result[l.a] += l.ratio * force;
            if (!fixed[l.b]) result[l.b] -= force;
        }
        return result;
    }
    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }
}
