package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.gantry.GantryCarriageBlockEntity;
import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Observe normal entity ticks: the native spawn delay cannot accumulate invisible falling work. */
final class GantryStartupTests {
    static void run(GameTestHelper h) {
        var pos = new BlockPos(12, 20, 12);
        for (int y = 5; y <= 24; y++) h.setBlock(new BlockPos(12, y, 12),
            AllBlocks.GANTRY_SHAFT.getDefaultState().setValue(GantryShaftBlock.FACING, Direction.UP));
        h.setBlock(pos.east(2), Blocks.SLIME_BLOCK);
        GantryContraptionEntity[] root = {null};
        double[] initial = {0}, previous = {0}; int[] moving = {0};
        h.onEachTick(() -> {
            var entity = root[0]; if (entity == null) return;
            h.assertFalse(entity.isRemoved(), "startup retained its real gantry entity");
            double drop = initial[0] - entity.getY(), step = previous[0] - entity.getY();
            previous[0] = entity.getY();
            if (step > 1e-7 && moving[0]++ == 0)
                h.assertTrue(step < .018, "first falling step must contain one tick of gravity, not hidden spawn-delay work: " + step);
            if (moving[0] == 10) {
                h.assertTrue(drop > .5, "released carriage accelerates through ordinary server ticks");
                entity.disassemble(); h.succeed(); root[0] = null;
            }
        });
        h.runAfterDelay(5, () -> {
            KineticBlockEntity shaft = h.getBlockEntity(pos);
            NetworkTopology.mark(shaft); NetworkTopology.flush(); ElasticKinetics.state(shaft).physicalSpeed = 16;
            h.setBlock(pos.east(), AllBlocks.GANTRY_CARRIAGE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
            GantryCarriageBlockEntity carriage = h.getBlockEntity(pos.east()); carriage.queueAssembly(); carriage.tick();
            root[0] = java.util.stream.StreamSupport.stream(h.getLevel().getAllEntities().spliterator(), false)
                .filter(e -> e instanceof GantryContraptionEntity).map(e -> (GantryContraptionEntity) e)
                .filter(e -> e.getAnchorVec().distanceTo(Vec3.atLowerCornerOf(h.absolutePos(pos.east()))) < 1).findFirst().orElseThrow();
            ContraptionPhysics.bindGantry(root[0], Double.NaN);
            for (var member : shaft.getOrCreateNetwork().members.keySet()) {
                ElasticKinetics.state(member).physicalSpeed = 0;
                var feed = ActorFeed.of(member); feed.speed = feed.angle = feed.twist = 0;
            }
            initial[0] = previous[0] = root[0].getY();
        });
    }
}
