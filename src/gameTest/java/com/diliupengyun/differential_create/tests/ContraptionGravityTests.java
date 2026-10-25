package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.mixin.LinearActuatorAccessor;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.gantry.GantryCarriageBlockEntity;
import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

final class ContraptionGravityTests {
    static void bearings(GameTestHelper h) {
        var horizontal = new BlockPos(7, 12, 7); var vertical = new BlockPos(20, 12, 20); var balanced = new BlockPos(20, 12, 7);
        h.setBlock(horizontal, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        h.setBlock(vertical, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        h.setBlock(balanced, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        for (int i = -2; i <= 2; i++) h.setBlock(balanced.east().south(i), Blocks.SLIME_BLOCK);
        for (int i = 0; i < 3; i++) {
            h.setBlock(horizontal.east().south(i), Blocks.SLIME_BLOCK);
            h.setBlock(vertical.above().east(i), Blocks.SLIME_BLOCK);
        }
        h.runAfterDelay(5, () -> {
            MechanicalBearingBlockEntity a = h.getBlockEntity(horizontal), b = h.getBlockEntity(vertical), c = h.getBlockEntity(balanced);
            a.assemble(); b.assemble(); c.assemble(); NetworkTopology.mark(a); NetworkTopology.mark(b); NetworkTopology.mark(c); NetworkTopology.flush();
            h.assertTrue(ContraptionGravity.capture(a).torque(0, 0) > 0, "eccentric horizontal bearing has a world-down torque");
            h.assertTrue(!ContraptionGravity.capture(b).active(), "vertical axis has no gravitational drive");
            h.assertTrue(!ContraptionGravity.capture(c).active(), "balanced horizontal structure has no gravitational drive");
            double y = centreY(a);
            h.runAfterDelay(20, () -> {
                h.assertTrue(a.isRunning() && a.getMovedContraption() != null, "unpowered bearing remains assembled");
                h.assertTrue(centreY(a) < y - .01, "unpowered eccentric mass actually descends");
                h.assertTrue(Math.abs(NetworkDynamics.preciseSpeed(a)) > .1, "gravity starts a source-free shaft");
                h.assertTrue(NetworkDynamics.preciseSpeed(b) == 0, "vertical axis remains at rest");
                h.assertTrue(NetworkDynamics.preciseSpeed(c) == 0, "balanced structure remains at rest");
                a.disassemble(); b.disassemble(); c.disassemble(); h.succeed();
            });
        });
    }
    private static double centreY(MechanicalBearingBlockEntity bearing) {
        var root = bearing.getMovedContraption(); var shape = ContraptionPhysics.shape(root.getContraption());
        return root.toGlobalVector(new Vec3(.5 + shape.centre(0), .5 + shape.centre(1), .5 + shape.centre(2)), 1).y;
    }
    static void pulley(GameTestHelper h) {
        var pos = new BlockPos(12, 20, 12);
        for (int y = 6; y <= 20; y++) for (int x = 12; x <= 13; x++) h.setBlock(new BlockPos(x, y, 12), Blocks.AIR);
        h.setBlock(pos, AllBlocks.ROPE_PULLEY.getDefaultState());
        h.setBlock(pos.below(), Blocks.SLIME_BLOCK); h.setBlock(pos.below().east(), Blocks.OAK_PLANKS);
        h.runAfterDelay(5, () -> {
            LinearActuatorBlockEntity be = h.getBlockEntity(pos);
            NetworkTopology.mark(be); NetworkTopology.flush(); ElasticKinetics.state(be).physicalSpeed = 16;
            try { ((LinearActuatorAccessor) be).differentialcreate$assemble(); }
            catch (Exception e) { throw new AssertionError("pulley assembly", e); }
            h.assertTrue(be.movedContraption != null, "native rope pulley assembled its payload");
            ElasticKinetics.state(be).physicalSpeed = 0;
            double start = be.movedContraption.getY();
            h.runAfterDelay(20, () -> {
                h.assertTrue(be.running && be.movedContraption != null, "gravity does not trigger zero-speed placement");
                h.assertTrue(be.movedContraption.getY() < start - .01 && be.offset > .01, "payload and rope offset descend together");
                h.assertTrue(NetworkDynamics.preciseSpeed(be) > 0, "descending weight backdrives pulley shaft");
                var feed = ActorFeed.of(be);
                h.assertTrue(Math.abs(ElasticKinetics.elasticEnergy(be.getOrCreateNetwork()).of(be) - feed.elasticEnergy()) < 1e-12,
                    "the independent structural contact is included once in elastic energy readouts");
                var tag = new net.minecraft.nbt.CompoundTag(); feed.write(tag);
                var restored = new ActorFeed(); restored.read(tag);
                h.assertTrue(restored.speed == feed.speed && restored.twist == feed.twist
                    && restored.elasticEnergy() == feed.elasticEnergy(), "output speed and elastic storage survive save/load");
                be.disassemble(); h.succeed();
            });
        });
    }
    static void gantry(GameTestHelper h) {
        var pos = new BlockPos(12, 20, 12);
        for (int y = 10; y <= 28; y++) {
            for (int x = 12; x <= 15; x++) h.setBlock(new BlockPos(x, y, 12), Blocks.AIR);
            h.setBlock(new BlockPos(12, y, 12), AllBlocks.GANTRY_SHAFT.getDefaultState().setValue(GantryShaftBlock.FACING, Direction.UP));
        }
        h.setBlock(pos.east(2), Blocks.SLIME_BLOCK);
        h.runAfterDelay(5, () -> {
            KineticBlockEntity shaft = h.getBlockEntity(pos);
            NetworkTopology.mark(shaft); NetworkTopology.flush(); ElasticKinetics.state(shaft).physicalSpeed = 16;
            h.setBlock(pos.east(), AllBlocks.GANTRY_CARRIAGE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
            GantryCarriageBlockEntity carriage = h.getBlockEntity(pos.east()); carriage.queueAssembly(); carriage.tick();
            var entity = java.util.stream.StreamSupport.stream(h.getLevel().getAllEntities().spliterator(), false)
                .filter(e -> e instanceof GantryContraptionEntity).map(e -> (GantryContraptionEntity) e)
                .filter(e -> e.getAnchorVec().distanceTo(Vec3.atLowerCornerOf(h.absolutePos(pos.east()))) < 1).findFirst().orElseThrow();
            entity.tick(); ContraptionPhysics.bindGantry(entity, Double.NaN);
            for (var be : shaft.getOrCreateNetwork().members.keySet()) ElasticKinetics.state(be).physicalSpeed = 0;
            double start = entity.getY();
            h.runAfterDelay(14, () -> {
                h.assertFalse(entity.isRemoved(), "source-free vertical gantry stays assembled while falling");
                h.assertTrue(entity.getY() < start - .1, "vertical gantry descends under gravity");
                var owner = ContraptionPhysics.gantryShaft(entity);
                h.assertTrue(owner != null && NetworkDynamics.preciseSpeed(owner) > 0, "current supporting shaft receives gravitational motion");
                h.getLevel().setBlock(owner.getBlockPos(), owner.getBlockState().setValue(GantryShaftBlock.POWERED, true), 2);
                entity.tick();
                h.assertTrue(entity.isRemoved(), "redstone still dismantles immediately using native grid placement");
                h.succeed();
            });
        });
    }
    static void elevator(GameTestHelper h) {
        var pos = new BlockPos(12, 20, 12);
        h.setBlock(pos, AllBlocks.ELEVATOR_PULLEY.getDefaultState());
        h.setBlock(pos.below(), Blocks.SLIME_BLOCK); h.setBlock(pos.below().east(), Blocks.OAK_PLANKS);
        h.setBlock(pos.below().west(), AllBlocks.REDSTONE_CONTACT.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH));
        h.runAfterDelay(5, () -> {
            LinearActuatorBlockEntity be = h.getBlockEntity(pos);
            NetworkTopology.mark(be); NetworkTopology.flush(); ElasticKinetics.state(be).physicalSpeed = 64;
            try { ((LinearActuatorAccessor) be).differentialcreate$assemble(); }
            catch (Exception e) { throw new AssertionError("elevator assembly", e); }
            h.assertTrue(be.movedContraption != null, "native elevator assembled");
            var contraption = (com.simibubi.create.content.contraptions.elevator.ElevatorContraption) be.movedContraption.getContraption();
            var column = com.simibubi.create.content.contraptions.elevator.ElevatorColumn.get(h.getLevel(), contraption.getGlobalColumn());
            int top = column.getTargetedYLevel();
            column.target(top - 3);
            h.runAfterDelay(80, () -> {
                h.assertTrue(be.running && be.offset > 2.9 && be.offset <= 3.0001, "elevator starts, descends, and stops at its native target: " + be.offset);
                var network = (INetworkDynamics) be.getOrCreateNetwork();
                network.differentialcreate$setSpeed(64 / network.differentialcreate$getRatios().get(be));
                column.target(top);
                h.runAfterDelay(80, () -> {
                    h.assertTrue(be.running && be.offset >= 0 && be.offset < .1, "elevator reverses and lifts against gravity using supplied shaft energy: " + be.offset);
                    float held = be.offset;
                    h.runAfterDelay(20, () -> {
                        h.assertTrue(Math.abs(be.offset - held) < 1e-5, "native arrival support holds the payload");
                        be.disassemble(); h.succeed();
                    });
                });
            });
        });
    }
}
