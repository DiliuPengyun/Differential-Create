package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsMovingInteraction;
import com.simibubi.create.content.contraptions.mounted.*;
import com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Player operations during a paid job, rather than only initial actor states. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class MinecartOperationTests {
    static final BlockPos START = new BlockPos(8, 5, 8);
    private MinecartOperationTests() {}
    private static void add(Collection<TestFunction> cases, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
            cases.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 160, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (var facing : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH})
            for (boolean saw : new boolean[]{false, true})
                for (String operation : new String[]{"disable", "reverse", "remove", "replace", "same_type", "same_type_reload", "bedrock", "relocate", "disassemble"})
                    add(cases, "minecart_operation_" + facing.getName() + (saw ? "_saw_" : "_drill_") + operation,
                        h -> transition(h, facing, saw, operation));
        for (var facing : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            add(cases, "minecart_operation_" + facing.getName() + "_controls_reload", h -> controls(h, facing));
            for (String kind : new String[]{"rideable", "furnace", "chest"}) for (boolean dispenser : new boolean[]{false, true})
                add(cases, "minecart_operation_" + facing.getName() + "_pickup_" + kind + (dispenser ? "_dispenser" : "_place"),
                    h -> pickup(h, facing, kind, dispenser));
            for (boolean harvest : new boolean[]{true, false}) for (String operation : new String[]{"finish", "disable", "replace", "replace_moving", "same_type", "reload", "late"})
                add(cases, "minecart_operation_" + facing.getName() + (harvest ? "_harvester_" : "_plough_") + operation,
                    h -> farm(h, facing, harvest, operation));
            for (String operation : new String[]{"finish", "disable", "replace", "same_type", "reload", "refill", "reverse", "disassemble"})
                add(cases, "minecart_operation_" + facing.getName() + "_roller_" + operation, h -> roller(h, facing, operation));
        }
        return cases;
    }
    static int items(Fixture f, Item item) {
        var inventory = f.root.getContraption().getStorage().getAllItems(); int count = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) if (inventory.getStackInSlot(slot).is(item)) count += inventory.getStackInSlot(slot).getCount();
        return count;
    }
    private static double progress(MovementContext context) { return context.data.getCompound("DCActorJob").getDouble("Travel"); }
    private static void farm(GameTestHelper h, Direction facing, boolean harvest, String operation) {
        h.runAfterDelay(5, () -> {
            var f = new Fixture(h, facing, (harvest ? AllBlocks.MECHANICAL_HARVESTER : AllBlocks.MECHANICAL_PLOUGH)
                .getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing), "rideable", true, false);
            h.getLevel().setBlockAndUpdate(f.target.below(), (harvest ? Blocks.FARMLAND : Blocks.DIRT).defaultBlockState());
            f.target(harvest ? Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, operation.equals("late") ? 3 : 7) : Blocks.AIR.defaultBlockState());
            if (operation.equals("late")) {
                if (!harvest) h.getLevel().setBlockAndUpdate(f.target.below(), Blocks.BEDROCK.defaultBlockState());
                f.push(.01); f.tick(); f.push(0); f.tick();
                near(h, 0, MinecartPhysics.state(f.cart).actorWork, 0, "immature crop or unsuitable soil cannot take work");
                if (harvest) f.target(Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7));
                else h.getLevel().setBlockAndUpdate(f.target.below(), Blocks.DIRT.defaultBlockState());
            } else {
                f.push(.01); f.tick();
                h.assertTrue(ContraptionActorWork.of(f.context).pending() && progress(f.context) > 0, "moving farm tool holds a partially paid real job");
                double before = progress(f.context); f.push(0); f.tick();
                near(h, before, progress(f.context), 0, "stopped farm tool retains its work without native free progress");
                switch (operation) {
                    case "disable" -> {
                        f.root.getContraption().setActorsActive(ItemStack.EMPTY, false); f.tick();
                        h.assertFalse(ContraptionActorWork.of(f.context).pending(), "disabling a partially working farm tool cancels its job");
                        f.root.getContraption().setActorsActive(ItemStack.EMPTY, true);
                    }
                    case "replace", "replace_moving" -> {
                        if (harvest) f.target(Blocks.CARROTS.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7));
                        else h.getLevel().setBlockAndUpdate(f.target.below(), Blocks.GRASS_BLOCK.defaultBlockState());
                        if (operation.equals("replace")) { f.tick(); near(h, 0, progress(f.context), 0, "changed crop or soil does not inherit the previous job's work"); }
                    }
                    case "same_type" -> {
                        var pos = harvest ? f.target : f.target.below(); var state = h.getLevel().getBlockState(pos);
                        h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()); h.getLevel().setBlockAndUpdate(pos, state);
                        f.tick(); near(h, 0, progress(f.context), 0, "same-type crop/soil replacement cancels the previous physical target");
                    }
                    case "reload" -> { f.reload(); near(h, before, progress(f.context), 0, "actual farm job progress survives native reload"); }
                }
            }
            f.push(.4); f.tick();
            h.assertTrue(harvest ? h.getLevel().getBlockState(f.target).getValue(BlockStateProperties.AGE_7) == 0
                : h.getLevel().getBlockState(f.target.below()).is(Blocks.FARMLAND), "physical feed completes real farming after " + operation);
            h.assertTrue(MinecartPhysics.state(f.cart).actorWork > 0, "farming consumes the cart's kinetic energy");
            if (operation.equals("replace_moving")) {
                double rating = BlockStressValues.getImpact(f.context.state.getBlock()); if (rating <= 0) rating = 2;
                near(h, rating * 1000 / 1200, MinecartPhysics.state(f.cart).actorWork, 1e-8, "moving replacement pays exactly the new crop or soil job's work");
            }
            h.assertFalse(f.context.stall || f.root.isStalled(), "farming never adds a native structure pause");
            int wheat = items(f, Items.WHEAT); f.push(0); for (int i = 0; i < 3; i++) f.tick();
            near(h, wheat, items(f, Items.WHEAT), 0, "native follow-up actor ticks cannot duplicate harvested drops");
            f.cleanup(); h.succeed();
        });
    }
    private static void roller(GameTestHelper h, Direction facing, String operation) {
        h.runAfterDelay(5, () -> {
            var f = new Fixture(h, facing, AllBlocks.MECHANICAL_ROLLER.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing), "rideable", true, false);
            f.context.blockEntityData.putInt("ScrollValue", 1);
            f.context.blockEntityData.put("Filter", new ItemStack(Items.STONE).save(h.getLevel().registryAccess()));
            f.target(Blocks.AIR.defaultBlockState()); h.getLevel().setBlockAndUpdate(f.target.below(), Blocks.BEDROCK.defaultBlockState());
            if (operation.equals("refill")) {
                var inventory = f.root.getContraption().getStorage().getAllItems();
                for (int slot = 0; slot < inventory.getSlots(); slot++) if (inventory.getStackInSlot(slot).is(Items.STONE)) inventory.extractItem(slot, 64, false);
                f.push(.01); f.tick(); f.push(0); f.tick();
                h.assertFalse(ContraptionActorWork.of(f.context).pending(), "empty inventory cannot start paving");
                ItemHandlerHelper.insertItem(inventory, new ItemStack(Items.STONE, 16), false);
            } else {
                f.push(.01); f.tick();
                h.assertTrue(ContraptionActorWork.of(f.context).pending() && progress(f.context) > 0, "roller reserves material for a partially paid physical job");
                near(h, 15, items(f, Items.STONE), 0, "one queued paving block reserves exactly one item");
                double before = progress(f.context); f.push(0); f.tick();
                near(h, before, progress(f.context), 0, "stopped roller retains its partial work and reservation");
                switch (operation) {
                    case "disable" -> {
                        f.root.getContraption().setActorsActive(ItemStack.EMPTY, false); f.tick();
                        h.assertFalse(ContraptionActorWork.of(f.context).pending(), "disabled roller cancels its reservation");
                        near(h, 16, items(f, Items.STONE), 0, "disabled roller refunds its reserved block once");
                        f.root.getContraption().setActorsActive(ItemStack.EMPTY, true);
                    }
                    case "reverse" -> {
                        f.push(-.01); f.tick(); near(h, 16, items(f, Items.STONE), 0, "retreating roller refunds its reserved material"); f.push(0);
                    }
                    case "replace" -> {
                        f.target(Blocks.BEDROCK.defaultBlockState()); f.tick();
                        near(h, 16, items(f, Items.STONE), 0, "player's replacement receives no paving and refunds the material");
                        f.target(Blocks.AIR.defaultBlockState());
                    }
                    case "same_type" -> {
                        f.target(Blocks.BEDROCK.defaultBlockState()); f.target(Blocks.AIR.defaultBlockState()); f.tick();
                        near(h, 0, progress(f.context), 0, "same-state paving replacement cannot retain the old target's work");
                    }
                    case "reload" -> {
                        f.reload(); near(h, before, progress(f.context), 0, "reloaded roller preserves paid progress");
                        near(h, 15, items(f, Items.STONE), 0, "reload does not extract or refund the reserved block a second time");
                    }
                    case "disassemble" -> {
                        f.root.disassemble(); f.cart.ejectPassengers();
                        h.assertFalse(ContraptionActorWork.of(f.context).pending(), "disassembly cancels the partial paving job");
                        int returned = 0;
                        for (var pos : BlockPos.betweenClosed(h.absolutePos(START).offset(-4, 0, -4), h.absolutePos(START).offset(4, 6, 4)))
                            if (h.getLevel().getBlockEntity(pos) instanceof ChestBlockEntity chest)
                                for (int slot = 0; slot < chest.getContainerSize(); slot++) if (chest.getItem(slot).is(Items.STONE)) returned += chest.getItem(slot).getCount();
                        near(h, 16, returned, 0, "disassembled native storage contains the refunded material exactly once");
                        f.cart.discard(); h.succeed(); return;
                    }
                }
            }
            f.push(.4); f.tick();
            h.assertTrue(h.getLevel().getBlockState(f.target).is(Blocks.STONE), "roller places its paid block after " + operation);
            near(h, 15, items(f, Items.STONE), 0, "one finished paving block consumes exactly one item");
            h.assertFalse(f.context.stall || f.root.isStalled(), "paving never pauses the native structure");
            f.push(0); for (int i = 0; i < 3; i++) f.tick();
            near(h, 15, items(f, Items.STONE), 0, "native follow-up ticks cannot repeat material extraction");
            f.cleanup(); h.succeed();
        });
    }
    static void near(GameTestHelper h, double expected, double actual, double tolerance, String label) {
        h.assertTrue(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
            label + ": expected=" + expected + ", actual=" + actual);
    }
    static Vec3 vector(Direction facing, double speed) { return Vec3.atLowerCornerOf(facing.getNormal()).scale(speed); }
    static BlockState rail(Direction facing) {
        return Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE,
            facing.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH);
    }
    static final class Fixture {
        final GameTestHelper h;
        final Direction facing;
        AbstractMinecart cart;
        AbstractMinecart support;
        OrientedContraptionEntity root;
        MovementContext context;
        final BlockPos target;
        Fixture(GameTestHelper h, Direction facing, BlockState tool) {
            this(h, facing, tool, "rideable", false, false);
        }
        Fixture(GameTestHelper h, Direction facing, BlockState tool, String kind, boolean chest, boolean controls) {
            this(h, facing, tool, kind, chest, controls, null);
        }
        Fixture(GameTestHelper h, Direction facing, BlockState tool, String kind, boolean chest, boolean controls, BlockState extra) {
            this(h, facing, tool, kind, chest, controls, extra, 0);
        }
        Fixture(GameTestHelper h, Direction facing, BlockState tool, String kind, boolean chest, boolean controls, BlockState extra, int arm) {
            this(h, facing, tool, kind, chest, controls, extra, arm, false, false);
        }
        Fixture(GameTestHelper h, Direction facing, BlockState tool, String kind, boolean chest, boolean controls, BlockState extra, int arm, boolean coupled, boolean forwardArm) {
            this.h = h; this.facing = facing;
            for (int i = -6; i <= 6; i++) {
                var p = START.relative(facing, i);
                h.setBlock(p.below(), Blocks.STONE); h.setBlock(p, rail(facing));
                for (int y = 1; y <= 5; y++) h.setBlock(p.above(y), Blocks.AIR);
            }
            cart = switch (kind) {
                case "furnace" -> EntityType.FURNACE_MINECART.create(h.getLevel());
                case "chest" -> EntityType.CHEST_MINECART.create(h.getLevel());
                default -> EntityType.MINECART.create(h.getLevel());
            };
            cart.setPos(Vec3.atCenterOf(h.absolutePos(START)).add(0, -.4375, 0)); h.getLevel().addFreshEntity(cart);
            if (coupled) {
                support = EntityType.MINECART.create(h.getLevel()); support.setPos(cart.position().add(vector(facing, 4)));
                h.getLevel().addFreshEntity(support); CapabilityMinecartController.tick(h.getLevel());
            }
            h.setBlock(START, AllBlocks.CART_ASSEMBLER.getDefaultState().setValue(CartAssemblerBlock.RAIL_SHAPE,
                facing.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH)
                .setValue(CartAssemblerBlock.RAIL_TYPE, CartAssembleRailType.REGULAR));
            h.setBlock(START.below(), Blocks.REDSTONE_BLOCK);
            int height = tool.is(AllBlocks.MECHANICAL_ROLLER.get()) ? 4 : 2;
            for (int y = 1; y < height; y++) h.setBlock(START.above(y), Blocks.SLIME_BLOCK);
            var armFacing = forwardArm ? (arm > 0 ? facing : facing.getOpposite()) : (arm > 0 ? facing.getClockWise() : facing.getCounterClockWise());
            for (int i = 0; i < Math.abs(arm); i++)
                h.setBlock(START.above(height).relative(armFacing, i), Blocks.SLIME_BLOCK);
            h.setBlock(START.above(height).relative(armFacing, Math.abs(arm)), tool);
            if (coupled) {
                h.setBlock(START.relative(facing, 4), h.getBlockState(START));
                h.setBlock(START.relative(facing, 4).below(), Blocks.REDSTONE_BLOCK);
                for (int i = 0; i <= 4; i++) h.setBlock(START.relative(facing, i).above(), Blocks.SLIME_BLOCK);
            }
            if (chest) {
                var pos = START.above().relative(facing.getClockWise()); h.setBlock(pos, Blocks.CHEST);
                ((ChestBlockEntity) h.getBlockEntity(pos)).setItem(0, new ItemStack(Items.STONE, 16));
                ((ChestBlockEntity) h.getBlockEntity(pos)).setItem(1, new ItemStack(Items.COAL, 2));
            }
            if (controls) h.setBlock(START.above().relative(facing.getCounterClockWise()), AllBlocks.CONTRAPTION_CONTROLS.getDefaultState());
            if (extra != null) h.setBlock(START.above().relative(facing.getCounterClockWise()), extra);
            CartAssemblerBlockEntity assembler = h.getBlockEntity(START); assembler.tryAssemble(cart);
            root = (OrientedContraptionEntity) MinecartPhysics.mounted(cart);
            h.assertTrue(root != null, "native assembler creates the operation fixture");
            if (coupled) h.assertTrue(root.getCouplingId() != null, "two real assemblers produce a shared coupled structure");
            ((MountedContraption) root.getContraption()).rotationMode = CartAssemblerBlockEntity.CartMovementMode.ROTATION_LOCKED;
            h.setBlock(START, rail(facing)); cart.positionRider(root);
            if (coupled) h.setBlock(START.relative(facing, 4), rail(facing));
            context = root.getContraption().getActors().stream().filter(a -> a.getLeft().state().is(tool.getBlock()))
                .findFirst().orElseThrow().getRight();
            if (tool.is(AllBlocks.MECHANICAL_ROLLER.get())) {
                // Keep this accounting fixture within one paving column. A
                // separate column must legitimately consume another item.
                cart.setPos(cart.position().subtract(vector(facing, .4))); cart.positionRider(root);
            }
            target = contact();
        }
        BlockPos contact() {
            var behaviour = MovementBehaviour.REGISTRY.get(context.state);
            return BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour.getActiveAreaOffset(context)), 1));
        }
        void tick() { cart.tick(); cart.positionRider(root); root.tick(); }
        void push(double speed) { cart.setDeltaMovement(vector(facing, speed)); }
        void target(BlockState state) { h.getLevel().setBlockAndUpdate(target, state); }
        void reload() {
            var tag = new CompoundTag(); h.assertTrue(cart.save(tag), "native cart save includes mounted actors and inventory");
            root.discard(); cart.ejectPassengers(); cart.discard();
            cart = (AbstractMinecart) EntityType.loadEntityRecursive(tag, h.getLevel(), e -> e);
            h.assertTrue(cart != null, "native recursive entity reload succeeds"); h.getLevel().addFreshEntityWithPassengers(cart);
            root = (OrientedContraptionEntity) MinecartPhysics.mounted(cart);
            var block = context.state.getBlock();
            context = root.getContraption().getActors().stream().filter(a -> a.getLeft().state().is(block)).findFirst().orElseThrow().getRight();
        }
        void partial(BlockState state) {
            target(state); push(.01);
            for (int i = 0; i < 8; i++) tick();
            h.assertTrue(context.data.getDouble("DCActorTravel") > 0, "fixture first pays part of a real cut");
            near(h, 0, cart.getDeltaMovement().horizontalDistance(), 0, "insufficient kinetic energy stops the fixture");
            h.assertTrue(h.getLevel().getBlockState(target).equals(state), "partial cutting preserves the target");
        }
        void cleanup() { root.discard(); cart.ejectPassengers(); cart.discard(); if (support != null) support.discard(); }
    }
    private static void controls(GameTestHelper h, Direction facing) {
        h.runAfterDelay(5, () -> {
            var f = new Fixture(h, facing, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, facing),
                "rideable", false, true);
            f.partial(Blocks.STONE.defaultBlockState());
            var player = h.makeMockPlayer(GameType.SURVIVAL);
            var interaction = new ContraptionControlsMovingInteraction();
            var button = f.root.getContraption().getActors().stream().filter(a -> AllBlocks.CONTRAPTION_CONTROLS.has(a.getLeft().state()))
                .findFirst().orElseThrow().getLeft().pos();
            h.assertTrue(interaction.handlePlayerInteraction(player, InteractionHand.MAIN_HAND, button, f.root), "actual controls button accepts the player's click");
            h.assertTrue(f.context.disabled, "button disables the already cutting drill");
            h.assertFalse(f.context.data.contains("BreakingPos") || f.context.data.contains("DCActorTravel"), "controls cancel already paid partial cutting");
            f.reload(); h.assertTrue(f.context.disabled, "disabled actor selection survives native entity reload");
            f.tick(); near(h, 0, MinecartPhysics.state(f.cart).actorWork, 0, "disabled loaded tool consumes no work");
            h.assertTrue(interaction.handlePlayerInteraction(player, InteractionHand.MAIN_HAND, button, f.root), "second button click reenables the loaded tool");
            h.assertFalse(f.context.disabled, "reloaded tool reenables through its controls");
            f.push(.4); f.tick(); h.assertTrue(h.getLevel().getBlockState(f.target).isAir(), "reenabled tool finishes a fresh paid cut");
            f.cleanup(); h.succeed();
        });
    }
    private static void pickup(GameTestHelper h, Direction facing, String kind, boolean dispenser) {
        h.runAfterDelay(5, () -> {
            var f = new Fixture(h, facing, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, facing), kind, true, false);
            // Stop automatic refuelling while preparing the partial-cut pickup.
            if (kind.equals("furnace")) {
                var inventory = f.root.getContraption().getStorage().getAllItems();
                for (int slot = 0; slot < inventory.getSlots(); slot++) if (inventory.getStackInSlot(slot).is(Items.COAL)) inventory.extractItem(slot, 64, false);
            }
            f.partial(Blocks.STONE.defaultBlockState()); double inertia = MinecartPhysics.refresh(f.cart);
            if (kind.equals("furnace")) ItemHandlerHelper.insertItem(f.root.getContraption().getStorage().getAllItems(), new ItemStack(Items.COAL, 2), false);
            var player = h.makeMockPlayer(GameType.SURVIVAL); player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
            var event = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, f.cart);
            MinecartContraptionItem.wrenchCanBeUsedToPickUpMinecartContraptions(event);
            h.assertTrue(event.isCanceled() && f.cart.isRemoved() && f.root.isRemoved(), "actual wrench interaction picks up the partially working vehicle");
            h.assertFalse(f.context.data.contains("DCActorTravel") || f.context.data.contains("BreakingPos"), "item pickup clears old world cutting progress");
            var item = player.getInventory().items.stream().filter(s -> s.getItem() instanceof MinecartContraptionItem).findFirst().orElseThrow();
            var place = START.relative(facing, 3); var absolute = h.absolutePos(place);
            if (dispenser) {
                var sourcePos = place.relative(facing.getOpposite());
                var sourceState = Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, facing); h.setBlock(sourcePos, sourceState);
                DispenserBlockEntity be = h.getBlockEntity(sourcePos);
                DispenserBlock.DISPENSER_REGISTRY.get(item.getItem()).dispense(new BlockSource(h.getLevel(), h.absolutePos(sourcePos), sourceState, be), item);
                // A backward-facing picked-up drill can retreat into the dispenser;
                // remove that real cart obstruction before checking its new cut.
                h.setBlock(sourcePos, rail(facing));
            } else {
                player.setYRot(facing.toYRot()); player.setItemInHand(InteractionHand.MAIN_HAND, item);
                var use = new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false));
                h.assertTrue(item.useOn(use).consumesAction(), "native contraption item placement accepts the chosen rail");
            }
            h.assertTrue(item.isEmpty(), "one placement consumes exactly one contraption item");
            f.cart = h.getLevel().getEntitiesOfClass(AbstractMinecart.class, h.getBounds()).stream().filter(c -> MinecartPhysics.mounted(c) != null).findFirst().orElseThrow();
            f.root = (OrientedContraptionEntity) MinecartPhysics.mounted(f.cart);
            f.context = f.root.getContraption().getActors().stream().filter(a -> AllBlocks.MECHANICAL_DRILL.has(a.getLeft().state())).findFirst().orElseThrow().getRight();
            near(h, inertia, MinecartPhysics.refresh(f.cart), 1e-14, "placing an item reconstructs its mass including storage");
            near(h, 16, items(f, Items.STONE), 0, "picked-up storage retains every unused paving item");
            near(h, 2, items(f, Items.COAL), 0, "picked-up storage retains fuel before the placed engine ticks");
            near(h, 0, f.context.data.getDouble("DCActorTravel"), 0, "placed item cannot inherit another world's partial cut");
            f.cart.positionRider(f.root); var target = f.contact(); h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
            var feed = f.root.applyRotation(Vec3.atLowerCornerOf(f.context.state.getValue(BlockStateProperties.FACING).getNormal()), 1);
            f.cart.setDeltaMovement(feed.scale(.4));
            for (int i = 0; i < 3 && !h.getLevel().getBlockState(target).isAir(); i++) f.tick();
            h.assertTrue(h.getLevel().getBlockState(target).isAir(), "placed or dispensed native vehicle performs a new paid cut");
            h.assertTrue(h.getLevel().getBlockState(f.target).is(Blocks.STONE), "moving the item leaves the original unfinished target intact");
            f.cleanup(); h.succeed();
        });
    }
    private static void transition(GameTestHelper h, Direction facing, boolean saw, String operation) {
        h.runAfterDelay(5, () -> {
            var f = new Fixture(h, facing, (saw ? AllBlocks.MECHANICAL_SAW : AllBlocks.MECHANICAL_DRILL)
                .getDefaultState().setValue(BlockStateProperties.FACING, facing));
            var original = (saw ? Blocks.OAK_LOG : Blocks.STONE).defaultBlockState(); f.partial(original);
            if (operation.equals("disassemble")) {
                double velocity = f.cart.getDeltaMovement().horizontalDistance();
                f.root.disassemble(); f.cart.ejectPassengers();
                near(h, .01, MinecartPhysics.refresh(f.cart), 0, "disassembly releases the mass during a partial cut");
                near(h, velocity, f.cart.getDeltaMovement().horizontalDistance(), 0, "disassembly cannot restore spent energy");
                h.assertFalse(f.context.data.contains("DCActorTravel") || f.context.data.contains("BreakingPos"), "disassembly cancels partial work");
                h.assertTrue(h.getLevel().getBlockState(f.target).equals(original), "disassembly does not complete a partial cut");
                f.cart.discard(); h.succeed(); return;
            }
            switch (operation) {
                case "disable" -> f.root.getContraption().setActorsActive(net.minecraft.world.item.ItemStack.EMPTY, false);
                case "reverse" -> f.push(-.01);
                case "remove" -> f.target(Blocks.AIR.defaultBlockState());
                case "replace" -> f.target((saw ? Blocks.BIRCH_LOG : Blocks.DEEPSLATE).defaultBlockState());
                case "same_type", "same_type_reload" -> {
                    f.target(Blocks.AIR.defaultBlockState()); f.target(original);
                    if (operation.equals("same_type_reload")) f.reload();
                }
                case "bedrock" -> f.target(Blocks.BEDROCK.defaultBlockState());
                case "relocate" -> f.cart.setPos(f.cart.position().add(vector(facing, 2)));
                default -> throw new IllegalArgumentException(operation);
            }
            f.tick();
            near(h, 0, f.context.data.getDouble("DCActorTravel"), 0, "operation discards work belonging to the previous target: " + operation);
            near(h, 0, MinecartPhysics.state(f.cart).actorWork, 0, "state change cannot charge unaccepted cutting work");
            if (!operation.equals("replace") && !operation.startsWith("same_type")) h.assertFalse(f.context.data.contains("BreakingPos"), "old contact is cancelled: " + operation);
            if (operation.equals("disable")) f.root.getContraption().setActorsActive(net.minecraft.world.item.ItemStack.EMPTY, true);
            if (operation.equals("relocate")) {
                f.cart.setPos(f.cart.position().subtract(vector(facing, 2))); f.cart.positionRider(f.root);
            }
            f.target(original); f.push(0); f.tick();
            near(h, 0, f.context.data.getDouble("DCActorTravel"), 0, "reacquiring a target at rest cannot restore cancelled progress");
            h.assertTrue(f.context.data.contains("BreakingPos"), "returning to active contact reacquires the target");
            f.push(.4); f.tick();
            h.assertTrue(h.getLevel().getBlockState(f.target).isAir(), "fresh physical feed completes the target after " + operation);
            double rating = BlockStressValues.getImpact(f.context.state.getBlock()); if (rating <= 0) rating = 2;
            near(h, rating * Math.max(.1, original.getDestroySpeed(h.getLevel(), f.target)) * 1000 / 1200,
                MinecartPhysics.state(f.cart).actorWork, 1e-8, "resumed tool pays a whole fresh stroke");
            f.cleanup(); h.succeed();
        });
    }
}
