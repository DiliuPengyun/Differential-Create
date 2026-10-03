package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;

/** A dry inventory mouth can hold its front between empty-space and liquid pressure.
 * Inflow uses the liquid datum; outgoing empty space does no inventory pressure work. */
public final class FluidPressureBounds {
    private FluidPressureBounds() {}
    public record Contact(int node, double liquidPressure) {
        public Contact {
            if (!(liquidPressure < 0) || !Double.isFinite(liquidPressure))
                throw new IllegalArgumentException("A dry suction contact needs negative liquid pressure");
        }
    }
    /** Updates only contact pressures. Interior solutions impose zero mouth flow. */
    public static boolean[] solve(ReducedFluidGraph graph, double[] initial, double[] inertia, double[] force,
                                  double[] pressure, List<Contact> contacts, double minutes) {
        boolean[] pinned = new boolean[pressure.length];
        if (contacts.isEmpty()) return pinned;
        int count = contacts.size();
        for (var contact : contacts) pressure[contact.node] = 0;
        var empty = graph.sample(initial, inertia, force, pressure, minutes);
        double[] base = new double[count]; double[][] response = new double[count][count];
        for (int i = 0; i < count; i++) base[i] = empty.outward()[contacts.get(i).node];
        for (int j = 0; j < count; j++) {
            var contact = contacts.get(j); double step = -contact.liquidPressure;
            pressure[contact.node] = step;
            var probe = graph.sample(initial, inertia, force, pressure, minutes);
            pressure[contact.node] = 0;
            for (int i = 0; i < count; i++) response[i][j] = (probe.outward()[contacts.get(i).node] - base[i]) / step;
        }
        double[] value = new double[count]; int[] state = new int[count]; // 0: empty, -1: liquid, 1: stationary front.
        boolean complete = false;
        for (int iteration = 0; iteration < 16 + 16 * count * count; iteration++) {
            var free = new ArrayList<Integer>();
            for (int i = 0; i < count; i++) if (state[i] == 1) free.add(i);
            if (!free.isEmpty()) {
                double[][] matrix = new double[free.size()][free.size()]; double[] rhs = new double[free.size()];
                for (int i = 0; i < free.size(); i++) {
                    int row = free.get(i); rhs[i] = -base[row];
                    for (int j = 0; j < count; j++) if (state[j] != 1) rhs[i] -= response[row][j] * value[j];
                    for (int j = 0; j < free.size(); j++) matrix[i][j] = response[row][free.get(j)];
                }
                double[] candidate = linear(matrix, rhs); double fraction = 1; int hit = -1, side = 0;
                for (int i = 0; i < free.size(); i++) {
                    int index = free.get(i); double delta = candidate[i] - value[index];
                    double bound = candidate[i] < contacts.get(index).liquidPressure ? contacts.get(index).liquidPressure : 0;
                    if (candidate[i] >= contacts.get(index).liquidPressure && candidate[i] <= 0) continue;
                    double travel = (bound - value[index]) / delta;
                    if (travel < fraction) { fraction = Math.max(0, travel); hit = index; side = bound == 0 ? 0 : -1; }
                }
                for (int i = 0; i < free.size(); i++) {
                    int index = free.get(i); value[index] += fraction * (candidate[i] - value[index]);
                }
                if (hit >= 0) {
                    value[hit] = side == 0 ? 0 : contacts.get(hit).liquidPressure; state[hit] = side;
                    continue;
                }
            }
            int release = -1; double largest = 0;
            for (int i = 0; i < count; i++) if (state[i] != 1 && response[i][i] > 0) {
                double flow = base[i], scale = Math.abs(flow);
                for (int j = 0; j < count; j++) { double term = response[i][j] * value[j]; flow += term; scale += Math.abs(term); }
                double violation = state[i] == 0 ? flow : -flow;
                if (violation <= 64 * Math.ulp(scale)) continue;
                double normalized = violation / Math.sqrt(response[i][i]);
                if (normalized > largest) { largest = normalized; release = i; }
            }
            if (release < 0) { complete = true; break; }
            state[release] = 1;
        }
        if (!complete) throw new IllegalStateException("Dry inventory pressure bounds did not converge");
        for (int i = 0; i < count; i++) {
            pressure[contacts.get(i).node] = value[i];
            pinned[contacts.get(i).node] = state[i] == 1;
        }
        return pinned;
    }
    private static double[] linear(double[][] matrix, double[] rhs) {
        for (int column = 0; column < rhs.length; column++) {
            int pivot = column;
            for (int row = column + 1; row < rhs.length; row++) if (Math.abs(matrix[row][column]) > Math.abs(matrix[pivot][column])) pivot = row;
            if (!(Math.abs(matrix[pivot][column]) > 0)) throw new IllegalStateException("Singular dry inventory pressure response");
            double[] swap = matrix[column]; matrix[column] = matrix[pivot]; matrix[pivot] = swap;
            double entry = rhs[column]; rhs[column] = rhs[pivot]; rhs[pivot] = entry;
            for (int row = column + 1; row < rhs.length; row++) {
                double factor = matrix[row][column] / matrix[column][column];
                for (int j = column + 1; j < rhs.length; j++) matrix[row][j] -= factor * matrix[column][j];
                rhs[row] -= factor * rhs[column];
            }
        }
        double[] result = new double[rhs.length];
        for (int row = rhs.length - 1; row >= 0; row--) {
            double value = rhs[row];
            for (int j = row + 1; j < rhs.length; j++) value -= matrix[row][j] * result[j];
            result[row] = value / matrix[row][row];
            if (!Double.isFinite(result[row])) throw new IllegalStateException("Nonfinite dry inventory pressure");
        }
        return result;
    }
}
