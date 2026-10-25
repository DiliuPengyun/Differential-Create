package com.diliupengyun.differential_create;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.diliupengyun.differential_create.kinetics.MasslessTopology;
import com.diliupengyun.differential_create.kinetics.MasslessTopology.Contact;

/** Independent equilibrium/energy expectations for the topology event policy. */
public final class MasslessTopologyRegression {
    private static int checks;
    public static void main(String[] args) { run(); }
    public static void run() {
        tree(); cycles(); meshes(); incompatible(); fractures(); invalid();
        System.out.println("Massless topology: " + checks + " checks passed");
    }
    private static void tree() {
        for (int n : new int[]{1, 2, 257, 4097}) {
            var edges = new ArrayList<Contact>();
            double[] speeds = new double[n], ratios = new double[n];
            double numerator = 0, denominator = 0;
            ratios[0] = 1;
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    double q = i % 2 == 0 ? -.5 : -2;
                    ratios[i] = ratios[i - 1] * q;
                    edges.add(edge(i - 1, i, q, 1e7, (i % 3 - 1) * 1e-4));
                }
                speeds[i] = i % 2 == 0 ? 1e12 : -3e12;
                numerator += ratios[i] * speeds[i]; denominator += ratios[i] * ratios[i];
            }
            var p = MasslessTopology.project(speeds, edges);
            yes(!p.jammed() && p.broken() < 0, "free tree has one mode");
            near(p.energyBefore(), p.loss(), 1e-12, "tree relaxation energy");
            for (int i = 0; i < n; i++) near(ratios[i] * numerator / denominator, p.speed()[i], 1e-11, "tree speed projection");
            for (double x : p.twist()) near(0, x, 0, "tree cannot retain self-stress");
        }
    }
    private static void cycles() {
        // General compatible graph: independent closed flows give B*f=0;
        // adding B^T*dtheta changes no cycle invariant, hence must be removed.
        int[][] ends = {{0,1}, {1,2}, {2,3}, {3,0}, {0,2}};
        Random random = new Random(94171);
        for (int trial = 0; trial < 300; trial++) {
            double[] ratios = {1, -2, -1, 2};
            double[] angle = new double[4], speed = new double[4];
            for (int i = 0; i < 4; i++) {
                angle[i] = (random.nextDouble() - .5) * 1e-8;
                speed[i] = ratios[i] * 37;
            }
            double ring = (random.nextDouble() - .5) * 100, triangle = (random.nextDouble() - .5) * 100;
            double[] flow = {ring + triangle, ring + triangle, ring, ring, -triangle};
            var edges = new ArrayList<Contact>();
            double[] expected = new double[5];
            for (int e = 0; e < 5; e++) {
                int a = ends[e][0], b = ends[e][1];
                double q = ratios[b] / ratios[a], k = Math.pow(10, random.nextInt(13));
                double equilibrium = flow[e] / ratios[b] / k;
                double x = equilibrium + q * angle[a] - angle[b];
                if (random.nextBoolean()) {
                    edges.add(edge(b, a, 1 / q, k * q * q, -x / q));
                    expected[e] = -equilibrium / q;
                } else { edges.add(edge(a, b, q, k, x)); expected[e] = equilibrium; }
            }
            var nodeOrder = new ArrayList<>(List.of(0,1,2,3));
            var edgeOrder = new ArrayList<>(List.of(0,1,2,3,4));
            java.util.Collections.shuffle(nodeOrder, random); java.util.Collections.shuffle(edgeOrder, random);
            var shuffled = new ArrayList<Contact>();
            double[] reorderedExpected = new double[5], reorderedSpeed = new double[4];
            for (int i = 0; i < 4; i++) reorderedSpeed[nodeOrder.get(i)] = speed[i];
            for (int i = 0; i < 5; i++) {
                var c = edges.get(edgeOrder.get(i));
                shuffled.add(new Contact(nodeOrder.get(c.a()), nodeOrder.get(c.b()), c.ratio(), c.stiffness(), c.strength(), c.twist()));
                reorderedExpected[i] = expected[edgeOrder.get(i)];
            }
            edges = shuffled; expected = reorderedExpected; speed = reorderedSpeed;
            var p = MasslessTopology.project(speed, edges);
            yes(!p.jammed() && p.broken() < 0, "compatible redundant cycles");
            for (int e = 0; e < 5; e++) near(expected[e], p.twist()[e], 1e-9, "energy-minimizing cycle strain");
            near(p.energyBefore(), p.energyAfter() + p.loss(), 1e-12, "closed energy ledger");
            var saved = replace(edges, p.twist());
            var again = MasslessTopology.project(p.speed(), saved);
            near(0, again.loss(), 0, "balanced self-stress survives another rebuild exactly");
            for (int e = 0; e < 5; e++) near(p.twist()[e], again.twist()[e], 0, "idempotent strain");
        }
    }
    private static void meshes() {
        for (int side : new int[]{7, 8, 24, 64}) for (boolean preload : new boolean[]{false, true})
            for (boolean jammed : new boolean[]{false, true}) {
            int n = side * side;
            Random random = new Random(631 + side);
            double[] angle = new double[n], speed = new double[n];
            for (int i = 0; i < n; i++) { angle[i] = (random.nextDouble() - .5) * 1e-4; speed[i] = 37; }
            var edges = new ArrayList<Contact>(); var expected = new ArrayList<Double>();
            for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) {
                int a = y * side + x;
                if (x + 1 < side) {
                    double stress = !preload ? 0 : y == 0 ? 1e-4 : y == side - 1 ? -1e-4 : 0;
                    edges.add(edge(a,a+1,1,1e7,stress+angle[a]-angle[a+1])); expected.add(stress);
                }
                if (y + 1 < side) {
                    double stress = !preload ? 0 : x == side - 1 ? 1e-4 : x == 0 ? -1e-4 : 0;
                    edges.add(edge(a,a+side,1,1e7,stress+angle[a]-angle[a+side])); expected.add(stress);
                }
            }
            if (jammed) { edges.add(edge(0,side+1,2,1e7,2*angle[0]-angle[side+1])); expected.add(0d); }
            MasslessTopology.Result p;
            try { p = MasslessTopology.project(speed, edges); }
            catch (ArithmeticException failure) { throw new AssertionError("mesh " + side + " preload=" + preload + " jammed=" + jammed, failure); }
            yes(p.jammed() == jammed, "mesh closure");
            for (double value : p.speed()) near(jammed ? 0 : 37, value, 1e-12, "mesh speed mode");
            for (int e = 0; e < edges.size(); e++) near(expected.get(e), p.twist()[e], 1e-12, "large mesh equilibrium");
            near(preload ? 2 * (side - 1) * 1e7 * 1e-8 : 0, p.energyAfter(), 1e-11, "known perimeter preload energy");
            var repeat = MasslessTopology.project(p.speed(), replace(edges,p.twist()));
            near(0, repeat.loss(), 0, "large mesh rebuild is idempotent");
        }
    }
    private static void incompatible() {
        for (double closure : new double[]{-1, 2, .5, 1 + 1e-8, 1 - 1e-8}) {
            var edges = List.of(edge(0,1,1,1e7,.0001), edge(1,2,1,1e7,-.0002), edge(2,0,closure,1e7,.0003));
            var p = MasslessTopology.project(new double[]{10,20,30}, edges);
            yes(p.jammed(), "incompatible closure eliminates the free mode");
            for (double w : p.speed()) near(0, w, 0, "jam speed");
            for (double x : p.twist()) near(0, x, 0, "simple incompatible loop has no self-stress");
        }
        // An incompatible loop can still contain an independent compatible
        // cycle: locking its speeds must NOT erase that cycle's preload.
        double[] expected = {1,.5,.25,0};
        // x = [1,.5,.25,0] + B^T*[.5,.3,.1].
        var edges = List.of(edge(0,1,1,10,1.2), edge(1,2,1,20,.7),
            edge(2,0,1,40,-.15), edge(0,2,2,30,.9));
        var p = MasslessTopology.project(new double[]{10,20,30}, edges);
        yes(p.jammed(), "redundant incompatible graph jams");
        for (int e = 0; e < 4; e++) near(expected[e], p.twist()[e], 1e-12, "retain self-stress inside jammed assembly");
    }
    private static void fractures() {
        double[] speeds = {10,20,30};
        var edges = List.of(new Contact(0,1,1,1,1,0), new Contact(1,2,1,1,10,3), new Contact(2,0,1,1,10,3));
        var p = MasslessTopology.project(speeds, edges);
        yes(p.broken() == 0, "redistribution breaks previously unstressed weak contact");
        near(1, p.twist()[0], 1e-12, "stop at first strength crossing");
        near(2.5, p.twist()[1], 1e-12, "other contacts at the same projection fraction");
        near(2.25, p.loss(), 1e-12, "partial projection energy");
        for (int i = 0; i < 3; i++) near(speeds[i], p.speed()[i], 0, "fracture retains prior speeds for fragment projection");
        edges = List.of(new Contact(0,1,1,1,1,1 + 1e-10), edge(1,2,1,1,0));
        p = MasslessTopology.project(speeds, edges);
        yes(p.broken() == 0, "saved overload fractures before tree relaxation can erase it");
        near(0, p.loss(), 0, "no relaxation before existing overload");
        var fragment = MasslessTopology.project(new double[]{20,30}, List.of(edge(0,1,1,1,0)));
        near(25, fragment.speed()[0], 1e-14, "surviving fragment selects compatible inherited speed");
    }
    private static void invalid() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY}) {
            boolean rejected = false;
            try { MasslessTopology.project(new double[]{bad}, List.of()); }
            catch (ArithmeticException expected) { rejected = true; }
            yes(rejected, "invalid speed explicitly rejected");
        }
    }
    private static Contact edge(int a, int b, double q, double k, double x) {
        return new Contact(a,b,q,k,Double.POSITIVE_INFINITY,x);
    }
    private static List<Contact> replace(List<Contact> edges, double[] twist) {
        var result = new ArrayList<Contact>();
        for (int e = 0; e < edges.size(); e++) {
            var c = edges.get(e); result.add(new Contact(c.a(),c.b(),c.ratio(),c.stiffness(),c.strength(),twist[e]));
        }
        return result;
    }
    private static void yes(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void near(double expected, double actual, double tolerance, String label) {
        checks++;
        if (!Double.isFinite(actual) || Math.abs(expected-actual) > tolerance * Math.max(1,Math.abs(expected)))
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
}
