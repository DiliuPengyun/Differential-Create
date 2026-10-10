package com.diliupengyun.differential_create.tests;

import java.util.*;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.kinetics.portable.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.items.*;

/** Natural entity/server ticks: no contact callbacks, native stalls or pose assignment to make a connection. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class PortableDockingTests {
    private PortableDockingTests() {}
    private static void add(Collection<TestFunction> tests, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
            tests.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 240, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var result = new ArrayList<TestFunction>();
        for (boolean fluid : new boolean[]{false, true}) for (String mode : new String[]{"idle", "creeping", "fast", "redstone", "retained", "reconnect", "removed", "disabled", "settings", "simulation"})
            add(result, "dock_cart_" + (fluid ? "fluid_" : "item_") + mode, h -> cart(h, fluid, mode));
        return result;
    }
    private static void cart(GameTestHelper h, boolean fluid, String mode) {
        h.runAfterDelay(5, () -> {
            var block = fluid ? AllBlocks.PORTABLE_FLUID_INTERFACE : AllBlocks.PORTABLE_STORAGE_INTERFACE;
            var f = new MinecartOperationTests.Fixture(h, Direction.EAST, block.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH),
                "rideable", !fluid, false, fluid ? AllBlocks.FLUID_TANK.getDefaultState() : null);
            var body = f.root.toGlobalVector(Vec3.atCenterOf(f.context.localPos), 1);
            var facing = f.root.applyRotation(Vec3.atLowerCornerOf(f.context.state.getValue(BlockStateProperties.FACING).getNormal()), 1);
            var direction = Direction.getNearest(facing.x, facing.y, facing.z);
            var target = BlockPos.containing(body).relative(direction, 2);
            h.getLevel().setBlockAndUpdate(target, block.getDefaultState().setValue(BlockStateProperties.FACING, direction.getOpposite()));
            var owner = (PortableStorageInterfaceBlockEntity) h.getLevel().getBlockEntity(target);
            var state = ((PortableEndpoint) owner).differentialcreate$docking();
            if (fluid) f.root.getContraption().getStorage().getFluids().fill(new FluidStack(Fluids.WATER, 4000), FluidAction.EXECUTE);
            if (mode.equals("creeping")) f.push(.015);
            if (mode.equals("fast")) f.push(.4);
            var arrival = f.cart.position();
            class Run {
                int elapsed, connectedAt = -1, releasedAt = -1;
                boolean acted, secondConnected, checkedClosed;
                IFluidHandler oldFluid;
                IItemHandler oldItem;
                DockingSession first;
                Vec3 held;
                void tick() {
                    elapsed++;
                    h.assertFalse(f.root.isStalled() || f.context.stall, "physical docking never creates native stall data");
                    if (mode.equals("fast")) {
                        h.assertFalse(owner.canTransfer(), "crossing before heads are ready cannot open storage");
                        if (elapsed >= 20) {
                            h.assertTrue(f.cart.position().distanceTo(arrival) > 1.5 && f.cart.getDeltaMovement().horizontalDistance() > .05,
                                "a missed high-speed arrival keeps real motion instead of snapping back to the station");
                            f.cleanup(); h.succeed(); return;
                        }
                        h.runAfterDelay(1, this::tick); return;
                    }
                    if (state.phase == DockingState.Phase.TRANSFER && connectedAt < 0) {
                        connectedAt = elapsed; held = f.cart.position(); first = state.session();
                        h.assertTrue(owner.canTransfer(), "stable physical contact opens inventory");
                        h.assertTrue(f.cart.getDeltaMovement().lengthSqr() < 1e-16, "cart is physically stopped before storage opens");
                        state.owner.getBehaviour(DockingSettingBehaviour.OUTPUT).setValue(1);
                        h.assertTrue(state.signal() == 15, "occupation output isolates a clutch throughout transfer");
                        oldFluid = fluid ? h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, target, null) : null;
                        oldItem = fluid ? null : h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, target, null);
                        if (mode.equals("creeping")) h.assertTrue(state.dissipated > 0, "residual motion paid finite braking work");
                    }
                    if (connectedAt >= 0 && !acted) {
                        acted = true;
                        switch (mode) {
                            case "redstone" -> owner.getBehaviour(DockingSettingBehaviour.RELEASE).setValue(1);
                            case "retained", "reconnect" -> power(true);
                            case "removed" -> h.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
                            case "disabled" -> f.root.getContraption().setActorsActive(new ItemStack(block.get()), false);
                            case "settings" -> {
                                owner.getBehaviour(DockingSettingBehaviour.RELEASE).setValue(1);
                                owner.getBehaviour(DockingSettingBehaviour.OUTPUT).setValue(2);
                                var saved = owner.saveWithoutMetadata(h.getLevel().registryAccess());
                                var clone = (PortableStorageInterfaceBlockEntity) owner.getType().create(target, owner.getBlockState());
                                clone.loadWithComponents(saved, h.getLevel().registryAccess());
                                var restored = ((PortableEndpoint) clone).differentialcreate$docking();
                                h.assertTrue(restored.outputMode == 2 && restored.releaseMode == 1, "both wrench settings survive save/load");
                                h.assertFalse(restored.session().valid(), "loading NBT cannot restore an old capability grant");
                                f.cleanup(); h.succeed(); return;
                            }
                            case "simulation" -> {
                                int stored = fluid ? f.root.getContraption().getStorage().getFluids().getFluidInTank(0).getAmount() : MinecartOperationTests.items(f, Items.STONE);
                                if (fluid) { oldFluid.fill(new FluidStack(Fluids.WATER, 25), FluidAction.SIMULATE); oldFluid.drain(25, FluidAction.SIMULATE); }
                                else ItemHandlerHelper.insertItem(oldItem, new ItemStack(Items.DIAMOND), true);
                                h.assertTrue(stored == (fluid ? f.root.getContraption().getStorage().getFluids().getFluidInTank(0).getAmount() : MinecartOperationTests.items(f, Items.STONE)), "simulation changes no storage");
                            }
                            default -> {
                                if (fluid) h.assertTrue(oldFluid.drain(125, FluidAction.EXECUTE).getAmount() == 125, "fluid uses the common transfer session");
                                else h.assertTrue(ItemHandlerHelper.insertItem(oldItem, new ItemStack(Items.DIAMOND), false).isEmpty(), "items use the common transfer session");
                            }
                        }
                    }
                    if (mode.equals("redstone") && connectedAt >= 0) {
                        if (elapsed == connectedAt + 90) { h.assertTrue(owner.canTransfer(), "redstone release holds beyond automatic timeout"); power(true); }
                    }
                    if (state.phase == DockingState.Phase.RETRACTING) {
                        h.assertFalse(owner.canTransfer(), "storage closes before retraction");
                        h.assertTrue(state.signal() == 15, "clutch occupation continues while heads retract");
                        checkOld(); checkedClosed = true;
                    }
                    if (connectedAt >= 0 && state.phase == DockingState.Phase.IDLE) {
                        if (releasedAt < 0) releasedAt = elapsed;
                        h.assertFalse(first.valid(), "released generation is permanently invalid"); checkOld();
                        h.assertTrue(f.cart.getDeltaMovement().lengthSqr() < 1e-16, "no motor means no saved-velocity relaunch");
                        if (mode.equals("reconnect")) {
                            power(false);
                            if (elapsed > releasedAt + 25) h.fail("reenabling should reconnect the stationary body");
                        } else {
                            if (!mode.equals("removed") && !mode.equals("disabled")) h.assertTrue(checkedClosed, "normal release passed through retraction");
                            f.cleanup(); h.succeed(); return;
                        }
                    }
                    if (mode.equals("reconnect") && releasedAt >= 0 && state.phase == DockingState.Phase.TRANSFER) {
                        secondConnected = true; h.assertFalse(first.valid(), "new connection never revives old generation"); checkOld();
                        var now = state.session(); h.assertTrue(now.valid(), "new connection obtains a new valid generation");
                        f.cleanup(); h.succeed(); return;
                    }
                    if (elapsed > 210) h.fail("dock did not finish: " + state.phase + " / " + state.failure + " / connected=" + connectedAt + " / body=" + body + " / port=" + target);
                    h.runAfterDelay(1, this::tick);
                }
                void power(boolean on) { h.getLevel().setBlockAndUpdate(target.above(), (on ? Blocks.REDSTONE_BLOCK : Blocks.AIR).defaultBlockState()); owner.neighbourChanged(); }
                void checkOld() {
                    if (fluid) {
                        h.assertTrue(oldFluid.fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE) == 0, "retained fluid fill rejects a closed or replaced session");
                        h.assertTrue(oldFluid.drain(1, FluidAction.SIMULATE).isEmpty(), "retained fluid simulation rejects a closed session");
                        h.assertTrue(oldFluid.getTanks() == 0, "old fluid queries expose no mounted stock");
                    } else h.assertTrue(ItemHandlerHelper.insertItem(oldItem, new ItemStack(Items.DIAMOND), false).is(Items.DIAMOND), "old item writes reject a closed session");
                }
            }
            h.runAfterDelay(1, new Run()::tick);
        });
    }
}
