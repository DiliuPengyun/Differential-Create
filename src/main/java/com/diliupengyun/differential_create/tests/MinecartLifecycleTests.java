package com.diliupengyun.differential_create.tests;

import static com.diliupengyun.differential_create.tests.MinecartOperationTests.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllAttachmentTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.minecart.CouplingHandler;
import com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Whole world ticks, fuel interactions and rail/support transitions. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class MinecartLifecycleTests {
    private MinecartLifecycleTests() {}
    private static void add(Collection<TestFunction> cases, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
            cases.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 180, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (var facing : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            add(cases, "minecart_lifecycle_" + facing.getName() + "_world_ticks", h -> world(h, facing));
            add(cases, "minecart_lifecycle_" + facing.getName() + "_derail", h -> derail(h, facing));
            add(cases, "minecart_lifecycle_" + facing.getName() + "_couple", h -> couple(h, facing));
            for (String mode : new String[]{"player_bare", "player_loaded", "automatic", "empty", "refill", "capacity"})
                add(cases, "minecart_lifecycle_" + facing.getName() + "_fuel_" + mode, h -> fuel(h, facing, mode));
        }
        return cases;
    }
    private static int items(Fixture f, net.minecraft.world.item.Item item) {
        var inventory = f.root.getContraption().getStorage().getAllItems(); int count = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) if (inventory.getStackInSlot(slot).is(item)) count += inventory.getStackInSlot(slot).getCount();
        return count;
    }
    private static Fixture drill(GameTestHelper h, Direction facing, String kind, boolean inventory) {
        return new Fixture(h, facing, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, facing), kind, inventory, false);
    }
    private static void world(GameTestHelper h, Direction facing) {
        final Fixture[] fixture = {null}; final Vec3[] initial = {null}; final double[] work = {0}; final boolean[] done = {false};
        // Register before GameTest starts iterating its scheduled-callback map.
        h.onEachTick(() -> {
            if (fixture[0] == null || done[0]) return;
            var f = fixture[0]; work[0] += MinecartPhysics.state(f.cart).actorWork;
            h.assertFalse(f.context.stall || f.root.isStalled() || f.cart.getData(AllAttachmentTypes.MINECART_CONTROLLER).isStalled(), "real world tick order cannot introduce a cutting pause");
        });
        h.runAfterDelay(5, () -> {
            var f = drill(h, facing, "rideable", true); fixture[0] = f; initial[0] = f.cart.position();
            for (int i = -6; i <= 6; i++) {
                var p = START.relative(facing, i); h.setBlock(p.below(), Blocks.REDSTONE_BLOCK);
                h.setBlock(p, Blocks.POWERED_RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT,
                    facing.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH).setValue(PoweredRailBlock.POWERED, true));
            }
            for (int i = 0; i < 3; i++) h.getLevel().setBlockAndUpdate(f.target.relative(facing, i), Blocks.STONE.defaultBlockState());
            f.push(.4);
        });
        h.runAtTickTime(35, () -> {
            done[0] = true; var f = fixture[0];
            for (int i = 0; i < 3; i++) h.assertTrue(h.getLevel().getBlockState(f.target.relative(facing, i)).isAir(), "naturally ticking cart cuts every block along the rail");
            near(h, 3, items(f, Items.COBBLESTONE), 0, "three native targets produce exactly three collected drops");
            double rating = com.simibubi.create.api.stress.BlockStressValues.getImpact(f.context.state.getBlock()); if (rating <= 0) rating = 2;
            near(h, rating * 3 * 1500 / 1200, work[0], 1e-8, "world tick order charges each real drill stroke once");
            h.assertTrue(f.cart.position().subtract(initial[0]).dot(vector(facing, 1)) > 2, "cart travels along real rails without resetting its position");
            f.cleanup(); h.succeed();
        });
    }
    private static void derail(GameTestHelper h, Direction facing) {
        h.runAfterDelay(5, () -> {
            var f = drill(h, facing, "rideable", false); f.partial(Blocks.STONE.defaultBlockState());
            h.setBlock(START, Blocks.AIR); f.push(.4); double paid = 0;
            for (int i = 0; i < 4; i++) { f.tick(); paid += MinecartPhysics.state(f.cart).actorWork; }
            h.assertTrue(paid > 0 && h.getLevel().getBlockState(f.target).isAir(), "removing the rail keeps a moving drill's work paid and functional");
            h.assertFalse(f.context.stall || f.root.isStalled(), "derailment does not restore the old cutting pause");
            h.setBlock(f.cart.blockPosition(), rail(facing)); f.push(.1); f.tick();
            h.assertTrue(f.cart.isOnRails(), "derailed vehicle can return to a newly placed rail");
            near(h, 0, MinecartPhysics.state(f.cart).actorWork, 0, "returning to rails cannot replay the finished cut");
            f.cleanup(); h.succeed();
        });
    }
    private static void fuel(GameTestHelper h, Direction facing, String mode) {
        h.runAfterDelay(5, () -> {
            var f = drill(h, facing, "furnace", mode.equals("automatic") || mode.equals("empty") || mode.equals("refill"));
            var motor = (MinecartFurnace) f.cart;
            if (mode.equals("player_bare")) { f.root.discard(); motor.ejectPassengers(); MinecartPhysics.refresh(motor); }
            var player = h.makeMockPlayer(GameType.SURVIVAL); player.setPos(motor.position().subtract(vector(facing, 1)));
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 2));
            if (mode.startsWith("player") || mode.equals("capacity")) {
                if (mode.equals("capacity")) { var tag = motor.saveWithoutId(new CompoundTag()); tag.putShort("Fuel", (short) 30000); motor.load(tag); }
                motor.interact(player, InteractionHand.MAIN_HAND);
                near(h, mode.equals("capacity") ? 30000 : 3600, motor.saveWithoutId(new CompoundTag()).getShort("Fuel"), 0, "actual player interaction respects the fuel capacity");
                near(h, mode.equals("capacity") ? 2 : 1, player.getItemInHand(InteractionHand.MAIN_HAND).getCount(), 0, "fuel capacity cannot consume an unused coal item");
                near(h, 0, motor.getDeltaMovement().horizontalDistance(), 0, "adding coal grants fuel rather than an immediate free velocity");
                if (!mode.equals("player_bare")) f.target(Blocks.STONE.defaultBlockState());
            } else {
                if (!mode.equals("automatic")) {
                    var inventory = f.root.getContraption().getStorage().getAllItems();
                    for (int slot = 0; slot < inventory.getSlots(); slot++) if (inventory.getStackInSlot(slot).is(Items.COAL)) inventory.extractItem(slot, 64, false);
                }
                f.target(Blocks.STONE.defaultBlockState()); f.push(.01); f.tick();
                if (mode.equals("empty") || mode.equals("refill")) {
                    near(h, 0, motor.saveWithoutId(new CompoundTag()).getShort("Fuel"), 0, "empty storage cannot invent fuel");
                    for (int i = 0; i < 7; i++) f.tick();
                    near(h, 0, motor.getDeltaMovement().horizontalDistance(), 0, "remaining kinetic energy runs out before checking the empty fuel supply");
                    double progress = f.context.data.getDouble("DCActorTravel"); for (int i = 0; i < 3; i++) f.tick();
                    near(h, progress, f.context.data.getDouble("DCActorTravel"), 0, "no fuel cannot finish the stopped cut");
                    if (mode.equals("empty")) { f.cleanup(); h.succeed(); return; }
                    net.neoforged.neoforge.items.ItemHandlerHelper.insertItem(f.root.getContraption().getStorage().getAllItems(), new ItemStack(Items.COAL), false);
                    f.root.tick();
                }
                near(h, 3600, motor.saveWithoutId(new CompoundTag()).getShort("Fuel"), 0, "native storage callback supplies one coal's burn time");
                near(h, mode.equals("automatic") ? 1 : 0, items(f, Items.COAL), 0, "automatic replenishment extracts one fuel item");
            }
            for (int i = 0; i < 6; i++) {
                motor.tick(); if (!mode.equals("player_bare")) { motor.positionRider(f.root); f.root.tick(); }
            }
            h.assertTrue(motor.getDeltaMovement().dot(vector(facing, 1)) > 0, "real fuel accelerates the vehicle in the chosen feed direction");
            if (!mode.equals("player_bare")) h.assertTrue(h.getLevel().getBlockState(f.target).isAir(), "fuel restores paid cutting after a real physical stop");
            motor.discard(); if (!mode.equals("player_bare")) f.root.discard(); h.succeed();
        });
    }
    private static void couple(GameTestHelper h, Direction facing) {
        h.runAfterDelay(5, () -> {
            var f = drill(h, facing, "rideable", false);
            var trailer = EntityType.MINECART.create(h.getLevel()); trailer.setPos(f.cart.position().add(vector(facing, 3))); h.getLevel().addFreshEntity(trailer);
            CapabilityMinecartController.tick(h.getLevel());
            var player = h.makeMockPlayer(GameType.SURVIVAL); player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.MINECART_COUPLING.asStack());
            h.assertTrue(CouplingHandler.tryToCoupleCarts(player, h.getLevel(), f.cart.getId(), trailer.getId()), "player can couple a mounted vehicle to a native trailer");
            h.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(), "successful coupling consumes exactly one link");
            double j = MinecartPhysics.refresh(f.cart);
            near(h, .01, MinecartPhysics.refresh(trailer), 0, "ordinary coupling does not duplicate the mounted body's mass onto its trailer");
            h.assertFalse(CouplingHandler.tryToCoupleCarts(player, h.getLevel(), trailer.getId(), f.cart.getId()), "duplicate coupling cannot form a two-cart loop");
            f.cart.getData(AllAttachmentTypes.MINECART_CONTROLLER).decouple();
            h.assertFalse(f.cart.getData(AllAttachmentTypes.MINECART_CONTROLLER).isLeadingCoupling()
                || trailer.getData(AllAttachmentTypes.MINECART_CONTROLLER).isConnectedToCoupling(), "native decoupling releases both endpoints");
            near(h, j, MinecartPhysics.refresh(f.cart), 0, "unlinking a separate trailer does not release the mounted structure");
            f.target(Blocks.STONE.defaultBlockState()); f.push(.4); f.tick();
            h.assertTrue(h.getLevel().getBlockState(f.target).isAir(), "native vehicle remains functional after player coupling and decoupling");
            f.cleanup(); trailer.discard(); h.succeed();
        });
    }
}
