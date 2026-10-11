package com.diliupengyun.differential_create.tests;

import java.util.*;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.kinetics.portable.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.pipes.GlassFluidPipeBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.*;

/** Real pumps and pipe-owned fractional buffers at a moving tank boundary. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class PortableFluidNetworkTests {
    private PortableFluidNetworkTests() {}
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var result = new ArrayList<TestFunction>();
        for (boolean inlet : new boolean[]{false, true}) {
            String name = "dock_pipe_" + (inlet ? "fill" : "drain");
            if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
                result.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 2500, 0, true, h -> pipe(h, inlet)));
            String reconnect = "dock_pipe_reconnect_" + (inlet ? "fill" : "drain");
            if (reconnect.matches(System.getProperty("differentialcreate.motion.select", ".*")))
                result.add(new TestFunction(reconnect, reconnect, "differentialcreate_motion:kineticphysicstests.physics_test", 6000, 0, true,
                    h -> reconnect(h, inlet)));
        }
        return result;
    }
    private static void ledge(GameTestHelper h, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        h.setBlock(pos.below(), Blocks.STONE); h.setBlock(pos, state);
    }
    /** Running dry pipes cache the closed endpoint before either real connection. */
    private static void reconnect(GameTestHelper h, boolean inlet) {
        BlockPos base = new BlockPos(8, 8, 8), target = base.above().east(4);
        h.setBlock(base, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        h.setBlock(base.below(), AllBlocks.CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        h.setBlock(base.below(2), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        h.setBlock(base.above(), Blocks.SLIME_BLOCK); h.setBlock(base.above().east(), Blocks.SLIME_BLOCK);
        h.setBlock(base.above().east(2), AllBlocks.PORTABLE_FLUID_INTERFACE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        h.setBlock(base.above().north(), AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(target, AllBlocks.PORTABLE_FLUID_INTERFACE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
        h.setBlock(target.above(), Blocks.REDSTONE_BLOCK);
        for (int i : new int[]{1, 3}) h.setBlock(target.east(i), AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(GlassFluidPipeBlock.AXIS, Direction.Axis.X));
        h.setBlock(target.east(2), AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
        h.setBlock(target.east(4), AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(target.east(2).above(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(target.east().above(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        ledge(h, target.south(), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.NORTH));
        ledge(h, target.south(2), Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        for (int x = 8; x <= 12; x++) ledge(h, new BlockPos(x, 9, 11), Blocks.REDSTONE_WIRE.defaultBlockState());
        ledge(h, base.south(2), Blocks.REDSTONE_WIRE.defaultBlockState());
        ledge(h, base.below().south(), Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        h.runAfterDelay(5, () -> {
            CreativeMotorBlockEntity drive = h.getBlockEntity(base.below(2)), pump = h.getBlockEntity(target.east().above());
            drive.generatedSpeed.setValue(0); pump.generatedSpeed.setValue(inlet ? 32 : -32);
            var fixed = h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, h.absolutePos(target.east(4)), null);
            h.assertTrue(fixed != null, "real fixed tank exists before the closed pipe warms up");
            if (inlet) fixed.fill(new FluidStack(Fluids.WATER, 4000), FluidAction.EXECUTE);
            // Leave the pump running against a closed interface long enough to reuse its frame.
            h.runAfterDelay(60, () -> {
                MechanicalBearingBlockEntity bearing = h.getBlockEntity(base); bearing.assemble();
                var root = bearing.getMovedContraption(); h.assertTrue(root != null, "native bearing assembles the real storage");
                var mounted = root.getContraption().getStorage().getFluids();
                if (!inlet) mounted.fill(new FluidStack(Fluids.WATER, 4000), FluidAction.EXECUTE);
                var owner = (PortableStorageInterfaceBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(target));
                owner.getBehaviour(DockingSettingBehaviour.OUTPUT).setValue(1);
                owner.getBehaviour(DockingSettingBehaviour.RELEASE).setValue(1);
                h.setBlock(target.above(), Blocks.AIR); drive.generatedSpeed.setValue(4);
                class Loop {
                    int ticks, connections, receivedAtOpen, reopenAt = -1;
                    long generation = -1;
                    IFluidHandler old;
                    void tick() {
                        ticks++;
                        var state = ((PortableEndpoint) owner).differentialcreate$docking();
                        double total = mounted.getFluidInTank(0).getAmount() + fixed.getFluidInTank(0).getAmount();
                        for (int i : new int[]{1, 2, 3}) {
                            var pipe = FluidPropagator.getPipe(h.getLevel(), h.absolutePos(target.east(i)));
                            var saved = FluidKinetics.state(pipe);
                            for (var parcel : saved.parcels()) if (!parcel.identity().isEmpty()) total += parcel.amount();
                            for (var buffer : saved.buffers.values()) for (var parcel : buffer.parcels()) if (!parcel.identity().isEmpty()) total += parcel.amount();
                        }
                        h.assertTrue(Math.abs(total - 4000) < 1e-6, "closed/open/reconnected pipes conserve fluid: " + total);
                        h.assertFalse(root.isStalled(), "pipe transfer uses physical bearing parking");
                        int received = inlet ? mounted.getFluidInTank(0).getAmount() : fixed.getFluidInTank(0).getAmount();
                        if (state.phase == DockingState.Phase.TRANSFER && state.session().generation() != generation) {
                            if (old != null) h.assertTrue(old.getTanks() == 0 && old.drain(1, FluidAction.SIMULATE).isEmpty(), "reconnect never revives the old session");
                            generation = state.session().generation(); connections++; receivedAtOpen = received;
                            old = h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, h.absolutePos(target), null);
                        }
                        if (state.phase == DockingState.Phase.TRANSFER && received >= receivedAtOpen + 125) {
                            if (connections >= 2) { root.discard(); h.succeed(); return; }
                            h.setBlock(target.above(), Blocks.REDSTONE_BLOCK);
                            h.assertTrue(old.getTanks() == 0 && old.drain(1, FluidAction.EXECUTE).isEmpty(), "closing invalidates the old cached provider immediately");
                        }
                        if (state.phase == DockingState.Phase.IDLE && owner.isPowered() && reopenAt < 0) reopenAt = ticks + 80;
                        if (ticks == reopenAt) { h.setBlock(target.above(), Blocks.AIR); reopenAt = -2; }
                        if (ticks > 5900) h.fail("reused pipe did not transfer twice: inlet=" + inlet + ", connections=" + connections + ", received=" + received + ", phase=" + state.phase);
                        h.runAfterDelay(1, this::tick);
                    }
                }
                h.runAfterDelay(1, new Loop()::tick);
            });
        });
    }
    private static void pipe(GameTestHelper h, boolean inlet) {
        h.runAfterDelay(5, () -> {
            var f = new MinecartOperationTests.Fixture(h, Direction.EAST, AllBlocks.PORTABLE_FLUID_INTERFACE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH),
                "rideable", false, false, AllBlocks.FLUID_TANK.getDefaultState());
            Vec3 body = f.root.toGlobalVector(Vec3.atCenterOf(f.context.localPos), 1);
            Vec3 face = f.root.applyRotation(Vec3.atLowerCornerOf(Direction.NORTH.getNormal()), 1);
            Direction outward = Direction.getNearest(face.x, face.y, face.z);
            BlockPos target = BlockPos.containing(body).relative(outward, 2), pump = target.relative(outward, 2), tank = target.relative(outward, 4);
            h.getLevel().setBlockAndUpdate(target, AllBlocks.PORTABLE_FLUID_INTERFACE.getDefaultState().setValue(BlockStateProperties.FACING, outward.getOpposite()));
            for (int i : new int[]{1, 3}) h.getLevel().setBlockAndUpdate(target.relative(outward, i), AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(GlassFluidPipeBlock.AXIS, outward.getAxis()));
            h.getLevel().setBlockAndUpdate(pump, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, outward));
            h.getLevel().setBlockAndUpdate(tank, AllBlocks.FLUID_TANK.getDefaultState());
            h.getLevel().setBlockAndUpdate(pump.above(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, outward.getAxis()));
            var source = pump.above().relative(outward.getOpposite());
            h.getLevel().setBlockAndUpdate(source, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, outward));
            var motor = (CreativeMotorBlockEntity) h.getLevel().getBlockEntity(source); motor.generatedSpeed.setValue(0);
            var owner = (PortableStorageInterfaceBlockEntity) h.getLevel().getBlockEntity(target);
            owner.getBehaviour(DockingSettingBehaviour.RELEASE).setValue(1);
            var mounted = f.root.getContraption().getStorage().getFluids();
            h.runAfterDelay(15, () -> {
                var fixed = h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, tank, null);
                h.assertTrue(fixed != null, "actual fixed tank exposes its native capability");
                if (inlet) fixed.fill(new FluidStack(Fluids.WATER, 4000), FluidAction.EXECUTE);
                else mounted.fill(new FluidStack(Fluids.WATER, 4000), FluidAction.EXECUTE);
                // Pump is a small cog: the neighbouring real cog reverses the motor's direction.
                motor.generatedSpeed.setValue((inlet ? 32 : -32) * outward.getAxisDirection().getStep());
                class Flow {
                    int ticks, closed = -1, stockAtClose;
                    IFluidHandler retained;
                    void tick() {
                        ticks++;
                        var state = ((PortableEndpoint) owner).differentialcreate$docking();
                        int received = inlet ? mounted.getFluidInTank(0).getAmount() : fixed.getFluidInTank(0).getAmount();
                        double total = mounted.getFluidInTank(0).getAmount() + fixed.getFluidInTank(0).getAmount();
                        for (int i : new int[]{1, 2, 3}) {
                            var pipe = FluidPropagator.getPipe(h.getLevel(), target.relative(outward, i));
                            if (pipe == null) continue;
                            var saved = FluidKinetics.state(pipe);
                            for (var parcel : saved.parcels()) if (!parcel.identity().isEmpty()) total += parcel.amount();
                            for (var buffer : saved.buffers.values()) for (var parcel : buffer.parcels()) if (!parcel.identity().isEmpty()) total += parcel.amount();
                        }
                        h.assertTrue(Math.abs(total - 4000) < 1e-6, "mounted tank, fixed tank and pipe buffers conserve all fluid: " + total);
                        if (received >= 100 && closed < 0) {
                            retained = h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, target, null);
                            h.getLevel().setBlockAndUpdate(target.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                            closed = ticks; stockAtClose = mounted.getFluidInTank(0).getAmount();
                            h.assertTrue(retained.fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE) == 0, "closing blocks cached fill immediately");
                        }
                        if (closed >= 0) {
                            h.assertTrue(mounted.getFluidInTank(0).getAmount() == stockAtClose, "real pipes cannot use the old storage session after closure");
                            if (ticks > closed + 50) { f.cleanup(); h.succeed(); return; }
                        }
                        if (ticks > 2350) h.fail("real portable pipe did not transfer: inlet=" + inlet + ", received=" + received + ", phase=" + state.phase + ", pump=" + motor.getSpeed());
                        h.runAfterDelay(1, this::tick);
                    }
                }
                h.runAfterDelay(1, new Flow()::tick);
            });
        });
    }
}
