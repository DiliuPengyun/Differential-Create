package com.diliupengyun.differential_create.tests;

import java.util.*;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.kinetics.portable.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.*;

/** A real comparator/wire/repeater/clutch circuit, repeatedly docked by the physical solver. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class PortableMachineTests {
    private PortableMachineTests() {}
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var result = new ArrayList<TestFunction>();
        for (boolean fluid : new boolean[]{false, true}) {
            String name = "dock_machine_clutch_" + (fluid ? "fluid" : "item");
            if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
                result.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 60000, 0, true, h -> farm(h, fluid)));
        }
        return result;
    }
    private static void supported(GameTestHelper h, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        h.setBlock(pos.below(), Blocks.STONE); h.setBlock(pos, state);
    }
    private static void farm(GameTestHelper h, boolean fluid) {
        var base = new BlockPos(8, 8, 8);
        var mobile = base.above().east(2); var target = mobile.east(2);
        h.setBlock(base, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        h.setBlock(base.below(), AllBlocks.CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        h.setBlock(base.below(2), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        h.setBlock(base.above(), Blocks.SLIME_BLOCK); h.setBlock(base.above().east(), Blocks.SLIME_BLOCK);
        var port = fluid ? AllBlocks.PORTABLE_FLUID_INTERFACE : AllBlocks.PORTABLE_STORAGE_INTERFACE;
        h.setBlock(mobile, port.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        h.setBlock(base.above().north(), fluid ? AllBlocks.FLUID_TANK.getDefaultState() : Blocks.CHEST.defaultBlockState());
        h.setBlock(target, port.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
        // A full-strength comparator circuit on a supported ledge, stepped down to the clutch.
        supported(h, target.east(), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST));
        supported(h, target.east(2), Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        for (int z = 8; z <= 11; z++) supported(h, new BlockPos(15, 9, z), Blocks.REDSTONE_WIRE.defaultBlockState());
        for (int x = 8; x <= 14; x++) supported(h, new BlockPos(x, 9, 11), Blocks.REDSTONE_WIRE.defaultBlockState());
        supported(h, new BlockPos(8, 8, 10), Blocks.REDSTONE_WIRE.defaultBlockState());
        supported(h, new BlockPos(8, 7, 9), Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        if (!fluid) { h.setBlock(target.below(), Blocks.HOPPER); h.setBlock(target.below(2), Blocks.CHEST); }
        h.runAfterDelay(5, () -> {
            CreativeMotorBlockEntity motor = h.getBlockEntity(base.below(2)); motor.generatedSpeed.setValue(4);
            MechanicalBearingBlockEntity bearing = h.getBlockEntity(base); bearing.assemble();
            var root = bearing.getMovedContraption(); h.assertTrue(root != null, "player-built bearing assembles the interface and storage");
            if (fluid) root.getContraption().getStorage().getFluids().fill(new FluidStack(Fluids.WATER, 4000), FluidAction.EXECUTE);
            else net.neoforged.neoforge.items.ItemHandlerHelper.insertItem(root.getContraption().getStorage().getAllItems(), new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 16), false);
            var owner = (PortableStorageInterfaceBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(target));
            owner.getBehaviour(DockingSettingBehaviour.OUTPUT).setValue(1);
            var state = ((PortableEndpoint) owner).differentialcreate$docking();
            class Cycle {
                int ticks, cycles, opened, openedAt;
                long generation = -1;
                boolean completedConnection, sawClutchOpen;
                void tick() {
                    ticks++;
                    if (state.phase == DockingState.Phase.TRANSFER) {
                        h.assertFalse(root.isStalled(), "bearing is stopped by actual reaction, without native model freezing");
                        if (generation != state.session().generation()) {
                            generation = state.session().generation(); opened++; completedConnection = true; openedAt = ticks;
                            if (fluid) {
                                var handler = h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, h.absolutePos(target), null);
                                h.assertTrue(handler.drain(1, FluidAction.EXECUTE).getAmount() == 1, "every fluid docking performs a real conserved transfer");
                            }
                        }
                        if (ticks - openedAt > 10) {
                            var clutch = h.getLevel().getBlockEntity(h.absolutePos(base.below()));
                            h.assertTrue(((IClutchDynamics) clutch).differentialcreate$getRedstoneSignal() == 15, "real redstone circuit fully releases the clutch: " + ((IClutchDynamics) clutch).differentialcreate$getRedstoneSignal());
                            sawClutchOpen = true;
                            h.assertTrue(Math.abs(NetworkDynamics.preciseSpeed(motor)) > 1, "upstream bus keeps running while the structure is docked");
                        }
                    }
                    if (completedConnection && state.phase == DockingState.Phase.IDLE) {
                        completedConnection = false; cycles++;
                        h.assertTrue(state.signal() == 0, "full release reconnects the structure drive");
                        if (cycles >= 100) {
                            h.assertTrue(opened == 100 && sawClutchOpen, "one hundred complete connections and releases use the real circuit");
                            h.assertTrue(state.dissipated > 0, "one hundred arrivals consume actual braking work");
                            if (fluid) h.assertTrue(root.getContraption().getStorage().getFluids().getFluidInTank(0).getAmount() == 3900, "fluid stock pays exactly one unit per cycle");
                            else {
                                var handler = h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(target.below(2)), null);
                                int count = 0; for (int slot = 0; slot < handler.getSlots(); slot++) count += handler.getStackInSlot(slot).getCount();
                                h.assertTrue(count == 16, "real hopper unloads the mounted chest without duplicating items");
                            }
                            root.discard(); h.succeed(); return;
                        }
                    }
                    if (ticks > 1800 && cycles < 2 || ticks > 59000) h.fail("machine did not repeat: cycles=" + cycles + ", opened=" + opened + ", phase=" + state.phase + ", failure=" + state.failure + ", rpm=" + NetworkDynamics.preciseSpeed(bearing));
                    h.runAfterDelay(1, this::tick);
                }
            }
            h.runAfterDelay(1, new Cycle()::tick);
        });
    }
}
