package com.diliupengyun.differential_create;

import java.util.List;
import java.util.Random;
import com.diliupengyun.differential_create.kinetics.FluidQueue;
import com.diliupengyun.differential_create.kinetics.QueuedFluidSolver;
import com.diliupengyun.differential_create.kinetics.IdealFluidSolver;
import com.diliupengyun.differential_create.kinetics.RotationMath;

/** Independent volume ledgers and hydraulic expectations for incompressible queues. */
public final class QueuedFluidRegression {
    private static long checks;
    private static void require(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static void near(double expected, double actual, double tolerance, String why) {
        require(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance, why + ": " + actual + " / " + expected);
    }
    public static void run() {
        var q = new FluidQueue<String>(String::equals);
        q.add("air", 40, false); q.add("water", 30, false); q.add("lava", 30, false);
        var reverse = q.take(50, false);
        require(reverse.get(0).identity().equals("lava") && reverse.get(1).identity().equals("water"), "reverse flow visits the actual tail first");
        q.receive(reverse, false);
        require(q.parts().get(2).identity().equals("lava") && q.parts().get(3).identity().equals("water"), "packet order follows arrivals rather than retyping inventory");
        near(100, q.volume(), 0, "closed packet exchange keeps capacity");
        var receiving = new FluidQueue<String>(String::equals); receiving.add("air", 10, false);
        receiving.receive(List.of(new FluidQueue.Part<>("water", 4d), new FluidQueue.Part<>("lava", 3d)), true);
        require(receiving.parts().get(0).identity().equals("lava") && receiving.parts().get(1).identity().equals("water"), "the first arriving parcel moves furthest from a reversed inlet");
        var tiny = new FluidQueue<String>(String::equals); tiny.add("water", .025, false); tiny.add("air", 99.975, false);
        require(tiny.take(.025, true).get(0).amount() == .025, "a real sub-mB parcel is not rounded away");
        require(tiny.peek(true).identity().equals("air"), "complete removal advances the queue identity");
        var filter = new FluidQueue<String>(String::equals);
        filter.add("air", 12, false); filter.add("water", 14, false); filter.add("lava", 16, false); filter.add("water", 58, false);
        near(26, filter.prefixVolume(50, true, p -> !p.equals("lava")), 0, "a filter passes ordered air and water up to the first rejected lava");
        near(58, filter.prefixVolume(75, false, p -> !p.equals("lava")), 0, "reverse filtering cannot skip a blocked parcel");
        near(50, filter.prefixVolume(50, true, p -> true), 0, "a normal port moves a multi-phase packet without slowing at a contact");
        var split = FluidQueue.distribute(List.of(new FluidQueue.Part<>("water", 17.25), new FluidQueue.Part<>("air", 12.75)), new double[]{2, 2, 2});
        for (var branch : split) {
            near(5.75, branch.get(0).amount(), 0, "symmetric outlets split the head water parcel symmetrically");
            near(4.25, branch.get(1).amount(), 0, "following air stays behind that water in every branch");
        }
        // Known binary fractions permit an exact reference ledger, including
        // component-bearing identities and frequent direction reversals.
        String[] kinds = {"air", "water", "lava", "potion:a", "potion:b", "real-gas"};
        var queues = new java.util.ArrayList<FluidQueue<String>>();
        double[] expected = new double[kinds.length];
        for (int n = 0; n < 12; n++) { var p = new FluidQueue<String>(String::equals); p.add("air", 100, false); queues.add(p); expected[0] += 100; }
        var random = new Random(0xA17F1F0);
        for (int step = 0; step < 40000; step++) {
            int from = random.nextInt(12), to = (from + 1 + random.nextInt(11)) % 12;
            boolean front = random.nextBoolean(); double amount = (1 + random.nextInt(100)) / 16d;
            var a = queues.get(from); var b = queues.get(to);
            var left = a.take(amount, front); var right = b.take(amount, !front);
            a.receive(right, front); b.receive(left, !front);
            if ((step & 15) == 0) {
                var previous = a.take(.5, front); for (var p : previous) expected[java.util.Arrays.asList(kinds).indexOf(p.identity())] -= p.amount();
                int identity = random.nextInt(kinds.length); a.add(kinds[identity], .5, front); expected[identity] += .5;
            }
            near(100, a.volume(), 0, "first finite queue stays full"); near(100, b.volume(), 0, "second finite queue stays full");
            if ((step & 31) == 0) for (int k = 0; k < kinds.length; k++) {
                final String kind = kinds[k]; double actual = queues.stream().mapToDouble(p -> p.volume(kind::equals)).sum();
                near(expected[k], actual, 0, "independent per-fluid ledger includes virtual air and component identity");
            }
        }
        circulatingQueues(); hydraulic();
        System.out.println("Queued fluid regression passed: " + checks + " checks");
    }
    private static void circulatingQueues() {
        var cells = new java.util.ArrayList<FluidQueue<Integer>>(); double[] ledger = new double[3];
        for (int n=0;n<9;n++) {
            var q = new FluidQueue<Integer>(Integer::equals);
            for (int part=0;part<16;part++) { int kind=(n+part)%3; q.add(kind,6.25,false); ledger[kind]+=6.25; }
            cells.add(q);
        }
        int maximum = 0;
        for (int tick=0;tick<5000;tick++) {
            var packets = new java.util.ArrayList<List<List<FluidQueue.Part<Integer>>>>();
            for (var q : cells) packets.add(q.distributeBounded(q.take(10,true), new double[]{5,5}, tick));
            for (int n=0;n<9;n++) {
                cells.get(n).receiveJoined(List.of(packets.get((n+8)%9).get(0),packets.get((n+6)%9).get(1)));
                near(100,cells.get(n).volume(),1e-8,"repeated splitting and joining keeps every cell full");
            }
            maximum=Math.max(maximum,cells.stream().mapToInt(FluidQueue::size).sum());
            for(int kind=0;kind<3;kind++) {
                int identity=kind; near(ledger[kind],cells.stream().mapToDouble(q->q.volume(k->k==identity)).sum(),1e-8,
                    "long-running FIFO circulation preserves each separate phase without mixing or deletion");
            }
        }
        require(maximum<1000,"closed branching queues do not multiply microscopic interfaces: "+maximum);
        var q=new FluidQueue<String>(String::equals);
        var packet=List.of(new FluidQueue.Part<>("a",3d),new FluidQueue.Part<>("b",4d),new FluidQueue.Part<>("c",5d));
        for (int first:new int[]{0,1,2,Integer.MAX_VALUE,Integer.MIN_VALUE}) {
            var branches=q.distributeBounded(packet,new double[]{1,2,3},first);
            for(int branch=0;branch<3;branch++) {
                near(2*(branch+1),branches.get(branch).stream().mapToDouble(FluidQueue.Part::amount).sum(),1e-12,
                    "contiguous branch windows retain each prescribed flow volume");
                int previous=-1;
                for(var part:branches.get(branch)) {
                    int order=part.identity().charAt(0)-'a'; require(order>=previous,"branch parcels cannot overtake their input queue"); previous=order;
                }
            }
        }
        System.out.println("Queued circulation: 5000 updates, max " + maximum + " parcels, separate phase ledgers conserved");
    }
    private static void hydraulic() {
        for (double rpm : new double[]{-256, -64, 0, 64, 256}) for (double height : new double[]{-256, 0, 256}) {
            var cell = new QueuedFluidSolver.Cell(100, height, 1000, 0, rpm, 0);
            var links = List.of(new QueuedFluidSolver.Link(0, 1, .25, height - .5, -1, 0, true),
                new QueuedFluidSolver.Link(0, 2, .25, height + .5, 1, 0, true));
            var open = new QueuedFluidSolver.Boundary[]{new QueuedFluidSolver.Boundary(0, true, true, 1000), new QueuedFluidSolver.Boundary(0, true, true, 1000)};
            var moved = QueuedFluidSolver.sample(List.of(cell), links, open, new double[2], RotationMath.TICK_MINUTES, Long.MAX_VALUE, (e, f) -> true);
            near(0, moved.flow()[0] + moved.flow()[1], 1e-11, "full air cell has zero net volume flux");
            require(rpm == 0 ? moved.flow()[1] == 0 : moved.flow()[1] > 0, "empty-pipe air pump follows its facing at either altitude and shaft sign");
            require(moved.solves() <= 3 && moved.solves() > 0, "one shared bounded pressure solve");
            require(moved.torque()[0] * rpm <= 1e-10, "pumping air accounts for the shaft reaction");
            var closed = new QueuedFluidSolver.Boundary[]{new QueuedFluidSolver.Boundary(0, false, false, 1000), new QueuedFluidSolver.Boundary(0, false, false, 1000)};
            var held = QueuedFluidSolver.sample(List.of(cell), links, closed, new double[2], RotationMath.TICK_MINUTES, Long.MAX_VALUE, (e, f) -> true);
            near(0, held.flow()[0], 1e-12, "sealed incompressible air cannot leave"); near(0, held.torque()[0], 1e-12, "ideal zero-flow pressure has zero continuous power");
            near(16 * rpm * rpm / (64 * 64), held.head()[0], 1e-10, "pump pressure remains available at shutoff");
            var deferred = QueuedFluidSolver.sample(List.of(cell), links, open, new double[2], RotationMath.TICK_MINUTES, 0, (e, f) -> true);
            require(deferred.solves() == 0 && deferred.flow()[0] == 0, "an expired work budget never advances inventory");
        }
        for (int count : new int[]{2, 16, 64, 256, 1024}) {
            var cells = new java.util.ArrayList<QueuedFluidSolver.Cell>(); var links = new java.util.ArrayList<QueuedFluidSolver.Link>();
            for (int n = 0; n < count; n++) {
                cells.add(new QueuedFluidSolver.Cell(100, n, 1000, 0, 0, 0));
                if (n > 0) links.add(new QueuedFluidSolver.Link(n - 1, n, 1, n - .5, 0, 0, true));
            }
            links.add(new QueuedFluidSolver.Link(0, count, .5, -.5, 0, 0, true));
            links.add(new QueuedFluidSolver.Link(count - 1, count + 1, .5, count - .5, 0, 0, true));
            var boundaries = new QueuedFluidSolver.Boundary[]{new QueuedFluidSolver.Boundary(0, true, true, 1000), new QueuedFluidSolver.Boundary(0, true, true, 1000)};
            var noGravity = QueuedFluidSolver.sample(cells, links, boundaries, new double[links.size()], RotationMath.TICK_MINUTES, Long.MAX_VALUE, (e, f) -> true);
            for (double flow : noGravity.flow()) near(0, flow, 0, "gas alone does not fall in a long vertical pipe");
            double common = IdealFluidSolver.pressureForce(987654.3);
            for(int b=0;b<boundaries.length;b++) boundaries[b]=new QueuedFluidSolver.Boundary(common,true,true,1000);
            var equalHeads=QueuedFluidSolver.sample(cells,links,boundaries,new double[links.size()],RotationMath.TICK_MINUTES,Long.MAX_VALUE,(e,f)->true);
            for(double flow:equalHeads.flow()) near(0,flow,0,"a common nonzero pressure cannot start motion or trigger a whole world bucket");
            boundaries[1]=new QueuedFluidSolver.Boundary(0,true,true,1000);
            // Apply a known static boundary gradient. Continuity makes every
            // segment carry the same volume, independently of individual phases.
            boundaries[0] = new QueuedFluidSolver.Boundary(IdealFluidSolver.pressureForce(1000), true, true, 1000);
            var driven = QueuedFluidSolver.sample(cells, links, boundaries, new double[links.size()], RotationMath.TICK_MINUTES, Long.MAX_VALUE, (e, f) -> true);
            require(driven.solves() > 0 && driven.flow()[0] > 0, "a long empty pipe transmits a positive pressure gradient");
            for (int e = 1; e < count - 1; e++) near(driven.flow()[0], driven.flow()[e], 1e-9, "all full cells share one through flow");
            var blocked = new java.util.ArrayList<>(links);
            var valve = blocked.get((count - 1) / 2);
            blocked.set((count - 1) / 2, new QueuedFluidSolver.Link(valve.from(), valve.to(), valve.length(), valve.faceHeight(), 0, 0, false));
            double[] previous = new double[links.size()]; java.util.Arrays.fill(previous, 50);
            var shut = QueuedFluidSolver.sample(cells, blocked, boundaries, previous, RotationMath.TICK_MINUTES, Long.MAX_VALUE, (e, f) -> true);
            require(shut.solves() > 0, "closing a moving full pipe completes a bounded update");
            for (double flow : shut.flow()) near(0, flow, 0, "incompressible sealed branches stop old motion exactly");
        }
    }
}
