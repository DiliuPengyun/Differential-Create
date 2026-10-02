package com.diliupengyun.differential_create.kinetics;

import java.util.Arrays;
import java.util.List;

/** Linear-space leaf elimination for A = diag(base) + sum w [q,-1]'[q,-1]. */
final class TreeFactorization {
    private final int root;
    private final int[] a, b, parent, edge, order;
    private final double[] ratio, pivot, coupling, ground, temporary;
    private final double[] savedBase, savedDiagonal, savedWeights;
    private final boolean[] savedFixed;
    private boolean valid;

    static TreeFactorization create(List<TorsionSolver.Body> bodies, int[] a, int[] b, double[] ratio) {
        // Tiny systems already solve cheaply; avoid speculative work and fallback overhead there.
        int n = bodies.size();
        if (n < 4 || a.length != n - 1) return null;
        int root = 0;
        for (int i = 1; i < n; i++)
            if (bodies.get(i).inertia() > bodies.get(root).inertia()) root = i;
        int[] parent = new int[n], edge = new int[n], order = new int[n], degree = new int[n];
        Arrays.fill(parent, -1);
        for (int e = 0; e < a.length; e++) { degree[a[e]]++; degree[b[e]]++; }
        int[][] neighbors = new int[n][], edges = new int[n][];
        for (int i = 0; i < n; i++) {
            neighbors[i] = new int[degree[i]]; edges[i] = new int[degree[i]]; degree[i] = 0;
        }
        for (int e = 0; e < a.length; e++) {
            int x = a[e], y = b[e];
            neighbors[x][degree[x]] = y; edges[x][degree[x]++] = e;
            neighbors[y][degree[y]] = x; edges[y][degree[y]++] = e;
        }
        int size = 1;
        order[0] = root; parent[root] = root;
        for (int head = 0; head < size; head++) {
            int x = order[head];
            for (int j = 0; j < neighbors[x].length; j++) {
                int y = neighbors[x][j];
                if (y == parent[x]) continue;
                if (parent[y] >= 0) return null;
                parent[y] = x; edge[y] = edges[x][j]; order[size++] = y;
            }
        }
        return size == n ? new TreeFactorization(root, a, b, ratio, parent, edge, order) : null;
    }

    private TreeFactorization(int root, int[] a, int[] b, double[] ratio,
                              int[] parent, int[] edge, int[] order) {
        this.root = root; this.a = a; this.b = b; this.ratio = ratio;
        this.parent = parent; this.edge = edge; this.order = order;
        int n = order.length;
        pivot = new double[n]; coupling = new double[n]; ground = new double[n]; temporary = new double[n];
        savedBase = new double[n]; savedDiagonal = new double[n]; savedWeights = new double[a.length];
        savedFixed = new boolean[n];
    }

    boolean factor(double[] base, double[] diagonal, double[] weights, boolean[] fixed) {
        // Reuse only an exactly identical matrix and pinned-node set. RHS values may change.
        if (valid && Arrays.equals(base, savedBase) && Arrays.equals(diagonal, savedDiagonal)
            && Arrays.equals(weights, savedWeights) && Arrays.equals(fixed, savedFixed)) return true;
        valid = false;
        for (int i = 0; i < order.length; i++) {
            ground[i] = fixed[i] ? 1 : base[i];
            if (ground[i] < 0 || !Double.isFinite(ground[i])) return false;
        }
        for (int e = 0; e < a.length; e++) {
            int x = a[e], y = b[e];
            double w = weights[e], q = ratio[e];
            if (w < 0 || !Double.isFinite(w) || !Double.isFinite(q)) return false;
            if (fixed[x] && !fixed[y]) ground[y] += w;
            if (fixed[y] && !fixed[x]) ground[x] += w * q * q;
        }
        for (int k = order.length - 1; k > 0; k--) {
            int x = order[k], y = parent[x], e = edge[x];
            double w = fixed[x] || fixed[y] ? 0 : weights[e], q = ratio[e];
            double own = w * (x == a[e] ? q * q : 1), other = w * (y == a[e] ? q * q : 1);
            double shunt = ground[x];
            pivot[x] = shunt + own;
            if (!(pivot[x] > 0) || !Double.isFinite(pivot[x])) return false;
            coupling[x] = -w * q / pivot[x];
            // Equivalent to other - offDiagonal^2 / pivot. Nonnegative shunts avoid
            // cancelling almost equal spring diagonals at inertialess transmission nodes.
            ground[y] += other * (shunt / pivot[x]);
        }
        pivot[root] = ground[root]; coupling[root] = 0;
        if (!(pivot[root] > 0) || !Double.isFinite(pivot[root])) return false;
        System.arraycopy(base, 0, savedBase, 0, base.length);
        System.arraycopy(diagonal, 0, savedDiagonal, 0, diagonal.length);
        System.arraycopy(weights, 0, savedWeights, 0, weights.length);
        System.arraycopy(fixed, 0, savedFixed, 0, fixed.length);
        valid = true;
        return true;
    }

    void solve(double[] rhs, double[] result) {
        System.arraycopy(rhs, 0, temporary, 0, rhs.length);
        for (int k = order.length - 1; k > 0; k--) {
            int x = order[k], y = parent[x];
            temporary[y] = Math.fma(-coupling[x], temporary[x], temporary[y]);
        }
        result[root] = temporary[root] / pivot[root];
        for (int k = 1; k < order.length; k++) {
            int x = order[k];
            result[x] = Math.fma(-coupling[x], result[parent[x]], temporary[x] / pivot[x]);
        }
    }
}
