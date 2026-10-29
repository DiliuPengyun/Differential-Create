package com.diliupengyun.differential_create.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.mixin.LinearActuatorAccessor;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.IControlContraption.MovementMode;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** A stationary native collision must not keep converting fictitious descent into shaft energy. */
final class GravityCollisionTests {
    enum Check { REMOVE_SUPPORT, REVERSE, RELOAD, PLACE, RETRACT }
    static void run(GameTestHelper h, boolean piston) { run(h, piston, Check.REMOVE_SUPPORT); }
    static void run(GameTestHelper h, boolean piston, Check check) {
        var pos = new BlockPos(12, 20, 12);
        h.setBlock(pos, piston ? AllBlocks.MECHANICAL_PISTON.getDefaultState().setValue(BlockStateProperties.FACING, Direction.DOWN)
            : AllBlocks.ROPE_PULLEY.getDefaultState());
        if (piston) for (int y = 1; y <= 8; y++)
            h.setBlock(pos.above(y), AllBlocks.PISTON_EXTENSION_POLE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.DOWN));
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) {
            h.setBlock(pos.offset(x, -1, z), Blocks.SLIME_BLOCK);
            h.setBlock(pos.offset(x, -4, z), check == Check.RETRACT ? Blocks.AIR : Blocks.STONE);
        }
        var rows = new ArrayList<String>(); rows.add("tick,offset_m,shaft_rpm,output_rpm,waiting,removed");
        LinearActuatorBlockEntity[] controller = {null}; long[] start = {0};
        double[] before = {0, 0};
        h.onEachTick(() -> {
            var be = controller[0]; if (be == null) return;
            long tick = h.getLevel().getGameTime() - start[0];
            rows.add(String.format(Locale.ROOT, "%d,%.12f,%.12f,%.12f,%b,%b", tick, (double) be.offset,
                NetworkDynamics.preciseSpeed(be), ActorFeed.of(be).speed, ((LinearActuatorAccessor) be).differentialcreate$waiting(),
                be.movedContraption == null || be.movedContraption.isRemoved()));
            if (tick == 100) { before[0] = be.offset; before[1] = NetworkDynamics.preciseSpeed(be); }
            if (check == Check.RETRACT && tick == 45) {
                h.assertTrue(be.movedContraption != null && !be.movedContraption.isRemoved() && be.offset > .05,
                    "gravity leaves the retracted stop without a new speed command: offset=" + be.offset
                        + ", waiting=" + ((LinearActuatorAccessor) be).differentialcreate$waiting()
                        + ", shaft=" + NetworkDynamics.preciseSpeed(be) + ", output=" + ActorFeed.of(be).speed);
                be.disassemble(); h.succeed();
            }
        });
        h.runAfterDelay(5, () -> {
            LinearActuatorBlockEntity be = h.getBlockEntity(pos); controller[0] = be; start[0] = h.getLevel().getGameTime();
            ((LinearActuatorAccessor) be).differentialcreate$movementMode().setValue(
                (check == Check.PLACE ? MovementMode.MOVE_PLACE : MovementMode.MOVE_NEVER_PLACE).ordinal());
            NetworkTopology.mark(be); NetworkTopology.flush(); ElasticKinetics.state(be).physicalSpeed = 16;
            try { ((LinearActuatorAccessor) be).differentialcreate$assemble(); }
            catch (Exception failure) { throw new AssertionError("assemble falling structure", failure); }
            var root = be.movedContraption; h.assertTrue(root != null, "native controller assembled payload");
            ElasticKinetics.state(be).physicalSpeed = 0;
            if (check == Check.RETRACT) {
                // Reproduce the reported save state: fully retracted, native wait,
                // stopped body and an unpowered rotor coasting toward the blocked side.
                ActorFeed.enabled(be); ActorFeed.of(be).speed = 0;
                ElasticKinetics.state(be).physicalSpeed = -32;
                ((LinearActuatorAccessor) be).differentialcreate$setWaiting(true);
                h.assertTrue(be.offset == 0 && ((LinearActuatorAccessor) be).differentialcreate$waiting()
                    && NetworkDynamics.preciseSpeed(be) == -32, "fixture has the native endpoint wait and independent coasting rotor");
                return;
            }
            if (check == Check.PLACE) {
                h.succeedWhen(() -> {
                    h.assertTrue(root.isRemoved(), "placement mode dismantles at the real collision");
                    h.assertTrue(h.getBlockState(pos.offset(1, -3, 1)).is(Blocks.SLIME_BLOCK), "native placement returns the payload above the floor");
                });
                return;
            }
            h.runAfterDelay(150, () -> {
                try {
                    Path dir = Path.of(System.getProperty("differentialcreate.gravity.report", "build/gravity-measurement/samples"))
                        .toAbsolutePath().getParent().resolve("collision");
                    Files.createDirectories(dir); Files.write(dir.resolve((piston ? "piston" : "pulley") + "-" + check.name().toLowerCase(Locale.ROOT) + ".csv"), rows);
                } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                h.assertTrue(be.running && be.movedContraption == root && !root.isRemoved(), "collision retains the assembled structure");
                h.assertTrue(be.offset > .5 && Math.abs(be.offset - before[0]) < 1e-6, "payload fell and is supported by the obstacle");
                h.assertTrue(Math.abs(ActorFeed.of(be).speed) < 1e-8, "stationary collision has zero physical output RPM: " + ActorFeed.of(be).speed);
                h.assertTrue(Math.abs(NetworkDynamics.preciseSpeed(be)) <= Math.abs(before[1]) + 1e-5,
                    "supported gravity cannot keep accelerating the input: before=" + before[1] + ", after=" + NetworkDynamics.preciseSpeed(be));
                double held = be.offset;
                if (check == Check.REVERSE) {
                    // Supply energy only to the input, leaving the stopped output untouched.
                    ElasticKinetics.state(be).physicalSpeed = -64;
                    h.runAfterDelay(12, () -> {
                        h.assertTrue(be.movedContraption == root && be.offset < held - .2, "reverse input lifts the retained body away from the obstacle");
                        h.assertTrue(h.getBlockState(pos.offset(0, -4, 0)).is(Blocks.STONE), "reversal works while the obstacle remains");
                        be.disassemble(); h.succeed();
                    });
                } else if (check == Check.RELOAD) {
                    var entityTag = root.saveWithoutId(new CompoundTag()); entityTag.putString("id", EntityType.getKey(root.getType()).toString());
                    root.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                    var blockTag = be.saveWithFullMetadata(h.getLevel().registryAccess());
                    h.runAfterDelay(2, () -> {
                        var restored = (LinearActuatorBlockEntity) BlockEntity.loadStatic(be.getBlockPos(), be.getBlockState(), blockTag, h.getLevel().registryAccess());
                        h.getLevel().setBlockEntity(restored); NetworkTopology.mark(restored); NetworkTopology.flush();
                        var loaded = (ControlledContraptionEntity) EntityType.loadEntityRecursive(entityTag, h.getLevel(), e -> e);
                        h.assertTrue(loaded != null && h.getLevel().addFreshEntity(loaded), "supported structure reloads as a fresh entity");
                        restored.attach(loaded); controller[0] = restored;
                        double speed = NetworkDynamics.preciseSpeed(restored);
                        boolean[] supported = {true};
                        h.onEachTick(() -> {
                            if (!supported[0]) return;
                            h.assertTrue(Math.abs(restored.offset - held) < 1e-6 && Math.abs(ActorFeed.of(restored).speed) < 1e-8,
                                "reloaded collision has no displacement or hidden output speed: tick=" + (h.getLevel().getGameTime() - start[0])
                                    + ", offset=" + restored.offset + ", output=" + ActorFeed.of(restored).speed
                                    + ", shaft=" + NetworkDynamics.preciseSpeed(restored) + ", position=" + loaded.position()
                                    + ", saved contact=" + entityTag.getCompound("DCLinearContact"));
                            h.assertTrue(Math.abs(NetworkDynamics.preciseSpeed(restored)) <= Math.abs(speed) + 1e-5,
                                "reloading support cannot restart gravity acceleration");
                        });
                        h.runAfterDelay(30, () -> { supported[0] = false; release(h, pos, restored); });
                    });
                } else release(h, pos, be);
            });
        });
    }
    private static void release(GameTestHelper h, BlockPos pos, LinearActuatorBlockEntity be) {
        var root = be.movedContraption; double held = be.offset;
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) h.setBlock(pos.offset(x, -4, z), Blocks.AIR);
        h.runAfterDelay(25, () -> {
            h.assertTrue(be.movedContraption == root && be.offset > held + .1, "removing support releases gravity without reassembly");
            be.disassemble(); h.succeed();
        });
    }
}
