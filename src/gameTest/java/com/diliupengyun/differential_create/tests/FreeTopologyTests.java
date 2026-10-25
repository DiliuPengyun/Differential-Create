package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.ElasticKinetics;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Zero-inertia free components: inconsistent rigid cycles jam, consistent trees project. */
final class FreeTopologyTests {
    private FreeTopologyTests() {}

    static void overconstrainedLoop(GameTestHelper h) {
        BlockPos a1Pos = new BlockPos(2, 3, 2);
        BlockPos a2Pos = new BlockPos(2, 4, 2);
        BlockPos b1Pos = new BlockPos(3, 3, 1);
        BlockPos b2Pos = new BlockPos(3, 4, 1);
        place(h, a1Pos, AllBlocks.LARGE_COGWHEEL.getDefaultState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        place(h, a2Pos, AllBlocks.COGWHEEL.getDefaultState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        place(h, b1Pos, AllBlocks.COGWHEEL.getDefaultState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        place(h, b2Pos, AllBlocks.LARGE_COGWHEEL.getDefaultState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        KineticBlockEntity a1 = h.getBlockEntity(a1Pos);
        KineticBlockEntity a2 = h.getBlockEntity(a2Pos);
        KineticBlockEntity b1 = h.getBlockEntity(b1Pos);
        KineticBlockEntity b2 = h.getBlockEntity(b2Pos);
        h.runAfterDelay(5, () -> {
            // Two coaxial pairs and two reciprocal meshes: the only rigid
            // solution is zero, so an anchored free component must jam.
            ElasticKinetics.state(a1).physicalSpeed = 40;
            ElasticKinetics.state(a2).physicalSpeed = 50;
            ElasticKinetics.state(b1).physicalSpeed = -50;
            ElasticKinetics.state(b2).physicalSpeed = -35;
            NetworkTopology.mark(a1);
            NetworkTopology.mark(a2);
            NetworkTopology.mark(b1);
            NetworkTopology.mark(b2);
            NetworkTopology.flush();
            if (a1.getOrCreateNetwork() != b2.getOrCreateNetwork())
                h.fail("the four gears did not form one kinetic network");
            var network = (INetworkDynamics) a1.getOrCreateNetwork();
            h.assertTrue(network.differentialcreate$data().jammed,
                "inconsistent rigid loop must be marked jammed");
            for (KineticBlockEntity be : new KineticBlockEntity[] { a1, a2, b1, b2 })
                h.assertTrue(Math.abs(be.getSpeed()) < 0.01,
                    "jammed loop member still rotates at " + be.getSpeed() + " RPM");
            h.succeed();
        });
    }

    static void consistentTree(GameTestHelper h) {
        BlockPos largePos = new BlockPos(8, 3, 2);
        BlockPos smallPos = new BlockPos(8, 4, 2);
        place(h, largePos, AllBlocks.LARGE_COGWHEEL.getDefaultState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        place(h, smallPos, AllBlocks.COGWHEEL.getDefaultState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        KineticBlockEntity large = h.getBlockEntity(largePos);
        KineticBlockEntity small = h.getBlockEntity(smallPos);
        h.runAfterDelay(5, () -> {
            ElasticKinetics.state(large).physicalSpeed = 40;
            ElasticKinetics.state(small).physicalSpeed = 50;
            NetworkTopology.mark(large);
            NetworkTopology.mark(small);
            NetworkTopology.flush();
            if (large.getOrCreateNetwork() != small.getOrCreateNetwork())
                h.fail("the coaxial pair did not share a network (ratios "
                    + NetworkTopology.connection(large, small) + "/"
                    + NetworkTopology.connection(small, large) + ")");
            var network = (INetworkDynamics) large.getOrCreateNetwork();
            Float rLarge = network.differentialcreate$getRatios().get(large);
            Float rSmall = network.differentialcreate$getRatios().get(small);
            if (rLarge == null || rSmall == null || rLarge == 0 || rSmall == 0)
                h.fail("the coaxial pair has no usable ratios");
            double common = (rLarge * 40 + rSmall * 50) / (rLarge * rLarge + rSmall * rSmall);
            near(h, rLarge * common, large.getSpeed(), 1e-4, "lower gear projected speed");
            near(h, rSmall * common, small.getSpeed(), 1e-4, "upper gear projected speed");
            h.assertFalse(network.differentialcreate$data().jammed,
                "a consistent tree must not be marked jammed");
            h.succeed();
        });
    }

    private static void place(GameTestHelper h, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        h.setBlock(pos, state);
        NetworkTopology.mark(h.getBlockEntity(pos));
    }

    private static void near(GameTestHelper h, double expected, double actual, double tolerance, String label) {
        h.assertTrue(Math.abs(expected - actual) <= tolerance,
            label + ": expected " + expected + ", got " + actual);
    }
}
