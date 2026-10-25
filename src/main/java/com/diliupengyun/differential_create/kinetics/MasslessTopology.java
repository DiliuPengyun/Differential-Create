package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Explicit dissipative reset of a connected, unloaded, inertialess rigid assembly.
 * This is an initial-condition policy, not a finite-time integration step. */
public final class MasslessTopology {
    public record Contact(int a, int b, double ratio, double stiffness, double strength, double twist) {}
    public record Result(double[] speed, double[] twist, boolean jammed, int broken,
                         double energyBefore, double energyAfter) {
        public double loss() { return Math.max(0, energyBefore - energyAfter); }
    }
    private static final double CLOSURE_TOLERANCE = 1e-12;
    private MasslessTopology() {}

    public static Result project(double[] previous, List<Contact> contacts) {
        int n = previous.length, m = contacts.size();
        if (n == 0) throw new IllegalArgumentException("Empty assembly");
        for (double speed : previous) finite(speed);
        double[] twist = new double[m], rootK = new double[m];
        var adjacency = new ArrayList<List<Integer>>(n);
        for (int i = 0; i < n; i++) adjacency.add(new ArrayList<>());
        for (int e = 0; e < m; e++) {
            Contact c = contacts.get(e);
            if (c.a < 0 || c.a >= n || c.b < 0 || c.b >= n || c.a == c.b
                || c.ratio == 0 || !(c.stiffness > 0) || !(c.strength > 0))
                throw new IllegalArgumentException("Invalid contact");
            finite(c.ratio); finite(c.stiffness); finite(c.twist);
            twist[e] = c.twist;
            rootK[e] = Math.sqrt(c.stiffness);
            adjacency.get(c.a).add(e); adjacency.get(c.b).add(e);
        }
        double before = energy(twist, rootK);
        // Test the real saved stress before changing speeds or relaxing anything.
        for (int e = 0; e < m; e++)
            if (Math.abs(contacts.get(e).stiffness * twist[e]) >= contacts.get(e).strength)
                return new Result(previous.clone(), twist, false, e, before, before);

        int[] order = new int[n], parentEdge = new int[n];
        Arrays.fill(parentEdge, -1);
        boolean[] visited = new boolean[n], tree = new boolean[m];
        double[] ratios = new double[n];
        ratios[0] = 1; visited[0] = true;
        int count = 1;
        for (int at = 0; at < count; at++) {
            int node = order[at];
            for (int e : adjacency.get(node)) {
                Contact c = contacts.get(e);
                int next = c.a == node ? c.b : c.a;
                if (visited[next]) continue;
                visited[next] = true; tree[e] = true;
                parentEdge[next] = e; order[count++] = next;
                ratios[next] = c.a == node ? ratios[node] * c.ratio : ratios[node] / c.ratio;
                finite(ratios[next]);
                if (ratios[next] == 0) throw new ArithmeticException("Underflow in topology ratio");
            }
        }
        if (count != n) throw new IllegalArgumentException("Assembly must be connected");
        boolean jammed = false;
        boolean[] incompatible = new boolean[m];
        for (int e = 0; e < m; e++) {
            Contact c = contacts.get(e);
            if (tree[e]) continue;
            double closure = c.ratio * ratios[c.a] / ratios[c.b];
            finite(closure);
            incompatible[e] = Math.abs(closure - 1) > CLOSURE_TOLERANCE;
            jammed |= incompatible[e];
        }
        double[] projected = twist;
        boolean equilibrium = balanced(twist, contacts);
        if (!equilibrium && compatibleStrain(twist, contacts, order, parentEdge, ratios, jammed)) {
            projected = new double[m];
        } else if (!equilibrium && m - n + 1 > 48) {
            // Dense cycle bases are useful for small, badly scaled loops; a
            // large mesh instead uses a sparse potential solve with O(n+m) storage.
            projected = sparseProjection(twist, rootK, contacts, n, jammed);
        } else if (!equilibrium) {
            // B has q at a and -1 at b. Self-stress is ker(B). Build its
            // fundamental cycles by balancing each non-root node along a tree.
            // Work in f/sqrt(K): Euclidean projection then minimizes elastic
            // energy while retaining every cycle invariant of x + B^T*dtheta.
            var cycles = new ArrayList<double[]>();
            var residuals = new ArrayList<Double>();
            int pivot = -1;
            double largest = 0;
            for (int e = 0; e < m; e++) {
                if (tree[e]) continue;
                Contact chord = contacts.get(e);
                double[] force = new double[n], flow = new double[m];
                force[chord.a] = chord.ratio; force[chord.b] = -1; flow[e] = 1;
                for (int at = n - 1; at > 0; at--) {
                    int node = order[at], t = parentEdge[node];
                    Contact c = contacts.get(t);
                    boolean left = c.a == node;
                    flow[t] = -force[node] / (left ? c.ratio : -1);
                    force[left ? c.b : c.a] += (left ? -1 : c.ratio) * flow[t];
                }
                for (int t = 0; t < m; t++) flow[t] /= rootK[t];
                double norm = norm(flow);
                for (int t = 0; t < m; t++) flow[t] /= norm;
                // Consistent closures can leave only roundoff at the root.
                double residual = incompatible[e] ? force[0] / norm : 0;
                finite(residual);
                if (Math.abs(residual) > largest) { largest = Math.abs(residual); pivot = cycles.size(); }
                cycles.add(flow); residuals.add(residual);
            }
            if (jammed && pivot < 0) throw new ArithmeticException("Unresolved rigid closure");
            var basis = new ArrayList<double[]>();
            for (int i = 0; i < cycles.size(); i++) {
                if (i == pivot) continue;
                double[] vector = cycles.get(i).clone();
                if (pivot >= 0) add(vector, cycles.get(pivot), -residuals.get(i) / residuals.get(pivot));
                // Twice reorthogonalized MGS; never silently discard an uncertain rank.
                for (int repeat = 0; repeat < 2; repeat++)
                    for (double[] b : basis) add(vector, b, -dot(vector, b));
                double norm = norm(vector);
                if (norm < 1e-12) throw new ArithmeticException("Ill-conditioned self-stress basis");
                for (int e = 0; e < m; e++) vector[e] /= norm;
                basis.add(vector);
            }
            double[] weighted = new double[m], relaxed = new double[m];
            for (int e = 0; e < m; e++) weighted[e] = rootK[e] * twist[e];
            for (double[] b : basis) add(relaxed, b, dot(weighted, b));
            projected = new double[m];
            for (int e = 0; e < m; e++) projected[e] = relaxed[e] / rootK[e];
        }
        if (!balanced(projected, contacts)) throw new ArithmeticException("Unbalanced topology projection");
        // Relaxation can concentrate stress on a weaker contact. Stop at its
        // first strength crossing; fragments inherit the pre-reset velocities.
        double fraction = 1;
        int broken = -1;
        for (int e = 0; e < m; e++) {
            Contact c = contacts.get(e);
            double end = c.stiffness * projected[e], start = c.stiffness * twist[e];
            if (Math.abs(end) < c.strength) continue;
            double crossing = (Math.copySign(c.strength, end) - start) / (end - start);
            if (crossing <= fraction) {
                if (crossing < fraction || broken < 0) { fraction = crossing; broken = e; }
            }
        }
        if (broken >= 0) {
            for (int e = 0; e < m; e++) projected[e] = Math.fma(fraction, projected[e] - twist[e], twist[e]);
        }
        double after = energy(projected, rootK);
        if (after > before + 1e-12 * before) throw new ArithmeticException("Topology reset created energy");
        double[] speed = previous.clone();
        if (broken < 0) {
            Arrays.fill(speed, 0);
            if (!jammed) {
                double ratioScale = 0, speedScale = 0;
                for (int i = 0; i < n; i++) {
                    ratioScale = Math.max(ratioScale, Math.abs(ratios[i]));
                    speedScale = Math.max(speedScale, Math.abs(previous[i]));
                }
                double numerator = 0, denominator = 0;
                for (int i = 0; i < n; i++) {
                    ratios[i] /= ratioScale;
                    numerator += ratios[i] * (speedScale == 0 ? 0 : previous[i] / speedScale);
                    denominator += ratios[i] * ratios[i];
                }
                double common = numerator / denominator;
                for (int i = 0; i < n; i++) { speed[i] = (ratios[i] * common) * speedScale; finite(speed[i]); }
            }
        }
        return new Result(speed, projected, jammed, broken, before, after);
    }

    /** Zero cycle strain can be recognized without subtracting two CG solutions
     * to produce a nearly-zero but unbalanced spring field. */
    private static boolean compatibleStrain(double[] twist, List<Contact> contacts, int[] order, int[] parentEdge,
                                           double[] ratios, boolean jammed) {
        double[] phase = new double[order.length];
        int[] depth = new int[order.length];
        for (int at = 1; at < order.length; at++) {
            int node = order[at], e = parentEdge[node];
            Contact c = contacts.get(e);
            int parent = c.a == node ? c.b : c.a;
            phase[node] = c.a == parent ? c.ratio * phase[parent] - twist[e] : (phase[parent] + twist[e]) / c.ratio;
            depth[node] = depth[parent] + 1;
            finite(phase[node]);
        }
        if (jammed) {
            double largest = 0, root = 0;
            for (int e = 0; e < contacts.size(); e++) {
                Contact c = contacts.get(e);
                double closure = c.ratio * ratios[c.a] - ratios[c.b];
                if (Math.abs(closure) <= largest) continue;
                largest = Math.abs(closure);
                root = (twist[e] - (c.ratio * phase[c.a] - phase[c.b])) / closure;
            }
            finite(root);
            for (int i = 0; i < phase.length; i++) { phase[i] = Math.fma(root, ratios[i], phase[i]); finite(phase[i]); }
        }
        for (int e = 0; e < contacts.size(); e++) {
            Contact c = contacts.get(e);
            double left = c.ratio * phase[c.a], right = phase[c.b];
            double scale = Math.abs(left) + Math.abs(right) + Math.abs(twist[e]);
            finite(scale);
            double roundoff = 64 * Math.ulp(scale) * Math.max(1, depth[c.a] + depth[c.b]);
            if (Math.abs(left - right - twist[e]) > roundoff) return false;
        }
        return true;
    }

    private static boolean balanced(double[] twist, List<Contact> contacts) {
        int n = 0;
        for (Contact c : contacts) n = Math.max(n, Math.max(c.a, c.b) + 1);
        double[] sum = new double[n], scale = new double[n];
        for (int e = 0; e < contacts.size(); e++) {
            Contact c = contacts.get(e);
            double force = c.stiffness * twist[e], left = c.ratio * force;
            finite(force); finite(left);
            sum[c.a] += left; sum[c.b] -= force;
            scale[c.a] += Math.abs(left); scale[c.b] += Math.abs(force);
        }
        double reference = 0;
        for (int i = 0; i < n; i++) { finite(sum[i]); finite(scale[i]); reference = Math.max(reference, scale[i]); }
        // A self-stress can vanish on an interior contact. Its local relative
        // residual is then meaningless; use the assembly's force scale as well.
        for (int i = 0; i < n; i++)
            if (Math.abs(sum[i]) > 2e-12 * reference) return false;
        return true;
    }

    private static double[] sparseProjection(double[] twist, double[] rootK, List<Contact> edges, int n, boolean jammed) {
        int m = edges.size();
        double[] diagonal = new double[n], left = new double[m], right = new double[m], rhs = new double[n];
        for (int e = 0; e < m; e++) {
            Contact c = edges.get(e);
            diagonal[c.a] = Math.hypot(diagonal[c.a], c.ratio * rootK[e]);
            diagonal[c.b] = Math.hypot(diagonal[c.b], rootK[e]);
        }
        for (int e = 0; e < m; e++) {
            Contact c = edges.get(e);
            left[e] = c.ratio * rootK[e] / diagonal[c.a]; right[e] = -rootK[e] / diagonal[c.b];
            // Pin only the genuine one-dimensional gauge of a compatible graph.
            if (!jammed && c.a == 0) left[e] = 0;
            if (!jammed && c.b == 0) right[e] = 0;
            rhs[c.a] += left[e] * rootK[e] * twist[e];
            rhs[c.b] += right[e] * rootK[e] * twist[e];
        }
        double[] potential = new double[n], residual = rhs.clone(), direction = rhs.clone();
        double initial = dot(rhs, rhs), rr = initial;
        finite(initial);
        boolean converged = initial == 0;
        for (int iteration = 0; iteration < Math.max(64, 4 * n) && !converged; iteration++) {
            double[] product = apply(direction, edges, left, right, n);
            double curvature = dot(direction, product);
            if (!(curvature > 0)) throw new ArithmeticException("Unresolved sparse topology rank");
            double alpha = rr / curvature;
            add(potential, direction, alpha); add(residual, product, -alpha);
            double next = dot(residual, residual);
            finite(next);
            if (next <= initial * 1e-28 || iteration % 256 == 255) {
                // Verify the actual residual, not just the CG recurrence.
                residual = rhs.clone(); add(residual, apply(potential, edges, left, right, n), -1);
                next = dot(residual, residual);
                if (next <= initial * 1e-26) { converged = true; break; }
                direction = residual.clone();
            } else {
                double beta = next / rr;
                for (int i = 0; i < n; i++) direction[i] = Math.fma(beta, direction[i], residual[i]);
            }
            rr = next;
        }
        if (!converged) throw new ArithmeticException("Sparse topology projection did not converge");
        double[] result = new double[m];
        for (int e = 0; e < m; e++) {
            Contact c = edges.get(e);
            double correction = Math.fma(left[e], potential[c.a], right[e] * potential[c.b]);
            result[e] = (rootK[e] * twist[e] - correction) / rootK[e];
        }
        double originalNorm = 0, remainingNorm = 0;
        for (int e = 0; e < m; e++) {
            originalNorm = Math.hypot(originalNorm, rootK[e] * twist[e]);
            remainingNorm = Math.hypot(remainingNorm, rootK[e] * result[e]);
        }
        // Pure compatible strain has a zero projection; do not turn subtraction
        // roundoff into an unbalanced spring network. This bound is numerical
        // resolution in the energy norm, not a material stress cutoff.
        if (remainingNorm <= 128 * Math.ulp(originalNorm)) Arrays.fill(result, 0);
        return result;
    }
    private static double[] apply(double[] vector, List<Contact> edges, double[] left, double[] right, int n) {
        double[] result = new double[n];
        for (int e = 0; e < edges.size(); e++) {
            Contact c = edges.get(e);
            double value = Math.fma(left[e], vector[c.a], right[e] * vector[c.b]);
            result[c.a] += left[e] * value; result[c.b] += right[e] * value;
        }
        return result;
    }
    private static double energy(double[] twist, double[] rootK) {
        double magnitude = 0;
        for (int e = 0; e < twist.length; e++) magnitude = Math.hypot(magnitude, twist[e] * rootK[e]);
        double energy = .5 * magnitude * magnitude;
        finite(energy); return energy;
    }
    private static double norm(double[] vector) {
        double norm = 0;
        for (double value : vector) norm = Math.hypot(norm, value);
        finite(norm); return norm;
    }
    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum = Math.fma(a[i], b[i], sum);
        return sum;
    }
    private static void add(double[] a, double[] b, double scale) {
        for (int i = 0; i < a.length; i++) a[i] = Math.fma(scale, b[i], a[i]);
    }
    private static void finite(double value) {
        if (!Double.isFinite(value)) throw new ArithmeticException("Non-finite topology state");
    }
}
