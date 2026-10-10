package com.diliupengyun.differential_create.tests;

import static com.diliupengyun.differential_create.tests.MinecartOperationTests.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.mixin.LinearActuatorAccessor;
import com.simibubi.create.AllAttachmentTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.infrastructure.config.AllConfigs;
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
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/** Native docking and capability semantics under Differential Create's motion. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class PortableStorageInterfaceTests {
    private PortableStorageInterfaceTests() {}
    private static void add(Collection<TestFunction> cases, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
            cases.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 120, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (var facing : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH})
            for (boolean fluid : new boolean[]{false, true})
                for (String mode : new String[]{"idle", "transfer", "continuous", "redstone", "disabled", "removed", "wrong_type", "wrong_facing"})
                    add(cases, "psi_cart_" + facing.getName() + (fluid ? "_fluid_" : "_item_") + mode, h -> cart(h, facing, fluid, mode, 0));
        for (boolean fluid : new boolean[]{false, true}) {
            for (int gap : new int[]{1, 2})
                add(cases, "psi_gap_" + (fluid ? "fluid_" : "item_") + (gap + 1), h -> cart(h, Direction.EAST, fluid, "gap", gap));
            add(cases, "psi_retained_handler_" + (fluid ? "fluid" : "item"), h -> retained(h, fluid));
            for (boolean piston : new boolean[]{false, true})
                add(cases, "psi_shaft_" + (piston ? "piston_" : "bearing_") + (fluid ? "fluid" : "item"), h -> shaft(h, piston, fluid));
        }
        return cases;
    }
    private static int timer(GameTestHelper h, PortableStorageInterfaceBlockEntity psi) {
        return psi.saveWithoutMetadata(h.getLevel().registryAccess()).getInt("Timer");
    }
    private static int volume(Fixture f) {
        var tanks = f.root.getContraption().getStorage().getFluids(); int result = 0;
        for (int tank = 0; tank < tanks.getTanks(); tank++) result += tanks.getFluidInTank(tank).getAmount();
        return result;
    }
    private record Dock(Fixture f, PortableStorageInterfaceBlockEntity psi, BlockPos target) {
        void tick() {
            psi.tick(); f.cart.getData(AllAttachmentTypes.MINECART_CONTROLLER).tick(); f.tick();
        }
    }
    private static Dock prepare(GameTestHelper h, Direction facing, boolean fluid, String mode, int gap) {
        var block = fluid ? AllBlocks.PORTABLE_FLUID_INTERFACE : AllBlocks.PORTABLE_STORAGE_INTERFACE;
        var f = new Fixture(h, facing, block.getDefaultState().setValue(BlockStateProperties.FACING, facing),
            "rideable", !fluid, false, fluid ? AllBlocks.FLUID_TANK.getDefaultState() : null);
        if (fluid) {
            near(h, 4000, f.root.getContraption().getStorage().getFluids().fill(new FluidStack(Fluids.WATER, 4000), FluidAction.EXECUTE), 0,
                "mounted native tank receives the initial fluid inventory");
        }
        f.context.disabled = true; f.push(.05); f.tick(); f.context.disabled = false;
        var behaviour = MovementBehaviour.REGISTRY.get(f.context.state);
        var point = f.root.toGlobalVector(Vec3.atCenterOf(f.context.localPos).add(behaviour.getActiveAreaOffset(f.context)), 1);
        var target = BlockPos.containing(point).relative(facing, gap);
        var other = mode.equals("wrong_type") ? (fluid ? AllBlocks.PORTABLE_STORAGE_INTERFACE : AllBlocks.PORTABLE_FLUID_INTERFACE) : block;
        h.getLevel().setBlockAndUpdate(target, other.getDefaultState().setValue(BlockStateProperties.FACING,
            mode.equals("wrong_facing") ? facing : facing.getOpposite()));
        var psi = (PortableStorageInterfaceBlockEntity) h.getLevel().getBlockEntity(target);
        f.context.position = point; f.context.rotation = v -> f.root.applyRotation(v, 1); f.context.motion = vector(facing, .05);
        behaviour.visitNewPosition(f.context, BlockPos.containing(point));
        return new Dock(f, psi, target);
    }
    private static void connect(GameTestHelper h, Dock d) {
        h.assertTrue(d.f.context.data.contains("WorkingPos"), "native contact locates its stationary counterpart");
        near(h, 14, timer(h, d.psi), 0, "initial native docking timer is fourteen ticks");
        for (int tick = 0; tick < 8 && !d.f.context.stall; tick++) d.tick();
        h.assertTrue(d.f.context.stall && d.f.root.isStalled(), "native interface parks the moving mounted structure");
        h.assertTrue(d.psi.canTransfer(), "stationary native capability is connected");
    }
    private static void exchange(GameTestHelper h, Dock d, boolean fluid, boolean simulate) {
        var f = d.f;
        if (fluid) {
            var handler = h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, d.target, null);
            h.assertTrue(handler != null, "stationary fluid capability is present");
            near(h, 125, handler.drain(125, simulate ? FluidAction.SIMULATE : FluidAction.EXECUTE).getAmount(), 0, "native drain accepts the requested fluid");
            near(h, 250, handler.fill(new FluidStack(Fluids.WATER, 250), simulate ? FluidAction.SIMULATE : FluidAction.EXECUTE), 0, "native fill accepts the requested fluid");
        } else {
            var handler = h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, d.target, null);
            h.assertTrue(handler != null && handler.getSlots() > 0, "stationary item capability exposes the mounted inventory");
            int slot = 0; while (slot < handler.getSlots() && !handler.getStackInSlot(slot).is(Items.STONE)) slot++;
            near(h, 3, handler.extractItem(slot, 3, simulate).getCount(), 0, "native extraction accepts the requested items");
            h.assertTrue(ItemHandlerHelper.insertItem(handler, new ItemStack(Items.DIAMOND, 2), simulate).isEmpty(), "native insertion reaches the mounted inventory");
        }
    }
    private static void cart(GameTestHelper h, Direction facing, boolean fluid, String mode, int gap) {
        h.runAfterDelay(5, () -> {
            var d = prepare(h, facing, fluid, mode, gap); var f = d.f;
            if (mode.startsWith("wrong_") || mode.equals("gap")) {
                boolean accepted = mode.equals("gap") && gap == 1;
                h.assertTrue(f.context.data.contains("WorkingPos") == accepted, "native search requires matching type, opposed facing and one or two empty blocks");
                f.cleanup(); h.succeed(); return;
            }
            connect(h, d);
            h.assertFalse(ContraptionActorWork.of(f.context).motionManaged() || ContraptionActorWork.of(f.context).pending(), "interface keeps its native clock and has no paid tool job");
            var saved = f.cart.getData(AllAttachmentTypes.MINECART_CONTROLLER).serializeNBT(h.getLevel().registryAccess()).getCompound("StallData").getList("Motion", 6);
            var incoming = new Vec3(saved.getDouble(0), saved.getDouble(1), saved.getDouble(2));
            h.assertTrue(incoming.lengthSqr() > 0, "native controller saves incoming cart velocity before parking");
            var held = f.cart.position();
            if (mode.equals("transfer")) {
                int before = timer(h, d.psi); exchange(h, d, fluid, true);
                near(h, before, timer(h, d.psi), 0, "simulation leaves the timeout unchanged");
                if (fluid) near(h, 4000, volume(f), 0, "simulation leaves mounted fluid untouched");
                else { near(h, 16, items(f, Items.STONE), 0, "simulation leaves mounted items untouched"); near(h, 0, items(f, Items.DIAMOND), 0, "simulation inserts no diamonds"); }
                exchange(h, d, fluid, false);
                near(h, AllConfigs.server().logistics.psiTimeout.get() + 4, timer(h, d.psi), 0, "successful transfer resets the native timeout");
                if (fluid) near(h, 4125, volume(f), 0, "native inventory accounts for the exact fill and drain");
                else { near(h, 13, items(f, Items.STONE), 0, "native inventory pays exactly one extraction"); near(h, 2, items(f, Items.DIAMOND), 0, "native inventory receives exactly one insertion"); }
            }
            if (mode.equals("continuous")) {
                for (int tick = 0; tick < 100; tick++) {
                    if (tick % 20 == 0) {
                        if (fluid) h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, d.target, null).drain(1, FluidAction.EXECUTE);
                        else {
                            var handler = h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, d.target, null);
                            int slot = 0; while (!handler.getStackInSlot(slot).is(Items.STONE)) slot++;
                            handler.extractItem(slot, 1, false);
                        }
                    }
                    d.tick(); h.assertTrue(f.root.isStalled(), "ongoing successful transfers hold the vehicle beyond the idle timeout");
                    near(h, 0, f.cart.position().distanceTo(held), 1e-8, "ongoing docking admits no cart displacement");
                }
            }
            if (mode.equals("redstone")) {
                h.getLevel().setBlockAndUpdate(d.target.above(), Blocks.REDSTONE_BLOCK.defaultBlockState()); d.psi.neighbourChanged();
                h.assertTrue(d.psi.isPowered() && !d.psi.canTransfer(), "real neighbour power closes the stationary interface");
                f.root.tick();
                if (fluid) near(h, 0, h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, d.target, null).fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE), 0, "newly resolved disconnected fluid capability rejects insertion");
                else near(h, 0, h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, d.target, null).getSlots(), 0, "newly resolved disconnected item capability exposes no slots");
            } else if (mode.equals("removed")) {
                h.getLevel().setBlockAndUpdate(d.target, Blocks.AIR.defaultBlockState()); f.root.tick();
            } else if (mode.equals("disabled")) {
                f.root.getContraption().setActorsActive(new ItemStack(f.context.state.getBlock()), false); f.root.tick();
            } else {
                int budget = timer(h, d.psi) - 4;
                for (int tick = 0; tick < budget; tick++) {
                    d.tick(); near(h, 0, f.cart.position().distanceTo(held), 1e-8, "timeout waits without cart feed");
                }
                near(h, 4, timer(h, d.psi), 0, "native pause releases at timer four");
            }
            h.assertFalse(f.root.isStalled() || f.context.stall, "native cancellation or timeout releases the mounted pause");
            near(h, 0, f.cart.getDeltaMovement().distanceTo(incoming), 1e-12, "release restores the controller's exact incoming velocity");
            f.cleanup(); h.succeed();
        });
    }
    private static void retained(GameTestHelper h, boolean fluid) {
        h.runAfterDelay(5, () -> {
            var d = prepare(h, Direction.EAST, fluid, "transfer", 0); connect(h, d);
            var oldFluid = fluid ? h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, d.target, null) : null;
            var oldItem = fluid ? null : h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, d.target, null);
            h.getLevel().setBlockAndUpdate(d.target.above(), Blocks.REDSTONE_BLOCK.defaultBlockState()); d.psi.neighbourChanged();
            h.assertFalse(d.psi.canTransfer(), "power invalidates the live connection");
            if (fluid) {
                near(h, 0, oldFluid.drain(1, FluidAction.EXECUTE).getAmount(), 0, "retained fluid handler gates extraction on live connection");
                near(h, 1, oldFluid.fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE), 0, "upstream retained fluid handler gates fill only on timer and still accepts a unit");
                near(h, 4001, volume(d.f), 0, "retained upstream fill changes the old mounted inventory");
            } else {
                h.assertTrue(ItemHandlerHelper.insertItem(oldItem, new ItemStack(Items.DIAMOND), false).is(Items.DIAMOND), "retained item handler rejects insertion after power disconnects");
            }
            d.f.cleanup(); h.succeed();
        });
    }
    private static void setSpeed(KineticBlockEntity be, double rpm) {
        var network = (INetworkDynamics) be.getOrCreateNetwork();
        network.differentialcreate$setSpeed(rpm / network.differentialcreate$getRatios().get(be));
    }
    private static void shaft(GameTestHelper h, boolean piston, boolean fluid) {
        var pos = new BlockPos(6, 8, 12);
        h.setBlock(pos, (piston ? AllBlocks.MECHANICAL_PISTON : AllBlocks.MECHANICAL_BEARING).getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        if (piston) for (int x = 1; x <= 4; x++) h.setBlock(pos.west(x), AllBlocks.PISTON_EXTENSION_POLE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        h.setBlock(pos.east(), Blocks.SLIME_BLOCK); h.setBlock(pos.east().above(), Blocks.SLIME_BLOCK);
        h.setBlock(pos.east().above(2), (fluid ? AllBlocks.PORTABLE_FLUID_INTERFACE : AllBlocks.PORTABLE_STORAGE_INTERFACE).getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        h.setBlock(pos.east().south(), AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        h.runAfterDelay(5, () -> {
            KineticBlockEntity be = h.getBlockEntity(pos); NetworkTopology.mark(be); NetworkTopology.flush(); setSpeed(be, piston ? -16 : 16);
            if (piston) { try { ((LinearActuatorAccessor) be).differentialcreate$assemble(); } catch (Exception e) { throw new AssertionError(e); } }
            else ((MechanicalBearingBlockEntity) be).assemble();
            NetworkTopology.mark(be); NetworkTopology.flush();
            ControlledContraptionEntity root = piston ? (ControlledContraptionEntity) ((LinearActuatorBlockEntity) be).movedContraption : ((MechanicalBearingBlockEntity) be).getMovedContraption();
            h.assertTrue(root != null, "native shaft controller assembles the mixed interface structure");
            var context = root.getContraption().getActors().stream().filter(a -> a.getLeft().state().is(fluid ? AllBlocks.PORTABLE_FLUID_INTERFACE.get() : AllBlocks.PORTABLE_STORAGE_INTERFACE.get())).findFirst().orElseThrow().getRight();
            var behaviour = MovementBehaviour.REGISTRY.get(context.state); context.disabled = true; root.tickActors();
            // Park inside the actuator range, rather than at its fully retracted
            // placement boundary, which legitimately disassembles at zero feed.
            setSpeed(be, piston ? -64 : 16); MachiningClock.advance(h.getLevel()); be.tick(); context.disabled = false;
            var point = root.toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour.getActiveAreaOffset(context)), 1);
            var target = BlockPos.containing(point); h.getLevel().setBlockAndUpdate(target, context.state.setValue(BlockStateProperties.FACING, Direction.WEST));
            var psi = (PortableStorageInterfaceBlockEntity) h.getLevel().getBlockEntity(target);
            context.position = point; context.rotation = v -> root.applyRotation(v, 1); context.motion = point.subtract(Vec3.atCenterOf(target)).normalize().scale(.05);
            behaviour.visitNewPosition(context, target); behaviour.tick(context); root.tickActors();
            h.assertTrue(root.isStalled() && context.stall, "real interface callback parks the shaft output");
            double held = piston ? ((LinearActuatorBlockEntity) be).offset : root.getAngle(1);
            double inertia = ContraptionPhysics.inertia(be);
            for (int tick = 0; tick < 3; tick++) {
                psi.tick(); root.tickActors(); setSpeed(be, piston ? -16 : 16);
                double before = ElasticKinetics.state(be).angle; MachiningClock.advance(h.getLevel()); be.tick();
                h.assertTrue(ElasticKinetics.state(be).angle != before, "shaft continues integrating rotation while the interface holds output");
                near(h, held, piston ? ((LinearActuatorBlockEntity) be).offset : root.getAngle(1), 2e-5, "native output remains parked");
                near(h, inertia, ContraptionPhysics.inertia(be), 1e-12, "parked structure still contributes inertia to the shaft model");
                near(h, 0, ContraptionPhysics.airDrag(be), 0, "parked output contributes no structure air drag");
                near(h, 0, ContraptionPhysics.workTorque(be), 0, "interface transfer adds no tool work torque");
            }
            h.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState()); root.tickActors(); setSpeed(be, 0); be.tick();
            near(h, held, piston ? ((LinearActuatorBlockEntity) be).offset : root.getAngle(1), 2e-5, "release cannot replay shaft travel accumulated during docking");
            setSpeed(be, piston ? -16 : 16); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
            h.assertTrue(Math.abs((piston ? ((LinearActuatorBlockEntity) be).offset : root.getAngle(1)) - held) > .001, "new shaft feed moves the output after release");
            if (piston) ((LinearActuatorBlockEntity) be).disassemble(); else { root.setAngle(0); ((MechanicalBearingBlockEntity) be).disassemble(); }
            h.succeed();
        });
    }
}
