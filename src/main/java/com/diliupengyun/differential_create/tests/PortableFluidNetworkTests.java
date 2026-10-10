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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
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
        }
        return result;
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
