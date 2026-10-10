package com.diliupengyun.differential_create.tests;

import java.util.*;
import com.diliupengyun.differential_create.kinetics.portable.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import com.simibubi.create.content.redstone.rail.ControllerRailBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.gametest.*;

/** Closed native rail loop: controller motors provide all acceleration, including departures from zero. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class PortableDepotTests {
    private PortableDepotTests() {}
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var result = new ArrayList<TestFunction>();
        for (boolean fluid : new boolean[]{false, true}) {
            String name = "dock_depot_" + (fluid ? "fluid" : "item");
            if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
                result.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 60000, 0, true, h -> depot(h, fluid)));
        }
        return result;
    }
    private static void depot(GameTestHelper h, boolean fluid) {
        h.runAfterDelay(5, () -> {
            var port = fluid ? AllBlocks.PORTABLE_FLUID_INTERFACE : AllBlocks.PORTABLE_STORAGE_INTERFACE;
            var f = new MinecartOperationTests.Fixture(h, Direction.EAST, port.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH),
                "rideable", !fluid, false, fluid ? AllBlocks.FLUID_TANK.getDefaultState() : null);
            if (fluid) f.root.getContraption().getStorage().getFluids().fill(new FluidStack(Fluids.WATER, 4000), FluidAction.EXECUTE);
            for (int x = 2; x <= 18; x++) for (int z : new int[]{8, 20}) rail(h, new BlockPos(x, 5, z), RailShape.EAST_WEST);
            for (int z = 9; z < 20; z++) for (int x : new int[]{2, 18}) rail(h, new BlockPos(x, 5, z), RailShape.NORTH_SOUTH);
            curve(h, new BlockPos(2, 5, 8), RailShape.SOUTH_EAST); curve(h, new BlockPos(18, 5, 8), RailShape.SOUTH_WEST);
            curve(h, new BlockPos(18, 5, 20), RailShape.NORTH_WEST); curve(h, new BlockPos(2, 5, 20), RailShape.NORTH_EAST);
            var stop = MinecartOperationTests.START;
            for (int x = 4; x <= 6; x++) {
                var p = new BlockPos(x, 5, 8); h.setBlock(p.below(), Blocks.STONE);
                h.setBlock(p, controller(1));
            }
            // One item in a barrel gives a real level-one comparator signal; controller rails share it.
            h.setBlock(new BlockPos(4, 4, 6), Blocks.STONE); h.setBlock(new BlockPos(4, 4, 7), Blocks.STONE);
            h.setBlock(new BlockPos(4, 5, 5), Blocks.BARREL);
            ((net.minecraft.world.level.block.entity.BarrelBlockEntity) h.getBlockEntity(new BlockPos(4, 5, 5))).setItem(0, new ItemStack(Items.STONE));
            h.setBlock(new BlockPos(4, 5, 6), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.NORTH));
            h.setBlock(new BlockPos(4, 5, 7), Blocks.REDSTONE_WIRE.defaultBlockState());
            h.setBlock(stop.below(), Blocks.STONE); h.setBlock(stop, controller(0));
            var detector = stop.east(3); h.setBlock(detector.below(), Blocks.STONE);
            h.setBlock(detector, Blocks.DETECTOR_RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, RailShape.EAST_WEST));
            var body = f.root.toGlobalVector(Vec3.atCenterOf(f.context.localPos), 1);
            var face = f.root.applyRotation(Vec3.atLowerCornerOf(Direction.NORTH.getNormal()), 1);
            var direction = Direction.getNearest(face.x, face.y, face.z);
            var target = BlockPos.containing(body).relative(direction, 2);
            h.getLevel().setBlockAndUpdate(target, port.getDefaultState().setValue(BlockStateProperties.FACING, direction.getOpposite()));
            var owner = (PortableStorageInterfaceBlockEntity) h.getLevel().getBlockEntity(target);
            class Loop {
                int ticks, cycles;
                boolean connected, departing, passedDetector;
                long generation = -1;
                void tick() {
                    ticks++;
                    var state = ((PortableEndpoint) owner).differentialcreate$docking();
                    if (state.phase == DockingState.Phase.TRANSFER && generation != state.session().generation()) {
                        generation = state.session().generation(); connected = true;
                        h.assertTrue(f.cart.getDeltaMovement().lengthSqr() < 1e-16 && !f.root.isStalled(), "real controller rail parks the cart without native stall");
                        if (cycles > 0) h.assertTrue(h.getBlockState(new BlockPos(6, 5, 8)).getValue(ControllerRailBlock.POWER) == 1, "actual comparator-powered approach is level one");
                        if (fluid) h.assertTrue(h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, target, null).drain(1, FluidAction.EXECUTE).getAmount() == 1, "depot transfers mounted fluid each arrival");
                        else h.assertTrue(ItemHandlerHelper.insertItem(h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, target, null), new ItemStack(Items.DIAMOND), false).isEmpty(), "depot transfers mounted items each arrival");
                    }
                    if (connected && state.phase == DockingState.Phase.IDLE && !departing) {
                        departing = true; cycles++;
                        h.setBlock(stop.below(), Blocks.REDSTONE_BLOCK);
                        if (cycles >= 100) {
                            if (fluid) h.assertTrue(f.root.getContraption().getStorage().getFluids().getFluidInTank(0).getAmount() == 3900, "one hundred depot cycles consume exactly one hundred fluid units");
                            else h.assertTrue(MinecartOperationTests.items(f, Items.DIAMOND) == 100, "one hundred depot cycles insert exactly one hundred items");
                            f.cleanup(); h.succeed(); return;
                        }
                    }
                    boolean detectorPowered = h.getBlockState(detector).getValue(DetectorRailBlock.POWERED);
                    if (departing && detectorPowered) passedDetector = true;
                    if (departing && passedDetector && !detectorPowered) {
                        departing = connected = passedDetector = false;
                        h.setBlock(stop.below(), Blocks.STONE);
                    }
                    if (ticks > 58000 || ticks > 2000 && cycles < 2) h.fail("depot loop did not repeat: cycles=" + cycles + ", phase=" + state.phase + ", position=" + f.cart.position() + ", velocity=" + f.cart.getDeltaMovement()
                        + ", stop=" + h.getBlockState(stop) + ", support=" + h.getBlockState(stop.below()) + ", signal=" + h.getLevel().getBestNeighborSignal(h.absolutePos(stop))
                        + ", departing=" + departing + ", passed=" + passedDetector + ", failure=" + state.failure
                        + ", body=" + f.root.toGlobalVector(Vec3.atCenterOf(f.context.localPos), 1) + ", facing=" + f.root.applyRotation(Vec3.atLowerCornerOf(Direction.NORTH.getNormal()), 1)
                        + ", target=" + target + ", data=" + f.context.data + ", disabled=" + f.context.disabled + ", stalled=" + f.root.isStalled()
                        + ", powered=" + owner.isPowered() + ", managed=" + PortableDocking.managed(f.context));
                    h.runAfterDelay(1, this::tick);
                }
            }
            h.runAfterDelay(1, new Loop()::tick);
        });
    }
    private static net.minecraft.world.level.block.state.BlockState controller(int power) {
        return AllBlocks.CONTROLLER_RAIL.getDefaultState().setValue(ControllerRailBlock.SHAPE, RailShape.EAST_WEST)
            .setValue(ControllerRailBlock.BACKWARDS, true).setValue(ControllerRailBlock.POWER, power);
    }
    private static void rail(GameTestHelper h, BlockPos pos, RailShape shape) {
        h.setBlock(pos.below(), Blocks.REDSTONE_BLOCK);
        h.setBlock(pos, Blocks.POWERED_RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, shape).setValue(PoweredRailBlock.POWERED, true));
    }
    private static void curve(GameTestHelper h, BlockPos pos, RailShape shape) {
        h.setBlock(pos.below(), Blocks.STONE); h.setBlock(pos, Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, shape));
    }
}
