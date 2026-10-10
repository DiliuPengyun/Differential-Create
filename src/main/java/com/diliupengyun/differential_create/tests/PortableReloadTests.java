package com.diliupengyun.differential_create.tests;

import java.util.*;
import com.diliupengyun.differential_create.kinetics.portable.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class PortableReloadTests {
    private PortableReloadTests() {}
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var result = new ArrayList<TestFunction>();
        for (boolean fluid : new boolean[]{false, true}) for (boolean unloading : new boolean[]{false, true}) {
            String name = (unloading ? "dock_suspend_" : "dock_reload_") + (fluid ? "fluid" : "item");
            if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
                result.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 220, 0, true, h -> reload(h, fluid, unloading)));
        }
        return result;
    }
    private static void reload(GameTestHelper h, boolean fluid, boolean unloading) {
        h.runAfterDelay(5, () -> {
            var port = fluid ? AllBlocks.PORTABLE_FLUID_INTERFACE : AllBlocks.PORTABLE_STORAGE_INTERFACE;
            var f = new MinecartOperationTests.Fixture(h, Direction.EAST, port.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH),
                "rideable", !fluid, false, fluid ? AllBlocks.FLUID_TANK.getDefaultState() : null);
            var body = f.root.toGlobalVector(Vec3.atCenterOf(f.context.localPos), 1);
            var face = f.root.applyRotation(Vec3.atLowerCornerOf(Direction.NORTH.getNormal()), 1);
            var direction = Direction.getNearest(face.x, face.y, face.z);
            var target = BlockPos.containing(body).relative(direction, 2);
            h.getLevel().setBlockAndUpdate(target, port.getDefaultState().setValue(BlockStateProperties.FACING, direction.getOpposite()));
            if (fluid) f.root.getContraption().getStorage().getFluids().fill(new FluidStack(Fluids.WATER, 4000), FluidAction.EXECUTE);
            class Restart {
                int ticks, opened = -1, resumed = -1, remaining;
                boolean reloaded;
                DockingSession first;
                IFluidHandler oldFluid;
                IItemHandler oldItem;
                void tick() {
                    ticks++;
                    var owner = (PortableStorageInterfaceBlockEntity) h.getLevel().getBlockEntity(target);
                    var state = ((PortableEndpoint) owner).differentialcreate$docking();
                    if (!reloaded && state.phase == DockingState.Phase.TRANSFER && opened < 0) {
                        opened = ticks; first = state.session();
                        if (fluid) oldFluid = h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, target, null);
                        else oldItem = h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, target, null);
                    }
                    if (!reloaded && opened >= 0 && ticks >= opened + 25) {
                        var saved = owner.saveWithoutMetadata(h.getLevel().registryAccess());
                        remaining = saved.getCompound("DCPortDocking").getInt("Remaining");
                        h.assertTrue(remaining > 0 && remaining < 45, "save retains elapsed idle time");
                        if (unloading) {
                            PortableDocking.forget(f.root, true);
                            h.assertFalse(first.valid(), "entity unload callback immediately revokes runtime storage access");
                            saved = owner.saveWithoutMetadata(h.getLevel().registryAccess());
                        }
                        var copy = (PortableStorageInterfaceBlockEntity) owner.getType().create(target, owner.getBlockState());
                        copy.loadWithComponents(saved, h.getLevel().registryAccess());
                        f.reload(); h.getLevel().setBlockEntity(copy);
                        h.assertFalse(first.valid(), "replacing endpoint and entity revokes the first session immediately");
                        reloaded = true;
                    }
                    if (reloaded) {
                        if (fluid) h.assertTrue(oldFluid.fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE) == 0 && oldFluid.getTanks() == 0, "retained fluid object never reconnects through NBT");
                        else h.assertTrue(ItemHandlerHelper.insertItem(oldItem, new ItemStack(Items.DIAMOND), false).is(Items.DIAMOND), "retained item object never reconnects through NBT");
                        if (state.phase == DockingState.Phase.TRANSFER && resumed < 0) {
                            resumed = ticks;
                            var current = state.session(); h.assertTrue(current.valid() && !first.valid(), "reload establishes a new guarded session");
                            int restored = owner.saveWithoutMetadata(h.getLevel().registryAccess()).getCompound("DCPortDocking").getInt("Remaining");
                            h.assertTrue(restored <= remaining && restored < 45, "reload never grants a fresh automatic timeout");
                        }
                        if (resumed >= 0 && state.phase == DockingState.Phase.IDLE) {
                            h.assertTrue(ticks - resumed <= remaining + 6, "saved idle timeout and retraction complete within the remaining budget");
                            if (fluid) h.assertTrue(f.root.getContraption().getStorage().getFluids().getFluidInTank(0).getAmount() == 4000, "reload preserves the mounted tank exactly");
                            f.cleanup(); h.succeed(); return;
                        }
                    }
                    if (ticks > 205) h.fail("reload did not settle and release: phase=" + state.phase + ", opened=" + opened + ", resumed=" + resumed);
                    h.runAfterDelay(1, this::tick);
                }
            }
            h.runAfterDelay(1, new Restart()::tick);
        });
    }
}
