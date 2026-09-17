package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;

/**
 * Finite-stiffness rotational graph. Angles are calibrated turns and time is minutes.
 * A link measures q*w[a]-w[b] on its output shaft and exerts (-q*T,+T).
 * Inertialess transmission nodes satisfy torque balance; no artificial rotor mass is added.
 * The implicit midpoint rule preserves quadratic kinetic/elastic energy in the undamped
 * problem. Damping and friction are passive. Trial integration never mutates its inputs.
 */
public final class TorsionSolver {

    public record Body(double inertia, double drive, double damping, double load, double drag, double powerLimit) {
        public Body(double inertia, double drive, double damping, double load, double drag) {
            this(inertia, drive, damping, load, drag, Double.POSITIVE_INFINITY);
        }
        double driveAt(double speed) { return DriveTorque.torque(drive, powerLimit, speed); }
    }
    public record Link(int a, int b, double ratio, double stiffness, double damping,
                       double strength, double friction) {}
    public record Result(double duration, double[] speed, double[] twist, double[] angle,
                         double[] travel, double[] torque, boolean[] slipping, int broken) {}
    private record Step(double[] speed, double[] twist, double[] velocity, double[] torque,
                        boolean[] slipping, int broken) {}
    private static final class ConvergenceFailure extends RuntimeException {
        ConvergenceFailure(String message) { super(message); }
    }
    private TorsionSolver() {}

    public static Result advance(List<Body> bodies, List<Link> links, double[] initialSpeed,
                                 double[] initialTwist, double duration, double maxStep) {
        if (!(maxStep > 0) || duration < 0) throw new IllegalArgumentException("Invalid physical interval");
        if (duration == 0) return new Result(0, initialSpeed.clone(), initialTwist.clone(),
            new double[bodies.size()], new double[bodies.size()], new double[links.size()], new boolean[links.size()], -1);
        var components = components(bodies, links, initialSpeed, initialTwist);
        if (components.size() <= 1)
            return integrate(bodies, links, initialSpeed, initialTwist, duration, maxStep, true);

        // Disconnected rotors must not change each other's convergence or analytic integration.
        var results = new ArrayList<Result>();
        double boundary = duration;
        for (var component : components) {
            var result = component.advance(duration, maxStep, true);
            results.add(result);
            boundary = Math.min(boundary, result.duration);
        }
        double[] speed = initialSpeed.clone(), twist = new double[links.size()];
        double[] angle = new double[bodies.size()], travel = new double[bodies.size()], torque = new double[links.size()];
        boolean[] slipping = new boolean[links.size()];
        int broken = -1;
        for (int c = 0; c < components.size(); c++) {
            Component component = components.get(c);
            Result result = results.get(c);
            // All components commit at the earliest fracture. That boundary is already located;
            // do not independently bisect it again and give other components different clocks.
            if (result.duration > boundary) result = component.advance(boundary, maxStep, false);
            for (int i = 0; i < component.nodes.size(); i++) {
                int target = component.nodes.get(i);
                speed[target] = result.speed[i]; angle[target] = result.angle[i]; travel[target] = result.travel[i];
            }
            for (int e = 0; e < component.edges.size(); e++) {
                int target = component.edges.get(e);
                twist[target] = result.twist[e]; torque[target] = result.torque[e]; slipping[target] = result.slipping[e];
                if (e == result.broken && (broken < 0 || target < broken)) broken = target;
            }
        }
        return new Result(boundary, speed, twist, angle, travel, torque, slipping, broken);
    }

    private static final class Component {
        final List<Integer> nodes = new ArrayList<>(), edges = new ArrayList<>();
        final List<Body> bodies = new ArrayList<>();
        final List<Link> links = new ArrayList<>();
        double[] speed, twist;
        Result advance(double duration, double maxStep, boolean locateBreak) {
            return integrate(bodies, links, speed, twist, duration, maxStep, locateBreak);
        }
    }

    private static List<Component> components(List<Body> bodies, List<Link> links, double[] speed, double[] twist) {
        int n = bodies.size();
        int[] parent = new int[n], group = new int[n], local = new int[n];
        for (int i = 0; i < n; i++) { parent[i] = i; group[i] = -1; }
        for (Link link : links)
            if (link.friction > 0) parent[root(parent, link.a)] = root(parent, link.b);
        boolean connected = true;
        for (int i = 1; i < n; i++) if (root(parent, i) != root(parent, 0)) { connected = false; break; }
        if (connected) return List.of(); // Avoid duplicating the common single-component graph.
        var result = new ArrayList<Component>();
        for (int i = 0; i < n; i++) {
            int root = root(parent, i);
            if (group[root] < 0) { group[root] = result.size(); result.add(new Component()); }
            Component component = result.get(group[root]);
            local[i] = component.nodes.size(); component.nodes.add(i); component.bodies.add(bodies.get(i));
        }
        for (int e = 0; e < links.size(); e++) {
            Link link = links.get(e);
            if (link.friction == 0) continue; // An open contact has no torque or stored twist.
            Component component = result.get(group[root(parent, link.a)]);
            component.edges.add(e);
            component.links.add(new Link(local[link.a], local[link.b], link.ratio, link.stiffness,
                link.damping, link.strength, link.friction));
        }
        for (Component component : result) {
            component.speed = new double[component.nodes.size()]; component.twist = new double[component.edges.size()];
            for (int i = 0; i < component.speed.length; i++) component.speed[i] = speed[component.nodes.get(i)];
            for (int e = 0; e < component.twist.length; e++) component.twist[e] = twist[component.edges.get(e)];
        }
        return result;
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    /** Passive massless speeds have no memory in the equations. Use a compatible nearby
     * shaft as their numerical origin, avoiding cancellation of post-fracture speeds. */
    private record SpeedFrame(int[] roots, double[] ratios) {
        double[] origins(double[] previous) {
            double[] result = new double[previous.length];
            for (int i = 0; i < result.length; i++) result[i] = ratios[i] * previous[roots[i]];
            return result;
        }
    }

    private static SpeedFrame speedFrame(List<Body> bodies, List<Link> links) {
        int n = bodies.size();
        boolean[] anchors = new boolean[n];
        for (int i = 0; i < n; i++) anchors[i] = !passiveMassless(bodies.get(i));
        return speedFrame(n, links, anchors, null);
    }

    private static boolean passiveMassless(Body body) {
        return body.inertia == 0 && body.drive == 0 && body.damping == 0 && body.load == 0 && body.drag == 0;
    }

    private static SpeedFrame speedFrame(int n, List<Link> links, boolean[] anchors, double[] weights) {
        int[] roots = new int[n], queue = new int[n];
        double[] ratios = new double[n];
        java.util.Arrays.fill(roots, -1);
        var adjacent = new ArrayList<List<Link>>();
        for (int i = 0; i < n; i++) adjacent.add(new ArrayList<>());
        for (int e = 0; e < links.size(); e++) {
            Link link = links.get(e);
            if (link.friction > 0 && (weights == null || weights[e] > 0)) {
                adjacent.get(link.a).add(link); adjacent.get(link.b).add(link);
            }
        }
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) {
            if (anchors[i]) {
                roots[i] = i; ratios[i] = 1; queue[tail++] = i;
            }
        }
        for (int seed = 0; seed < n; seed++) {
            if (head == tail && roots[seed] < 0) {
                roots[seed] = seed; ratios[seed] = 1; queue[tail++] = seed;
            }
            while (head < tail) {
                int i = queue[head++];
                for (Link link : adjacent.get(i)) {
                    int j = link.a == i ? link.b : link.a;
                    if (roots[j] >= 0) continue;
                    roots[j] = roots[i]; ratios[j] = ratios[i] * (link.a == i ? link.ratio : 1 / link.ratio);
                    queue[tail++] = j;
                }
            }
        }
        return new SpeedFrame(roots, ratios);
    }

    /** Recover endpoint velocities of undamped, passive massless nodes from the time
     * derivative of torque balance: B_m K B^T w = 0. Their midpoint solve can contain
     * a sign-alternating constraint residual, which is not a rotor velocity. An even
     * number of substeps aliases that residual into a steady displayed speed offset.
     * Keep inertial velocities, angle/work integration and elastic energy unchanged. */
    private static double[] endpointSpeeds(List<Body> bodies, List<Link> links, double[] speed, boolean[] slipping) {
        int n = bodies.size();
        boolean[] fixed = new boolean[n];
        for (int i = 0; i < n; i++) fixed[i] = !passiveMassless(bodies.get(i));
        double[] weights = new double[links.size()];
        for (int e = 0; e < links.size(); e++) {
            Link link = links.get(e);
            // Sliding friction has constant torque; open/sliding contacts impose no velocity relation.
            weights[e] = link.friction > 0 && !slipping[e] ? link.stiffness : 0;
            // A dashpot's torque balance depends on velocity itself, not just elastic position.
            if (link.friction > 0 && link.damping != 0) fixed[link.a] = fixed[link.b] = true;
        }
        boolean any = false;
        for (boolean value : fixed) any |= !value;
        if (!any) return speed;
        SpeedFrame frame = speedFrame(n, links, fixed, weights);
        boolean[] anchored = fixed.clone();
        any = false;
        for (int i = 0; i < n; i++) {
            // A massless component with no dynamic boundary has no unique physical speed.
            if (!anchored[frame.roots[i]]) fixed[i] = true;
            any |= !fixed[i];
        }
        if (!any) return speed;
        double[] origin = frame.origins(speed), rhs = new double[n], rhsMagnitude = new double[n];
        for (int i = 0; i < n; i++) if (fixed[i]) origin[i] = speed[i];
        for (int e = 0; e < links.size(); e++) {
            Link link = links.get(e);
            double force = weights[e] * (link.ratio * origin[link.a] - origin[link.b]);
            rhs[link.a] -= link.ratio * force; rhs[link.b] += force;
            double magnitude = weights[e] * (Math.abs(link.ratio * origin[link.a]) + Math.abs(origin[link.b]));
            rhsMagnitude[link.a] += Math.abs(link.ratio) * magnitude; rhsMagnitude[link.b] += magnitude;
        }
        double[] correction = solve(links, weights, new double[n], rhs, rhsMagnitude, fixed, new double[n], new double[n]);
        double[] result = speed.clone();
        for (int i = 0; i < n; i++) if (!fixed[i]) result[i] = origin[i] + correction[i];
        return result;
    }

    private static Result integrate(List<Body> bodies, List<Link> links, double[] initialSpeed,
                                     double[] initialTwist, double duration, double maxStep, boolean locateBreak) {
        int n = bodies.size(), m = links.size();
        double[] speed = initialSpeed.clone(), twist = initialTwist.clone();
        double[] angle = new double[n], travel = new double[n], torque = new double[m];
        boolean[] slipping = new boolean[m];
        if (m == 0 && bodies.stream().noneMatch(b -> Double.isFinite(b.powerLimit))) {
            for (int i = 0; i < n; i++) {
                Body b = bodies.get(i);
                var motion = RotationMath.motion(speed[i], b.drive, b.damping, b.load, b.inertia, b.drag, duration);
                speed[i] = motion.speed(); angle[i] = motion.angle(); travel[i] = motion.travel();
            }
            return new Result(duration, speed, twist, angle, travel, torque, slipping, -1);
        }
        double elapsed = 0, timeCorrection = 0;
        int broken = -1;
        SpeedFrame frame = speedFrame(bodies, links);
        while (elapsed < duration) {
            double remaining = duration - elapsed;
            // Fold a rounding-sized remainder into the final real step. An extra near-zero
            // step turns harmless massless force residuals into enormous algebraic speeds.
            double h = remaining <= maxStep + 8 * Math.ulp(duration) ? remaining : maxStep;
            double[] origin = frame.origins(speed);
            Step next;
            for (int retry = 0; ; retry++) {
                try { next = step(bodies, links, speed, origin, twist, h); break; }
                catch (ConvergenceFailure failure) {
                    if (retry == 12 || h / 2 <= Math.ulp(elapsed)) throw failure;
                    // Refine this interval only. A transient failure must not ratchet
                    // every remaining step down to an ever smaller time scale.
                    h /= 2;
                }
            }
            // Locate the first strength crossing before committing velocity or removing a link.
            if (locateBreak && next.broken >= 0) {
                double low = 0, high = h;
                for (int k = 0; k < 26 && high - low > 1e-12; k++) {
                    double mid = (low + high) / 2;
                    Step trial = step(bodies, links, speed, origin, twist, mid);
                    if (trial.broken < 0) low = mid;
                    else { high = mid; next = trial; }
                }
                h = high;
                next = step(bodies, links, speed, origin, twist, h);
            }
            for (int i = 0; i < n; i++) {
                angle[i] += h * next.velocity[i];
                // Midpoint travel is exact while direction is constant; split a reversal's triangle.
                if (speed[i] * next.speed[i] < 0) {
                    double a = Math.abs(speed[i]), b = Math.abs(next.speed[i]);
                    travel[i] += h * (a * a + b * b) / (2 * (a + b));
                } else travel[i] += h * Math.abs(next.velocity[i]);
            }
            double timeIncrement = h - timeCorrection, nextTime = elapsed + timeIncrement;
            timeCorrection = (nextTime - elapsed) - timeIncrement;
            elapsed = h == remaining ? duration : nextTime;
            speed = next.speed; twist = next.twist; torque = next.torque; slipping = next.slipping;
            if (next.broken >= 0 && (broken < 0 || next.broken < broken)) broken = next.broken;
            if (locateBreak && next.broken >= 0)
                return new Result(elapsed, endpointSpeeds(bodies, links, speed, slipping), twist, angle, travel,
                    torque, slipping, next.broken);
        }
        return new Result(elapsed, endpointSpeeds(bodies, links, speed, slipping), twist, angle, travel,
            torque, slipping, broken);
    }

    private static Step step(List<Body> bodies, List<Link> links, double[] previous, double[] origin,
                             double[] twist, double h) {
        int n = bodies.size(), m = links.size();
        double[] increment = new double[n], v = origin.clone(), coefficients = new double[m], constants = new double[m];
        double[] relative = new double[m];
        for (int e = 0; e < m; e++) {
            Link link = links.get(e);
            relative[e] = link.ratio * origin[link.a] - origin[link.b];
        }
        double[] signs = new double[n], fixed = new double[n];
        boolean[] sticking = new boolean[n], limited = new boolean[m];
        boolean linear = true;
        for (int i = 0; i < n; i++) {
            Body b = bodies.get(i);
            if (b.drag != 0 || (b.drive != 0 && Double.isFinite(b.powerLimit))) linear = false;
            signs[i] = Math.signum(previous[i] == 0 ? b.drive : previous[i]);
            sticking[i] = b.load > 0 && previous[i] == 0 && Math.abs(b.drive) <= b.load;
        }
        for (int iteration = 0; iteration < 80; iteration++) {
            double[] diag = new double[n], rhs = new double[n], rhsMagnitude = new double[n];
            for (int i = 0; i < n; i++) {
                Body b = bodies.get(i);
                double sourceSlope = DriveTorque.slope(b.drive, b.powerLimit, v[i]);
                diag[i] = 2 * b.inertia / h + b.damping + sourceSlope + 2 * b.drag * Math.abs(v[i]);
                // Solve the midpoint increment u, v = origin + u. Cancel 2J/h*previous
                // algebraically, before rounding a huge coasting RHS against a tiny load.
                double drive = b.driveAt(v[i]), damping = b.damping * previous[i], load = b.load * signs[i];
                double tangent = sourceSlope * increment[i], drag = b.drag * Math.abs(v[i]);
                rhs[i] = drive - damping - load + tangent + drag * (increment[i] - previous[i]);
                rhsMagnitude[i] = Math.abs(drive) + Math.abs(damping) + Math.abs(load) + Math.abs(tangent)
                    + drag * (Math.abs(increment[i]) + Math.abs(previous[i]));
                fixed[i] = b.inertia > 0 ? -previous[i] / 2 : -previous[i];
            }
            for (int e = 0; e < m; e++) {
                Link link = links.get(e);
                double slope = link.damping + h * link.stiffness / 2;
                double raw = link.stiffness * twist[e] + slope * (relative[e]
                    + link.ratio * increment[link.a] - increment[link.b]);
                coefficients[e] = limited[e] ? 0 : slope;
                constants[e] = limited[e] ? Math.copySign(link.friction, raw) : link.stiffness * twist[e] + slope * relative[e];
                if (link.friction == 0) { coefficients[e] = 0; constants[e] = 0; }
                rhs[link.a] -= link.ratio * constants[e];
                rhs[link.b] += constants[e];
                double magnitude = link.friction == 0 ? 0 : limited[e] ? Math.abs(constants[e])
                    : Math.abs(link.stiffness * twist[e]) + Math.abs(slope * relative[e]);
                rhsMagnitude[link.a] += Math.abs(link.ratio) * magnitude; rhsMagnitude[link.b] += magnitude;
            }
            double[] next = solve(links, coefficients, diag, rhs, rhsMagnitude, sticking, fixed, increment);
            boolean changed = false;
            double maxChange = 0;
            double[] forces = new double[n];
            for (int e = 0; e < m; e++) {
                Link link = links.get(e);
                double raw = link.stiffness * twist[e] + (link.damping + h * link.stiffness / 2)
                    * (relative[e] + link.ratio * next[link.a] - next[link.b]);
                boolean cap = Math.abs(raw) > link.friction + 1e-8;
                // Positive and negative slip are different active branches. A Newton trial
                // can jump across both friction bounds when another clutch engages.
                // Release that bound and solve the elastic branch before choosing a new
                // slip direction; switching directly between +/- limits can cycle forever.
                if (cap && limited[e] && link.friction > 0 && Math.signum(raw) != Math.signum(constants[e])) {
                    limited[e] = false; changed = true;
                } else if (cap != limited[e]) { limited[e] = cap; changed = true; }
                double t = Math.clamp(raw, -link.friction, link.friction);
                forces[link.a] -= link.ratio * t; forces[link.b] += t;
            }
            for (int i = 0; i < n; i++) {
                Body b = bodies.get(i);
                maxChange = Math.max(maxChange, Math.abs(next[i] - increment[i]) / (1 + Math.abs(next[i])));
                double velocity = origin[i] + next[i];
                if (b.load == 0) continue;
                double reaction = b.driveAt(velocity) - b.damping * velocity - b.drag * velocity * Math.abs(velocity)
                    + forces[i] - 2 * b.inertia / h * next[i];
                if (sticking[i]) {
                    if (Math.abs(reaction) > b.load + 1e-7) {
                        sticking[i] = false; signs[i] = Math.signum(reaction); changed = true;
                    }
                } else {
                    double end = b.inertia > 0 ? previous[i] + 2 * next[i] : velocity;
                    if (end * signs[i] < -1e-9 || signs[i] == 0) {
                        sticking[i] = true; changed = true;
                    }
                }
            }
            increment = next;
            for (int i = 0; i < n; i++) v[i] = origin[i] + increment[i];
            if (!changed && (linear || maxChange < 1e-11)) break;
            if (iteration == 79) throw new ConvergenceFailure("Torsion active set did not converge");
        }
        double[] speed = new double[n], nextTwist = new double[m], torque = new double[m], elasticRates = new double[m];
        int broken = -1;
        for (int i = 0; i < n; i++) {
            speed[i] = bodies.get(i).inertia > 0 ? previous[i] + 2 * increment[i] : v[i];
            if (sticking[i]) speed[i] = 0;
            if (Math.abs(speed[i]) < 1e-9) speed[i] = 0;
            if (!Double.isFinite(speed[i])) throw new IllegalStateException("Non-finite torsional speed");
        }
        for (int e = 0; e < m; e++) {
            Link link = links.get(e);
            double delta = relative[e] + link.ratio * increment[link.a] - increment[link.b];
            double slope = link.damping + h * link.stiffness / 2;
            double raw = link.stiffness * twist[e] + slope * delta;
            torque[e] = Math.clamp(raw, -link.friction, link.friction);
            limited[e] = Math.abs(raw) > link.friction + 1e-8;
            // Return mapping: slip is in series with the elastic/damped contact. No spring wind-up.
            double elasticRate = limited[e] ? (torque[e] - link.stiffness * twist[e]) / slope : delta;
            elasticRates[e] = link.friction == 0 ? 0 : elasticRate;
            nextTwist[e] = link.friction == 0 ? 0 : twist[e] + h * elasticRate;
            double peak = limited[e] ? Math.abs(torque[e]) : Math.max(Math.abs(torque[e]),
                Math.abs(link.stiffness * nextTwist[e] + link.damping * elasticRate));
            if (peak >= link.strength && broken < 0) broken = e;
        }
        checkEnergy(bodies, links, previous, speed, twist, nextTwist, v, increment, relative,
            torque, elasticRates, sticking, signs, h);
        return new Step(speed, nextTwist, v, torque, limited, broken);
    }

    /** Check actual rounded state changes against midpoint work, including elastic storage and slip. */
    private static void checkEnergy(List<Body> bodies, List<Link> links, double[] previous, double[] speed,
                                    double[] twist, double[] nextTwist, double[] v, double[] increment,
                                    double[] relative, double[] torque, double[] rates,
                                    boolean[] sticking, double[] signs, double h) {
        double change = 0, work = 0, workScale = 0, roundoff = 0;
        double[] forces = new double[bodies.size()];
        for (int e = 0; e < links.size(); e++) {
            Link link = links.get(e);
            forces[link.a] -= link.ratio * torque[e]; forces[link.b] += torque[e];
            change += .5 * link.stiffness * (nextTwist[e] - twist[e]) * (nextTwist[e] + twist[e]);
            double delta = relative[e] + link.ratio * increment[link.a] - increment[link.b];
            double loss = link.friction == 0 ? .5 * link.stiffness * twist[e] * twist[e]
                : h * (link.damping * rates[e] * rates[e] + torque[e] * (delta - rates[e]));
            work -= loss; workScale += Math.abs(loss);
            double magnitude = Math.max(Math.abs(twist[e]), Math.abs(nextTwist[e]));
            roundoff += 8 * link.stiffness * magnitude * Math.ulp(magnitude);
        }
        for (int i = 0; i < bodies.size(); i++) {
            Body body = bodies.get(i);
            change += .5 * body.inertia * (speed[i] - previous[i]) * (speed[i] + previous[i]);
            double drive = body.driveAt(v[i]), damping = body.damping * v[i], drag = body.drag * v[i] * Math.abs(v[i]);
            // A pinned node's static reaction is an unknown, bounded by the active-set check.
            double load = sticking[i] ? drive - damping - drag + forces[i] - 2 * body.inertia / h * increment[i]
                : body.load * signs[i];
            work += h * v[i] * (drive - damping - drag - load);
            workScale += h * Math.abs(v[i]) * (Math.abs(drive) + Math.abs(damping) + Math.abs(drag) + Math.abs(load));
            double magnitude = Math.max(Math.abs(previous[i]), Math.abs(speed[i]));
            roundoff += 8 * body.inertia * magnitude * Math.ulp(magnitude);
        }
        double tolerance = 1e-10 * workScale + roundoff + 1e-12;
        if (!Double.isFinite(change) || !Double.isFinite(work) || Math.abs(change - work) > tolerance)
            throw new ConvergenceFailure("Torsion work balance did not converge: defect=" + (change - work)
                + ", tolerance=" + tolerance);
    }

    /** Sparse preconditioned conjugate gradients, including exact pinned static-friction nodes. */
    private static double[] solve(List<Link> links, double[] weights, double[] base, double[] rhs,
                                  double[] rhsMagnitude, boolean[] fixed, double[] values, double[] guess) {
        int n = base.length;
        double[] diagonal = base.clone(), b = rhs.clone(), bMagnitude = rhsMagnitude.clone(), x = guess.clone();
        for (int e = 0; e < links.size(); e++) {
            Link l = links.get(e); double w = weights[e];
            diagonal[l.a] += w * l.ratio * l.ratio; diagonal[l.b] += w;
            if (fixed[l.a] && !fixed[l.b]) {
                double force = w * l.ratio * values[l.a]; b[l.b] += force; bMagnitude[l.b] += Math.abs(force);
            }
            if (fixed[l.b] && !fixed[l.a]) {
                double force = w * l.ratio * values[l.b]; b[l.a] += force; bMagnitude[l.a] += Math.abs(force);
            }
        }
        for (int i = 0; i < n; i++) {
            if (fixed[i]) { b[i] = values[i]; bMagnitude[i] = Math.abs(b[i]); x[i] = values[i]; diagonal[i] = 1; }
            // An isolated unloaded massless port has a free speed, not an invented inertia.
            else if (diagonal[i] == 0) { b[i] = guess[i]; bMagnitude[i] = Math.abs(b[i]); diagonal[i] = 1; base[i] = 1; }
        }
        double[] r = new double[n], z = new double[n], p = new double[n];
        double scale = 0;
        for (int i = 0; i < n; i++) {
            scale += b[i] * b[i] / diagonal[i];
        }
        Residual checked = residual(links, weights, base, fixed, diagonal, b, bMagnitude, x, r, z, scale);
        double tolerance = checked.tolerance, rz = checked.norm;
        System.arraycopy(z, 0, p, 0, n);
        int limit = Math.max(64, n * 6);
        for (int k = 0; k < limit; k++) {
            if (checked.converged) return x; // Both checks use the actual equation, not recursive CG estimates.
            double[] ap = multiply(links, weights, base, fixed, p);
            double denominator = dot(p, ap);
            if (!(denominator > 0) || !Double.isFinite(denominator)) break;
            double alpha = rz / denominator;
            for (int i = 0; i < n; i++) {
                x[i] = Math.fma(alpha, p[i], x[i]); r[i] = Math.fma(-alpha, ap[i], r[i]); z[i] = r[i] / diagonal[i];
            }
            double nextRz = dot(r, z), beta = nextRz / rz;
            if (nextRz <= tolerance || (k + 1) % Math.max(32, n) == 0) {
                // Recursive CG residuals drift. Verify, then restart if refinement is needed.
                checked = residual(links, weights, base, fixed, diagonal, b, bMagnitude, x, r, z, scale);
                tolerance = checked.tolerance; rz = checked.norm; System.arraycopy(z, 0, p, 0, n);
                continue;
            }
            for (int i = 0; i < n; i++) p[i] = z[i] + beta * p[i];
            rz = nextRz;
        }
        checked = residual(links, weights, base, fixed, diagonal, b, bMagnitude, x, r, z, scale);
        if (checked.converged) return x;
        throw new ConvergenceFailure("Torsion linear solve did not converge for " + n + " nodes: norm=" + checked.norm
            + ", tolerance=" + checked.tolerance);
    }

    private record Residual(double norm, double tolerance, boolean converged) {}

    private static Residual residual(List<Link> links, double[] weights, double[] base, boolean[] fixed,
                                    double[] diagonal, double[] b, double[] bMagnitude,
                                    double[] x, double[] r, double[] z, double scale) {
        double[] ax = multiply(links, weights, base, fixed, x);
        double[] magnitude = new double[x.length];
        for (int i = 0; i < x.length; i++) magnitude[i] = Math.abs(b[i]) + Math.abs(fixed[i] ? x[i] : base[i] * x[i]);
        for (int e = 0; e < links.size(); e++) {
            Link link = links.get(e);
            double force = weights[e] * (Math.abs(link.ratio * (fixed[link.a] ? 0 : x[link.a]))
                + Math.abs(fixed[link.b] ? 0 : x[link.b]));
            if (!fixed[link.a]) magnitude[link.a] += Math.abs(link.ratio) * force;
            if (!fixed[link.b]) magnitude[link.b] += force;
        }
        double floor = 0, worst = 0;
        for (int i = 0; i < x.length; i++) {
            r[i] = b[i] - ax[i]; z[i] = r[i] / diagonal[i];
            // Include cancellation while assembling b, not only while evaluating A*x.
            // Opposing contact/source torques can leave b many orders smaller than
            // its operands; their rounding uncertainty does not disappear with it.
            double error = 32 * Math.ulp(magnitude[i] + bMagnitude[i]);
            floor += error * error / diagonal[i];
            // A short fracture trial makes rotor diagonals (2J/h) huge while massless
            // port diagonals (hK/2) shrink. The global preconditioned norm can then
            // hide an inaccurate rotor impulse behind the ports' much larger scale.
            // Require each torque equation to converge as well.
            // Use the componentwise backward error |r| / (|b| + |A| |x|).
            // A balanced shaft can have a nearly zero RHS while both sides of its
            // equation carry finite torque. Scaling by that cancelled RHS alone
            // can demand unattainable precision and repeatedly shrink the time step.
            double allowance = 1e-12 * magnitude[i] + error;
            worst = Math.max(worst, Math.abs(r[i]) / allowance);
        }
        // Keep the relative coefficient; its scale now measures this component's increment
        // equation. Only a floating-point evaluation floor depends on cancellation in A*u.
        // Squaring an asymptotically vanishing contact force can underflow. Do not ask CG
        // to resolve a relative squared norm below the normal floating-point range.
        double norm = preciseDot(r, z);
        double tolerance = Math.max(Double.MIN_NORMAL, 1e-24 * scale + floor);
        boolean converged = norm <= tolerance && (worst <= 1 || norm <= Double.MIN_NORMAL);
        // After a failed per-node check, let CG reduce that error before restarting.
        if (worst > 1) tolerance = Math.max(Double.MIN_NORMAL, Math.min(tolerance, norm / (worst * worst)));
        return new Residual(norm, tolerance, converged);
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
    /** Compensated summation at acceptance checks; recursive CG estimates are verified there. */
    private static double preciseDot(double[] a, double[] b) {
        double sum = 0, correction = 0;
        for (int i = 0; i < a.length; i++) {
            double value = a[i] * b[i] - correction, next = sum + value;
            correction = (next - sum) - value; sum = next;
        }
        return sum;
    }
}
