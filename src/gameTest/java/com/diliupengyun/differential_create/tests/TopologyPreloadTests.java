package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.diliupengyun.differential_create.kinetics.ElasticKinetics;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.diliupengyun.differential_create.kinetics.PhysicsTuning;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises saved contacts through the real world rebuild and fracture callbacks. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class TopologyPreloadTests {
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        add(tests, "jam", FreeTopologyTests::overconstrainedLoop);
        add(tests, "tree_speed", FreeTopologyTests::consistentTree);
        add(tests, "balanced", h -> cycle(h, "balanced"));
        add(tests, "relaxed", h -> cycle(h, "relaxed"));
        add(tests, "overload", h -> cycle(h, "overload"));
        add(tests, "redistribution", h -> cycle(h, "redistribution"));
        add(tests, "tree_energy", h -> line(h, "tree"));
        add(tests, "tree_fracture", h -> line(h, "fracture"));
        add(tests, "inertial_boundary", h -> line(h, "inertia"));
        add(tests, "clutch_boundary", h -> line(h, "clutch"));
        return tests;
    }
    private static void add(List<TestFunction> tests, String suffix, Consumer<GameTestHelper> body) {
        String name = "topology_" + suffix;
        if (!name.matches(System.getProperty("differentialcreate.motion.select", ".*"))) return;
        tests.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 160, 0, true, h -> {
            KineticPhysicsTests.forceImpactChunks(h); body.accept(h);
        }));
    }
    private static KineticBlockEntity place(GameTestHelper h, BlockPos pos, BlockState block) {
        h.setBlock(pos, block.setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        KineticBlockEntity be = h.getBlockEntity(pos); NetworkTopology.mark(be); return be;
    }
    private static List<ElasticKinetics.Connection> contacts(KineticBlockEntity be) {
        return ((INetworkDynamics) be.getOrCreateNetwork()).differentialcreate$data().connections;
    }
    private static void rebuild(KineticBlockEntity be) { NetworkTopology.mark(be); NetworkTopology.flush(); }
    private static void line(GameTestHelper h, String mode) {
        var bottom = place(h, new BlockPos(5,3,5), AllBlocks.SHAFT.getDefaultState());
        var middle = place(h, new BlockPos(5,4,5), mode.equals("clutch") ? AllBlocks.CLUTCH.getDefaultState()
            : mode.equals("inertia") ? AllBlocks.FLYWHEEL.getDefaultState() : AllBlocks.SHAFT.getDefaultState());
        var top = place(h, new BlockPos(5,5,5), AllBlocks.SHAFT.getDefaultState());
        h.runAfterDelay(5, () -> {
            rebuild(bottom);
            var edges = contacts(bottom);
            h.assertTrue(edges.size() == 2, "line must have two physical contacts");
            boolean fracture = mode.equals("fracture"), boundary = mode.equals("inertia") || mode.equals("clutch");
            for (int e = 0; e < edges.size(); e++) {
                var c = edges.get(e);
                c.twist = (fracture && e == 0 ? c.strength() * 1.1 : 1000) / c.stiffness(); c.save();
            }
            double before = ElasticKinetics.elasticEnergy(bottom.getOrCreateNetwork()).total();
            var audit = NetworkTopology.energyAudit(h.getLevel());
            ElasticKinetics.state(bottom).physicalSpeed = 40;
            ElasticKinetics.state(middle).physicalSpeed = 50;
            ElasticKinetics.state(top).physicalSpeed = 60;
            var broken = fracture ? edges.get(0).weakest() : null;
            Map<KineticBlockEntity, Double> inherited = Map.of(bottom,40d,middle,50d,top,60d);
            rebuild(bottom);
            if (fracture) {
                h.assertTrue(broken.isRemoved(), "overloaded preload must break before it can be erased");
                for (var be : List.of(bottom,middle,top)) {
                    if (be.isRemoved()) continue;
                    double expected = be.getOrCreateNetwork().members.keySet().stream().mapToDouble(inherited::get).average().orElseThrow();
                    near(h, expected, ElasticKinetics.state(be).physicalSpeed, 1e-8, "fragment inherited speed");
                }
            } else if (boundary) {
                near(h, before, ElasticKinetics.elasticEnergy(bottom.getOrCreateNetwork()).total(), 1e-10, "boundary preload retained");
                near(h, 40, ElasticKinetics.state(bottom).physicalSpeed, 0, "boundary local speed retained");
            } else {
                near(h, 0, ElasticKinetics.elasticEnergy(bottom.getOrCreateNetwork()).total(), 0, "free tree relaxed");
                near(h, 50, ElasticKinetics.state(top).physicalSpeed, 1e-8, "tree average");
            }
            double after = 0;
            var networks = new java.util.HashSet<com.simibubi.create.content.kinetics.KineticNetwork>();
            for (var be : List.of(bottom,middle,top)) if (!be.isRemoved()) networks.add(be.getOrCreateNetwork());
            for (var network : networks) after += ElasticKinetics.elasticEnergy(network).total();
            var done = NetworkTopology.energyAudit(h.getLevel());
            near(h, before, after + done.projectionLoss() - audit.projectionLoss() + done.fractureLoss() - audit.fractureLoss(),
                1e-7, "line rebuild closes energy ledger");
            h.succeed();
        });
    }
    private static void cycle(GameTestHelper h, String mode) {
        BlockPos[] positions = {new BlockPos(5,3,5), new BlockPos(6,3,5), new BlockPos(6,3,6), new BlockPos(5,3,6)};
        var members = new ArrayList<KineticBlockEntity>();
        boolean redistribution = mode.equals("redistribution"), overload = mode.equals("overload");
        for (int i = 0; i < 4; i++) members.add(place(h, positions[i], redistribution && i > 0
            ? AllBlocks.BRASS_ENCASED_COGWHEEL.getDefaultState() : AllBlocks.COGWHEEL.getDefaultState()));
        h.runAfterDelay(5, () -> {
            rebuild(members.get(0));
            var edges = contacts(members.get(0));
            h.assertTrue(edges.size() == 4, "square gears must form exactly four contacts, got " + edges.size());
            Map<KineticBlockEntity, Double> ratios = new HashMap<>(), displacement = new HashMap<>();
            for (int i = 0; i < 4; i++) {
                ratios.put(members.get(i), i % 2 == 0 ? 1d : -1d);
                displacement.put(members.get(i), mode.equals("relaxed") ? (i - 1.5) * 1e-5 : 0);
                ElasticKinetics.state(members.get(i)).physicalSpeed = ratios.get(members.get(i)) * 37;
            }
            Map<String, Double> expected = new HashMap<>();
            for (var c : edges) {
                int a = members.indexOf(c.a), b = members.indexOf(c.b);
                double direction = (a + 1) % 4 == b ? 1 : -1;
                double flow = overload ? 1.1 * PhysicsTuning.ANDESITE_STRENGTH : 1000;
                if (redistribution) flow = a == 0 || b == 0 ? 0 : 2.5 * PhysicsTuning.ANDESITE_STRENGTH;
                double selfStress = direction * flow / ratios.get(c.b) / c.stiffness();
                c.twist = selfStress + c.ratio * displacement.get(c.a) - displacement.get(c.b); c.save();
                expected.put(key(c), selfStress);
            }
            double before = ElasticKinetics.elasticEnergy(members.get(0).getOrCreateNetwork()).total();
            var audit = NetworkTopology.energyAudit(h.getLevel());
            rebuild(members.get(0));
            if (overload || redistribution) {
                h.assertTrue(members.stream().anyMatch(KineticBlockEntity::isRemoved), "strength event did not remove a member");
                if (redistribution) h.assertTrue(members.get(0).isRemoved(), "redistribution must break the weaker andesite gear");
            } else {
                for (var c : contacts(members.get(0))) near(h, expected.get(key(c)), c.twist, 1e-12, "self-stress survives rebuild");
                var first = NetworkTopology.energyAudit(h.getLevel());
                for (int repeat = 0; repeat < 8; repeat++) rebuild(members.get(repeat % 4));
                near(h, first.projectionLoss(), NetworkTopology.energyAudit(h.getLevel()).projectionLoss(), 0, "repeat rebuild loses no more energy");
                // Exercise the subsequent physical step as well as the rebuild.
                var trial = ElasticKinetics.snapshot(List.of(members.get(0).getOrCreateNetwork()), Map.of()).sample(1e-6);
                trial.commit();
                for (var be : members) near(h, ratios.get(be) * 37, ElasticKinetics.state(be).physicalSpeed, 1e-5, "balanced cycle keeps its free mode");
            }
            double after = 0;
            var networks = new java.util.HashSet<com.simibubi.create.content.kinetics.KineticNetwork>();
            for (var be : members) if (!be.isRemoved()) networks.add(be.getOrCreateNetwork());
            for (var network : networks) after += ElasticKinetics.elasticEnergy(network).total();
            var done = NetworkTopology.energyAudit(h.getLevel());
            near(h, before, after + done.projectionLoss() - audit.projectionLoss() + done.fractureLoss() - audit.fractureLoss(),
                1e-7, "cycle rebuild closes energy ledger");
            h.succeed();
        });
    }
    private static String key(ElasticKinetics.Connection c) { return c.a.getBlockPos() + "/" + c.b.getBlockPos(); }
    private static void near(GameTestHelper h, double expected, double actual, double tolerance, String label) {
        h.assertTrue(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
            label + ": expected " + expected + ", got " + actual);
    }
}
