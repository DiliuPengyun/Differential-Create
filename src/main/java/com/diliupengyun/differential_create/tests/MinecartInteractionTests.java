package com.diliupengyun.differential_create.tests;

import static com.diliupengyun.differential_create.tests.MinecartOperationTests.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.kinetics.portable.*;
import com.simibubi.create.AllAttachmentTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.DetectorRailBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native deployer pauses, physical interface parking and original minecart interactions. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class MinecartInteractionTests {
    private MinecartInteractionTests() {}
    private static void add(Collection<TestFunction> cases, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
            cases.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 100, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (var facing : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            for (String kind : new String[]{"rideable", "furnace", "chest"})
                add(cases, "minecart_interaction_" + facing.getName() + "_activate_" + kind, h -> activate(h, facing, kind));
            for (String kind : new String[]{"rider", "hopper", "tnt", "detector"})
                add(cases, "minecart_interaction_" + facing.getName() + "_" + kind, h -> nativeInteraction(h, facing, kind));
            for (boolean storage : new boolean[]{false, true})
                add(cases, "minecart_interaction_" + facing.getName() + (storage ? "_storage_dwell" : "_deployer_dwell"), h -> dwell(h, facing, storage));
        }
        return cases;
    }
    private static void activate(GameTestHelper h, Direction facing, String kind) {
        h.runAfterDelay(5, () -> {
            var f = new Fixture(h, facing, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, facing), kind, false, false);
            f.partial(Blocks.STONE.defaultBlockState());
            var rail = Blocks.ACTIVATOR_RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT,
                facing.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH).setValue(PoweredRailBlock.POWERED, true);
            h.setBlock(START, rail); f.cart.tick(); f.cart.getData(AllAttachmentTypes.MINECART_CONTROLLER).tick();
            h.assertTrue(f.root.isRemoved() && MinecartPhysics.mounted(f.cart) == null, "native activator rail disassembles the mounted vehicle: pos=" + f.cart.position()
                + ", rail=" + h.getLevel().getBlockState(f.cart.getCurrentRailPosition()) + ", usable=" + f.cart.canUseRail()
                + ", controller=" + f.cart.getData(AllAttachmentTypes.MINECART_CONTROLLER).cart() + ", rootRemoved=" + f.root.isRemoved());
            near(h, .01, MinecartPhysics.refresh(f.cart), 0, "activator disassembly releases mounted mass");
            h.assertFalse(f.context.data.contains("DCActorTravel") || f.context.data.contains("BreakingPos"), "activator clears partial tool work");
            h.assertTrue(h.getLevel().getBlockState(f.target).is(Blocks.STONE), "activator disassembly cannot finish an unpaid target");
            f.cart.discard(); h.succeed();
        });
    }
    private static void nativeInteraction(GameTestHelper h, Direction facing, String kind) {
        h.setBlock(START.below(), kind.equals("rider") || kind.equals("detector") ? Blocks.STONE : Blocks.REDSTONE_BLOCK);
        h.setBlock(START, kind.equals("detector") ? Blocks.DETECTOR_RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT,
            facing.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH)
            : kind.equals("rider") ? rail(facing) : Blocks.ACTIVATOR_RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT,
                facing.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH).setValue(PoweredRailBlock.POWERED, true));
        h.runAfterDelay(5, () -> {
            AbstractMinecart cart = kind.equals("hopper") ? EntityType.HOPPER_MINECART.create(h.getLevel())
                : kind.equals("tnt") ? EntityType.TNT_MINECART.create(h.getLevel()) : EntityType.MINECART.create(h.getLevel());
            var start = Vec3.atCenterOf(h.absolutePos(START)).add(0, -.4375, 0); cart.setPos(start); h.getLevel().addFreshEntity(cart);
            switch (kind) {
                case "rider" -> {
                    var player = h.makeMockPlayer(GameType.SURVIVAL); player.setPos(start);
                    h.assertTrue(cart.interact(player, InteractionHand.MAIN_HAND).consumesAction() && player.getVehicle() == cart, "real player right-click mounts the unassembled cart");
                    cart.setDeltaMovement(vector(facing, .2)); cart.tick();
                    near(h, .2 * .997, cart.getDeltaMovement().dot(vector(facing, 1)), 1e-12, "mounted player's native drag remains exact");
                    near(h, .15, cart.position().subtract(start).dot(vector(facing, 1)), 1e-8, "mounted player's native displacement remains exact");
                    player.stopRiding(); cart.setPos(start); cart.setDeltaMovement(vector(facing, .2)); cart.tick();
                    near(h, .192, cart.getDeltaMovement().dot(vector(facing, 1)), 1e-12, "dismount immediately restores the unoccupied drag law");
                }
                case "hopper" -> {
                    var hopper = (MinecartHopper) cart;
                    var dropped = h.spawnItem(Items.COAL, h.relativeVec(start.add(0, .1, 0))); hopper.tick();
                    h.assertFalse(hopper.isEnabled(), "powered native activator disables the hopper");
                    h.assertTrue(dropped.isAlive() && hopper.getItem(0).isEmpty(), "disabled hopper cannot collect a nearby item");
                    h.setBlock(START.below(), Blocks.STONE); h.setBlock(START, Blocks.ACTIVATOR_RAIL.defaultBlockState()
                        .setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, facing.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH)
                        .setValue(PoweredRailBlock.POWERED, false));
                    hopper.tick(); h.assertTrue(hopper.isEnabled() && hopper.getItem(0).is(Items.COAL), "unpowered activator reenables native item collection");
                }
                case "tnt" -> {
                    var tnt = (MinecartTNT) cart; tnt.tick(); near(h, 79, tnt.getFuse(), 0, "activator primes the native TNT timer");
                    var saved = tnt.saveWithoutId(new net.minecraft.nbt.CompoundTag()); tnt.load(saved); tnt.tick();
                    near(h, 78, tnt.getFuse(), 0, "native reload retains an already burning TNT fuse");
                }
                case "detector" -> {
                    cart.tick(); h.assertTrue(h.getBlockState(START).getValue(DetectorRailBlock.POWERED), "native detector rail senses the unassembled vehicle");
                }
            }
            cart.discard(); h.succeed();
        });
    }
    private static void dwell(GameTestHelper h, Direction facing, boolean storage) {
        h.runAfterDelay(5, () -> {
            var extra = (storage ? AllBlocks.PORTABLE_STORAGE_INTERFACE : AllBlocks.DEPLOYER).getDefaultState().setValue(BlockStateProperties.FACING, facing);
            var f = new Fixture(h, facing, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, facing), "rideable", true, false, extra);
            var actor = f.root.getContraption().getActors().stream().filter(a -> a.getLeft().state().is(extra.getBlock())).findFirst().orElseThrow().getRight();
            var behaviour = MovementBehaviour.REGISTRY.get(actor.state); actor.disabled = true; f.push(.05); f.tick();
            actor.disabled = false;
            if (storage) {
                var body = f.root.toGlobalVector(Vec3.atCenterOf(actor.localPos), 1);
                var normal = f.root.applyRotation(Vec3.atLowerCornerOf(facing.getNormal()), 1);
                var direction = Direction.getNearest(normal.x, normal.y, normal.z);
                var station = BlockPos.containing(body).relative(direction, 2);
                h.getLevel().setBlockAndUpdate(station, extra.setValue(BlockStateProperties.FACING, direction.getOpposite()));
                var endpoint = (PortableEndpoint) h.getLevel().getBlockEntity(station);
                class Arrival {
                    int ticks;
                    void tick() {
                        ticks++;
                        var dock = endpoint.differentialcreate$docking();
                        if (dock.permission()) {
                            h.assertFalse(actor.stall || f.root.isStalled(), "interface parks the mixed structure without a native actor pause");
                            near(h, 0, f.cart.getDeltaMovement().lengthSqr(), 1e-16, "interface removes actual cart velocity before opening inventory");
                            h.assertTrue(dock.dissipated > 0, "mixed-actor arrival pays finite interface braking work");
                            var held = f.cart.position(); var drillTarget = f.contact();
                            h.getLevel().setBlockAndUpdate(drillTarget, Blocks.STONE.defaultBlockState());
                            for (int i = 0; i < 3; i++) {
                                f.tick(); near(h, 0, f.cart.position().distanceTo(held), 1e-8, "physical parking supplies no drill feed");
                                near(h, 0, MinecartPhysics.state(f.cart).actorWork, 0, "parked drill receives no free work");
                            }
                            h.assertTrue(h.getLevel().getBlockState(drillTarget).is(Blocks.STONE), "parked drill leaves its target intact");
                            h.getLevel().setBlockAndUpdate(station, Blocks.AIR.defaultBlockState());
                            h.assertFalse(dock.permission(), "removing the stationary endpoint immediately revokes storage");
                            f.push(.4); f.tick();
                            h.assertTrue(h.getLevel().getBlockState(drillTarget).isAir(), "drill resumes paid feed after physical endpoint removal");
                            f.cleanup(); h.succeed(); return;
                        }
                        if (ticks > 60) h.fail("mixed-actor interface did not settle: " + dock.phase + ", " + dock.failure);
                        h.runAfterDelay(1, this::tick);
                    }
                }
                h.runAfterDelay(1, new Arrival()::tick); return;
            }
            actor.blockEntityData.putString("Mode", "PUNCH"); actor.data.put("HeldItem", new ItemStack(Items.DIAMOND_PICKAXE).save(h.getLevel().registryAccess()));
            var point = f.root.toGlobalVector(Vec3.atCenterOf(actor.localPos).add(behaviour.getActiveAreaOffset(actor)), 1);
            var target = BlockPos.containing(point);
            h.getLevel().setBlockAndUpdate(target, Blocks.OBSIDIAN.defaultBlockState());
            // Invoke the native contact callback at the current actor position;
            // the following native root ticks own dwell and cancellation.
            actor.position = point; actor.rotation = v -> f.root.applyRotation(v, 1); actor.motion = vector(facing, .05);
            behaviour.visitNewPosition(actor, target); f.root.tick();
            h.assertTrue(actor.stall && f.root.isStalled(), "actual native deployer pauses its mounted structure");
            var held = f.cart.position(); var drillTarget = f.contact(); h.getLevel().setBlockAndUpdate(drillTarget, Blocks.STONE.defaultBlockState());
            for (int i = 0; i < 3; i++) {
                f.tick(); near(h, 0, f.cart.position().distanceTo(held), 1e-8, "native dwell cannot move the cart");
                near(h, 0, MinecartPhysics.state(f.cart).actorWork, 0, "native dwell cannot grant drill work");
            }
            h.assertTrue(h.getLevel().getBlockState(drillTarget).is(Blocks.STONE), "drill leaves its target intact during native dwell");
            f.root.getContraption().setActorsActive(new ItemStack(extra.getBlock()), false);
            f.root.tick(); h.assertFalse(f.root.isStalled(), "disabling the real deployer releases its native pause");
            f.push(.4); f.tick(); h.assertTrue(h.getLevel().getBlockState(drillTarget).isAir(), "drill resumes paid feed after actual native dwell cancellation");
            f.cleanup(); h.succeed();
        });
    }
}
