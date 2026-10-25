package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllAttachmentTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.mounted.*;
import com.simibubi.create.content.contraptions.minecart.CouplingPhysics;
import com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController;
import com.simibubi.create.content.redstone.rail.ControllerRailBlock;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native entity ticks compared with Minecraft's independent velocity recurrences. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class MinecartIntegrationTests {
    private static final BlockPos START = new BlockPos(8, 5, 8);
    private MinecartIntegrationTests() {}
    private static void add(Collection<TestFunction> cases, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
            cases.add(new TestFunction("minecart_" + name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 100, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (var axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) for (int sign : new int[]{-1, 1})
            for (String kind : new String[]{"coast", "powered", "powered_start", "powered_rider", "brake", "furnace", "unfuelled", "chest_empty", "chest_full", "hopper", "tnt", "controller", "rider", "water"}) {
                String name = "minecart_" + axis.name().toLowerCase() + "_" + (sign > 0 ? "positive_" : "negative_") + kind;
                add(cases, name, h -> nativeMotion(h, axis, sign, kind));
            }
        add(cases, "minecart_assembly_mass", h -> assembly(h, false));
        add(cases, "minecart_assembly_furnace", h -> assembly(h, true));
        add(cases, "minecart_loaded_controller", MinecartIntegrationTests::controller);
        add(cases, "minecart_mass_coupling", MinecartIntegrationTests::coupling);
        add(cases, "minecart_two_supports", MinecartIntegrationTests::twoSupports);
        add(cases, "minecart_two_supports_tool", h -> twoSupports(h, true));
        add(cases, "minecart_native_reload", MinecartIntegrationTests::reload);
        add(cases, "minecart_self_shadow", MinecartIntegrationTests::selfShadow);
        for (var axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) for (int sign : new int[]{-1, 1})
            for (String mode : new String[]{"drill", "saw", "weak", "disabled", "reverse", "replacement", "reload", "falling", "wall", "powered", "furnace", "native_pause", "dwell"})
                add(cases, "minecart_tool_" + axis.name().toLowerCase() + (sign > 0 ? "_positive_" : "_negative_") + mode,
                    h -> tool(h, axis, sign, mode));
        return cases;
    }
    private static void near(GameTestHelper h, double expected, double actual, double tolerance, String label) {
        h.assertTrue(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance, label + ": expected=" + expected + ", actual=" + actual);
    }
    private static Vec3 axis(Direction.Axis axis, double value) { return axis == Direction.Axis.X ? new Vec3(value, 0, 0) : new Vec3(0, 0, value); }
    private static double component(Direction.Axis axis, Vec3 v) { return axis == Direction.Axis.X ? v.x : v.z; }
    private static void rails(GameTestHelper h, Direction.Axis axis, BlockState rail) {
        for (int i = -5; i <= 5; i++) {
            var p = axis == Direction.Axis.X ? START.offset(i, 0, 0) : START.offset(0, 0, i);
            h.setBlock(p.below(), Blocks.STONE);
            h.setBlock(p, rail);
            for (int y = 1; y <= 4; y++) h.setBlock(p.above(y), Blocks.AIR);
        }
    }
    private static AbstractMinecart spawn(GameTestHelper h, String kind) {
        var type = switch (kind) {
            case "furnace", "unfuelled" -> EntityType.FURNACE_MINECART;
            case "chest_empty", "chest_full" -> EntityType.CHEST_MINECART;
            case "hopper" -> EntityType.HOPPER_MINECART;
            case "tnt" -> EntityType.TNT_MINECART;
            default -> EntityType.MINECART;
        };
        var cart = (AbstractMinecart) type.create(h.getLevel());
        var pos = Vec3.atCenterOf(h.absolutePos(START)).add(0, -.4375, 0);
        cart.setPos(pos); h.getLevel().addFreshEntity(cart); return cart;
    }
    private static void nativeMotion(GameTestHelper h, Direction.Axis axis, int sign, String kind) {
        RailShape shape = axis == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
        var rail = kind.startsWith("powered") || kind.equals("brake")
            ? Blocks.POWERED_RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, shape)
                .setValue(PoweredRailBlock.POWERED, kind.startsWith("powered"))
            : kind.equals("controller") ? AllBlocks.CONTROLLER_RAIL.getDefaultState().setValue(ControllerRailBlock.SHAPE, shape)
                .setValue(ControllerRailBlock.POWER, 15).setValue(ControllerRailBlock.BACKWARDS, sign > 0)
            : Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, shape);
        if (kind.equals("water")) rail = rail.setValue(BlockStateProperties.WATERLOGGED, true);
        final var testRail = rail;
        rails(h, axis, rail);
        h.runAfterDelay(5, () -> {
            // Neighbour updates can depower the fixture; set its native state at each tick.
            if (kind.startsWith("powered") || kind.equals("controller")) h.setBlock(START.below(), Blocks.REDSTONE_BLOCK);
            if (kind.equals("powered_start"))
                h.setBlock(START.relative(Direction.fromAxisAndDirection(axis, sign > 0 ? Direction.AxisDirection.NEGATIVE : Direction.AxisDirection.POSITIVE)), Blocks.STONE);
            var cart = spawn(h, kind); var start = cart.position();
            var rider = kind.endsWith("rider") ? EntityType.PIG.create(h.getLevel()) : null;
            if (rider != null) { rider.setPos(start); h.getLevel().addFreshEntity(rider); h.assertTrue(rider.startRiding(cart), "real rider enters the vanilla minecart"); }
            if (cart instanceof MinecartFurnace) {
                var tag = cart.saveWithoutId(new CompoundTag());
                tag.putShort("Fuel", (short) (kind.equals("furnace") ? 3600 : 0));
                tag.putDouble("PushX", axis == Direction.Axis.X && kind.equals("furnace") ? sign : 0);
                tag.putDouble("PushZ", axis == Direction.Axis.Z && kind.equals("furnace") ? sign : 0);
                cart.load(tag);
            }
            if (kind.equals("chest_full") && cart instanceof AbstractMinecartContainer container)
                for (int i = 0; i < container.getContainerSize(); i++) container.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            double expected = kind.equals("powered_start") ? 0 : sign * .4; cart.setDeltaMovement(axis(axis, expected));
            for (int tick = 0; tick < 64; tick++) {
                cart.setPos(start); h.getLevel().setBlock(h.absolutePos(START), testRail, 2);
                expected = Math.copySign(Math.min(2, Math.abs(expected)), expected);
                double travelling = Math.min(cart.getMaxSpeedWithRail(), Math.abs(expected) * (rider == null ? 1 : .75));
                if (kind.equals("brake")) {
                    expected = Math.abs(expected) < .03 ? 0 : expected * .5;
                    travelling = Math.min(cart.getMaxSpeedWithRail(), Math.abs(expected));
                }
                if (kind.equals("furnace")) expected = .8 * expected + sign;
                else if (kind.equals("unfuelled")) expected *= .98;
                double factor = kind.equals("chest_empty") || kind.equals("hopper") ? (double) (.98F + 15 * .001F)
                    : kind.equals("chest_full") ? (double) .98F : rider != null ? .997 : .96;
                // Vanilla updates its water contact at the end of the first tick.
                if (kind.equals("water") && tick > 0) {
                    h.assertTrue(cart.isInWater(), "waterlogged track establishes actual fluid contact");
                    factor *= .95F;
                }
                expected *= factor;
                if (kind.startsWith("powered")) expected = Math.abs(expected) > .01 ? expected + sign * .06
                    : kind.equals("powered_start") ? sign * .02 : expected;
                if (kind.equals("controller")) expected = sign * cart.getMaxSpeedWithRail();
                cart.tick();
                near(h, expected, component(axis, cart.getDeltaMovement()), 2e-12, "native empty-cart velocity at tick " + tick + " " + kind);
                near(h, sign * travelling, component(axis, cart.position().subtract(start)), 1e-8, "native rail displacement at tick " + tick + " " + kind);
                near(h, MinecartMotion.BASE_INERTIA, MinecartPhysics.state(cart).inertia, 0, "unassembled baseline inertia");
            }
            if (kind.equals("furnace")) near(h, 3536, cart.saveWithoutId(new CompoundTag()).getShort("Fuel"), 0, "native fuel duration is preserved");
            if (rider != null) rider.discard();
            cart.discard(); h.succeed();
        });
    }
    private static CartAssemblerBlockEntity fixture(GameTestHelper h, AbstractMinecart cart) {
        var station = AllBlocks.CART_ASSEMBLER.getDefaultState().setValue(CartAssemblerBlock.RAIL_SHAPE, RailShape.EAST_WEST)
            .setValue(CartAssemblerBlock.RAIL_TYPE, CartAssembleRailType.REGULAR).setValue(CartAssemblerBlock.POWERED, true);
        h.setBlock(START.below(), Blocks.STONE); h.setBlock(START, station);
        h.setBlock(START.above(), Blocks.SLIME_BLOCK); h.setBlock(START.above(2), Blocks.OAK_PLANKS);
        h.setBlock(START.north(), Blocks.REDSTONE_BLOCK);
        CartAssemblerBlockEntity assembler = h.getBlockEntity(START); assembler.tryAssemble(cart);
        h.assertTrue(MinecartPhysics.mounted(cart) != null, "native cart assembler mounts its real structure");
        return assembler;
    }
    private static void assembly(GameTestHelper h, boolean furnace) {
        rails(h, Direction.Axis.X, Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST));
        h.runAfterDelay(5, () -> {
            var cart = spawn(h, furnace ? "furnace" : "coast");
            cart.setDeltaMovement(new Vec3(.4, 0, 0));
            if (cart instanceof MinecartFurnace motor) {
                var tag = motor.saveWithoutId(new CompoundTag()); tag.putShort("Fuel", (short) 3600); tag.putDouble("PushX", 1); motor.load(tag);
            }
            fixture(h, cart); var root = MinecartPhysics.mounted(cart);
            double j = .01 + ContraptionPhysics.shape(root.getContraption()).linearInertia();
            near(h, j, MinecartPhysics.refresh(cart), 1e-15, "mounted cart uses the existing piston structure inertia");
            near(h, .004 / j, cart.getDeltaMovement().x, 1e-12, "assembly conserves momentum when resting mass joins");
            double speed = cart.getDeltaMovement().x;
            MinecartPhysics.refresh(cart); near(h, speed, cart.getDeltaMovement().x, 0, "repeated mass reads cannot charge attachment twice");
            MinecartPhysics.state(cart).inertia = MinecartMotion.BASE_INERTIA; cart.setDeltaMovement(new Vec3(.4, 0, 0));
            var lateDrive = MinecartPhysics.impulse(cart, cart.getDeltaMovement(), Vec3.ZERO);
            near(h, speed, lateDrive.x, 1e-12, "rail drive's captured motion includes any newly discovered attachment loss");
            MinecartPhysics.state(cart).inertia = MinecartMotion.BASE_INERTIA; cart.setDeltaMovement(new Vec3(.4, 0, 0));
            var lateBrake = MinecartPhysics.brake(cart, cart.getDeltaMovement());
            near(h, speed * Math.pow(.5, .01 / j), lateBrake.x, 1e-12, "late mass discovery also precedes the rail brake");
            cart.setDeltaMovement(new Vec3(speed, 0, 0));
            var saved = cart.saveWithoutId(new CompoundTag());
            MinecartPhysics.load(cart, saved); MinecartPhysics.refresh(cart);
            near(h, speed, cart.getDeltaMovement().x, 0, "reloading the saved mass summary cannot repeat assembly loss");
            if (cart instanceof MinecartFurnace motor) {
                near(h, 1, motor.xPush, 0, "assembly preserves the fueled engine direction");
                root.tick(); near(h, speed, cart.getDeltaMovement().x, 0, "native fuel callback cannot normalize the loaded cart to free speed");
            }
            root.disassemble(); cart.ejectPassengers(); MinecartPhysics.refresh(cart);
            near(h, .01, MinecartPhysics.state(cart).inertia, 0, "disassembly releases the structure inertia");
            near(h, speed, cart.getDeltaMovement().x, 0, "removing a structure does not accelerate the remaining cart");
            cart.discard(); h.succeed();
        });
    }
    private static void controller(GameTestHelper h) {
        rails(h, Direction.Axis.X, Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST));
        h.runAfterDelay(5, () -> {
            var cart = spawn(h, "coast"); fixture(h, cart);
            var state = AllBlocks.CONTROLLER_RAIL.getDefaultState().setValue(ControllerRailBlock.SHAPE, RailShape.EAST_WEST)
                .setValue(ControllerRailBlock.BACKWARDS, true).setValue(ControllerRailBlock.POWER, 15);
            h.getLevel().setBlock(h.absolutePos(START), state, 2);
            cart.setDeltaMovement(Vec3.ZERO);
            AllBlocks.CONTROLLER_RAIL.get().onMinecartPass(state, h.getLevel(), h.absolutePos(START), cart);
            h.assertTrue(cart.getDeltaMovement().x > 0 && cart.getDeltaMovement().x < .4, "loaded controller rail uses finite traction instead of assigning its speed target");
            var root = MinecartPhysics.mounted(cart); root.disassemble(); cart.ejectPassengers(); cart.discard(); h.succeed();
        });
    }
    private static void selfShadow(GameTestHelper h) {
        rails(h, Direction.Axis.X, Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST));
        h.runAfterDelay(5, () -> {
            var cart=spawn(h,"coast");fixture(h,cart);var root=MinecartPhysics.mounted(cart);
            var origin=new ContraptionGeometry.Cell(0,0,0);
            ContraptionPhysicsTests.cells(root,java.util.Set.of(origin,new ContraptionGeometry.Cell(3,0,0)));
            double inertia=MinecartPhysics.refresh(cart);
            var motion=root.applyRotation(new Vec3(.4,0,0),1);
            cart.setDeltaMovement(motion);MinecartPhysics.slowdown(cart);double hidden=cart.getDeltaMovement().horizontalDistance();
            ContraptionPhysicsTests.cells(root,java.util.Set.of(origin,new ContraptionGeometry.Cell(0,0,3)));
            near(h,inertia,MinecartPhysics.refresh(cart),1e-15,"minecart mass is unchanged by rearranging a shielded cube");
            cart.setDeltaMovement(motion);MinecartPhysics.slowdown(cart);double exposed=cart.getDeltaMovement().horizontalDistance();
            h.assertTrue(hidden>exposed&&exposed>0,"native minecart slowdown uses the structure's own shadowed projection");
            cart.setDeltaMovement(motion.scale(-1));MinecartPhysics.slowdown(cart);
            near(h,exposed,cart.getDeltaMovement().horizontalDistance(),1e-15,"native minecart air loss is symmetric in reverse");
            root.disassemble();cart.ejectPassengers();cart.discard();h.succeed();
        });
    }
    private static void coupling(GameTestHelper h) {
        rails(h, Direction.Axis.X, Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST));
        h.runAfterDelay(5, () -> {
            var first = spawn(h, "coast"); fixture(h, first); var second = spawn(h, "coast");
            second.setPos(first.position().add(3, 0, 0)); first.setDeltaMovement(new Vec3(.2, 0, 0)); second.setDeltaMovement(Vec3.ZERO);
            double a = MinecartPhysics.refresh(first), b = MinecartPhysics.refresh(second), before = a * first.getDeltaMovement().x;
            CouplingPhysics.softCollisionStep(h.getLevel(), Couple.create(first, second), 3);
            near(h, before, a * first.getDeltaMovement().x + b * second.getDeltaMovement().x, 1e-14, "real coupling gives equal opposite impulses to unequal cart masses");
            h.assertTrue(second.getDeltaMovement().x > 0 && first.getDeltaMovement().x < .2, "the heavier mounted cart transfers actual momentum to its trailer");
            first.setDeltaMovement(Vec3.ZERO);
            var controller = second.getData(AllAttachmentTypes.MINECART_CONTROLLER);
            controller.setStalledExternally(true); second.setDeltaMovement(new Vec3(.25, 0, 0));
            CouplingPhysics.softCollisionStep(h.getLevel(), Couple.create(first, second), 3);
            near(h, 0, first.getDeltaMovement().x, 0, "a stalled end's stored velocity cannot propel the live cart");
            near(h, .25, second.getDeltaMovement().x, 0, "coupling cannot change the stalled end's saved motion");
            controller.setStalledExternally(false);
            var root = MinecartPhysics.mounted(first); root.disassemble(); first.ejectPassengers(); first.discard(); second.discard(); h.succeed();
        });
    }
    private static void twoSupports(GameTestHelper h) {
        twoSupports(h, false);
    }
    private static void twoSupports(GameTestHelper h, boolean cutting) {
        rails(h, Direction.Axis.X, Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST));
        h.runAfterDelay(5, () -> {
            var first = spawn(h, "coast"); var second = spawn(h, "coast"); second.setPos(first.position().add(4, 0, 0));
            // Fresh entities enter Create's loaded-cart registry on the world tick.
            CapabilityMinecartController.tick(h.getLevel());
            first.setDeltaMovement(new Vec3(.4, 0, 0)); second.setDeltaMovement(new Vec3(.2, 0, 0));
            var station = AllBlocks.CART_ASSEMBLER.getDefaultState().setValue(CartAssemblerBlock.RAIL_SHAPE, RailShape.EAST_WEST)
                .setValue(CartAssemblerBlock.RAIL_TYPE, CartAssembleRailType.REGULAR).setValue(CartAssemblerBlock.POWERED, true);
            h.setBlock(START, station); h.setBlock(START.east(4), station);
            h.setBlock(START.below(), Blocks.REDSTONE_BLOCK); h.setBlock(START.east(4).below(), Blocks.REDSTONE_BLOCK);
            for (int x = 0; x <= 4; x++) h.setBlock(START.east(x).above(), Blocks.SLIME_BLOCK);
            if (cutting) h.setBlock(START.above(2), AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
            CartAssemblerBlockEntity assembler = h.getBlockEntity(START); assembler.tryAssemble(first);
            var root = MinecartPhysics.mounted(first); h.assertTrue(root != null, "two native assemblers create one cart-supported structure");
            // The native assembler snaps one support with float coordinates.
            // At GameTest's random far origins that can shift it off the rail
            // centre, so align this straight fixture before sampling contact.
            first.setPos(Vec3.atCenterOf(h.absolutePos(START)).add(0, -.4375, 0));
            second.setPos(first.position().add(4, 0, 0));
            double payload = ContraptionPhysics.shape(root.getContraption()).linearInertia();
            double a = MinecartPhysics.refresh(first), b = MinecartPhysics.refresh(second);
            near(h, .02 + payload, a + b, 1e-14, "one structure on two supporting carts is counted once");
            near(h, a, b, 1e-14, "both support points receive their half of the structure inertia");
            near(h, .006, a * first.getDeltaMovement().x + b * second.getDeltaMovement().x, 1e-14, "each native support attaches its share with momentum conservation");
            if (cutting) {
                var context = root.getContraption().getActors().getFirst().getRight();
                var behaviour = MovementBehaviour.REGISTRY.get(context.state); first.positionRider(root);
                var target = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour.getActiveAreaOffset(context)), 1));
                h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
                first.tick(); second.tick(); first.positionRider(root); root.tick();
                h.assertTrue(h.getLevel().getBlockState(target).isAir(), "one shared mounted drill cuts while both supporting carts tick");
                double rating = com.simibubi.create.api.stress.BlockStressValues.getImpact(context.state.getBlock());
                if (rating <= 0) rating = 2;
                near(h, rating * 1500 / 1200, MinecartPhysics.state(first).actorWork + MinecartPhysics.state(second).actorWork,
                    1e-9, "two supporting carts pay for one drill stroke exactly once");
                near(h, 0, MinecartPhysics.state(second).actorWork, 0, "the support without the mounted passenger cannot replay its tool work");
            }
            double firstSpeed = first.getDeltaMovement().x, secondSpeed = second.getDeltaMovement().x;
            root.disassemble(); first.ejectPassengers();
            near(h, .01, MinecartPhysics.refresh(first), 0, "first support releases an ejected structure");
            near(h, .01, MinecartPhysics.refresh(second), 0, "second support also releases an ejected structure");
            near(h, firstSpeed, first.getDeltaMovement().x, 0, "first support retains its speed on release");
            near(h, secondSpeed, second.getDeltaMovement().x, 0, "second support retains its speed on release");
            first.discard(); second.discard(); h.succeed();
        });
    }
    private static void reload(GameTestHelper h) {
        rails(h, Direction.Axis.X, Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST));
        h.runAfterDelay(5, () -> {
            var cart = spawn(h, "coast"); cart.setDeltaMovement(new Vec3(.4, 0, 0)); fixture(h, cart);
            double j = MinecartPhysics.refresh(cart), velocity = cart.getDeltaMovement().x;
            var tag = new CompoundTag(); h.assertTrue(cart.save(tag), "native cart and passenger serialization succeeds");
            var loaded = (AbstractMinecart) EntityType.loadEntityRecursive(tag, h.getLevel(), e -> e);
            h.assertTrue(loaded != null && MinecartPhysics.mounted(loaded) != null, "native recursive reload restores the actual mounted entity");
            near(h, j, MinecartPhysics.refresh(loaded), 1e-15, "new entity keeps the saved mounted inertia");
            near(h, velocity, loaded.getDeltaMovement().x, 0, "native reload cannot pay the attachment loss twice");
            var delayedTag = tag.copy(); delayedTag.remove("Passengers");
            var delayed = (AbstractMinecart) EntityType.loadEntityRecursive(delayedTag, h.getLevel(), e -> e);
            h.assertTrue(delayed != null, "cart can load before its passenger");
            near(h, j, MinecartPhysics.refresh(delayed), 1e-15, "saved inertia survives pending passenger load");
            var passenger = (com.simibubi.create.content.contraptions.AbstractContraptionEntity)
                EntityType.loadEntityRecursive(tag.getList("Passengers", 10).getCompound(0), h.getLevel(), e -> e);
            h.assertTrue(passenger != null && !passenger.isRemoved(), "late passenger loads independently without being disassembled");
            h.assertTrue(passenger.startRiding(delayed, true), "late mounted passenger attaches through the native reload path");
            near(h, j, MinecartPhysics.refresh(delayed), 1e-15, "late passenger resolves the saved inertia summary");
            near(h, velocity, delayed.getDeltaMovement().x, 0, "late loading also avoids a second attachment loss");
            passenger.discard(); delayed.ejectPassengers(); MinecartPhysics.mounted(loaded).discard(); loaded.discard(); delayed.discard();
            var root = MinecartPhysics.mounted(cart); root.disassemble(); cart.ejectPassengers(); cart.discard(); h.succeed();
        });
    }
    private static void tool(GameTestHelper h, Direction.Axis axis, int sign, String mode) {
        var rail = Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE,
            axis == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH);
        rails(h, axis, rail);
        h.runAfterDelay(5, () -> {
            var cart = spawn(h, mode.equals("furnace") ? "furnace" : "coast");
            if (cart instanceof MinecartFurnace motor) {
                var tag = motor.saveWithoutId(new CompoundTag()); tag.putShort("Fuel", (short) 3600);
                tag.putDouble("PushX", axis == Direction.Axis.X ? sign : 0); tag.putDouble("PushZ", axis == Direction.Axis.Z ? sign : 0);
                motor.load(tag);
            }
            var station = AllBlocks.CART_ASSEMBLER.getDefaultState().setValue(CartAssemblerBlock.RAIL_SHAPE,
                axis == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH)
                .setValue(CartAssemblerBlock.RAIL_TYPE, CartAssembleRailType.REGULAR);
            h.setBlock(START, station); h.setBlock(START.below(), Blocks.REDSTONE_BLOCK);
            var facing = Direction.fromAxisAndDirection(axis, (mode.equals("reverse") ? -sign : sign) > 0
                ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
            h.setBlock(START.above(), (mode.equals("saw") ? AllBlocks.MECHANICAL_SAW : AllBlocks.MECHANICAL_DRILL)
                .getDefaultState().setValue(BlockStateProperties.FACING, facing));
            CartAssemblerBlockEntity assembler = h.getBlockEntity(START); assembler.tryAssemble(cart);
            var root = MinecartPhysics.mounted(cart); h.assertTrue(root != null, "native cart assembler mounts a working tool");
            ((MountedContraption) root.getContraption()).rotationMode = CartAssemblerBlockEntity.CartMovementMode.ROTATION_LOCKED;
            h.setBlock(START, mode.equals("powered") ? Blocks.POWERED_RAIL.defaultBlockState()
                .setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, axis == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH)
                .setValue(PoweredRailBlock.POWERED, true) : rail); cart.positionRider(root);
            var context = root.getContraption().getActors().getFirst().getRight();
            var behaviour = MovementBehaviour.REGISTRY.get(context.state);
            var point = root.toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour.getActiveAreaOffset(context)), 1);
            var target = BlockPos.containing(point); var block = mode.equals("saw") ? Blocks.OAK_LOG : Blocks.STONE;
            if (mode.equals("replacement")) {
                cart.setDeltaMovement(Vec3.ZERO); cart.tick(); cart.positionRider(root); root.tick();
                h.assertTrue(ContraptionActorWork.of(context).motionManaged(), "an empty stationary contact already belongs to the cart's physical feed");
            }
            h.getLevel().setBlockAndUpdate(target, block.defaultBlockState());
            if (mode.equals("wall")) h.setBlock(START.relative(facing), Blocks.STONE);
            if (mode.equals("falling")) h.getLevel().setBlockAndUpdate(target.above(), Blocks.SAND.defaultBlockState());
            context.disabled = mode.equals("disabled");
            boolean weak = mode.equals("weak") || mode.equals("reload");
            boolean powered = mode.equals("powered") || mode.equals("furnace");
            cart.setDeltaMovement(axis(axis, sign * (weak || powered ? .01 : .4)));
            if (mode.equals("dwell")) {
                root.getContraption().stalled = true; var held = cart.position(); cart.tick();
                near(h, 0, cart.position().distanceTo(held), 0, "a structure paused by another action cannot feed its drill");
                near(h, 0, MinecartPhysics.state(cart).actorWork, 0, "native dwell cannot grant tool work despite stored cart velocity");
                h.assertTrue(h.getLevel().getBlockState(target).is(block), "the drill respects the other action's held structure pose");
                root.getContraption().stalled = false; cart.setDeltaMovement(axis(axis, sign * .4));
                cart.tick(); cart.positionRider(root); root.tick();
                h.assertTrue(h.getLevel().getBlockState(target).isAir(), "releasing the other pause resumes physical feed");
                root.discard(); cart.ejectPassengers(); cart.discard(); h.succeed(); return;
            }
            if (mode.equals("native_pause")) {
                behaviour.visitNewPosition(context, target); context.data.putInt("Progress", 5);
                root.getContraption().stalled = true;
                cart.getData(AllAttachmentTypes.MINECART_CONTROLLER).setStalledExternally(true);
            }
            double initial = component(axis, cart.position()); double paid = 0;
            for (int tick = 0; tick < (weak ? 8 : mode.equals("falling") || mode.equals("native_pause") ? 3 : powered ? 7 : 1); tick++) {
                cart.tick(); cart.positionRider(root); root.tick();
                paid += MinecartPhysics.state(cart).actorWork;
                h.assertFalse(context.stall || root.isStalled() || root.getContraption().stalled
                    || cart.getData(AllAttachmentTypes.MINECART_CONTROLLER).isStalled(), "continuous cutting cannot set the native cart pause flag: " + mode);
            }
            h.assertTrue(sign * (component(axis, cart.position()) - initial) > 0, "the real minecart advances while touching its target");
            h.assertTrue(ContraptionActorWork.of(context).motionManaged(), "the native mounted tool opts into the common continuous work counter");
            if (mode.equals("disabled") || mode.equals("reverse")) {
                h.assertTrue(h.getLevel().getBlockState(target).is(block), "an inactive or retreating tool cannot break the target");
                near(h, 0, paid, 0, "inactive tool cannot charge work");
            } else if (mode.equals("wall")) {
                h.assertTrue(h.getLevel().getBlockState(target).is(block), "a physical cart collision cannot supply the full cutting stroke");
                near(h, 0, cart.getDeltaMovement().horizontalDistance(), 0, "a real rail-level wall stops the cart");
                double progress = context.data.getDouble("DCActorTravel");
                for (int tick = 0; tick < 4; tick++) { cart.tick(); cart.positionRider(root); root.tick(); }
                near(h, progress, context.data.getDouble("DCActorTravel"), 0, "blocked displacement grants no additional cutting work");
            } else if (weak) {
                h.assertTrue(paid > 0 && h.getLevel().getBlockState(target).is(block), "weak cart pays only a partial cutting stroke");
                near(h, 0, cart.getDeltaMovement().horizontalDistance(), 0, "real kinetic energy depletion stops feed without an actor pause");
                double progress = context.data.getDouble("DCActorTravel");
                h.assertTrue(progress > 0 && progress < 1500, "physical stop retains fractional stone progress");
                if (mode.equals("reload")) {
                    var saved = new CompoundTag(); h.assertTrue(cart.save(saved), "save stopped cart and pending tool work through native NBT");
                    root.discard(); cart.ejectPassengers(); cart.discard();
                    cart = (AbstractMinecart) EntityType.loadEntityRecursive(saved, h.getLevel(), e -> e);
                    h.assertTrue(cart != null, "native cart reload succeeds"); h.getLevel().addFreshEntityWithPassengers(cart);
                    root = MinecartPhysics.mounted(cart); h.assertTrue(root != null, "reload restores its mounted tool");
                    context = root.getContraption().getActors().getFirst().getRight();
                    near(h, progress, context.data.getDouble("DCActorTravel"), 0, "reload retains already paid cutting progress");
                }
                for (int tick = 0; tick < 4; tick++) { cart.tick(); cart.positionRider(root); root.tick(); }
                near(h, progress, context.data.getDouble("DCActorTravel"), 0, "zero speed cannot continue cutting through native actor ticks");
                cart.setDeltaMovement(axis(axis, sign * .4)); cart.tick(); cart.positionRider(root); root.tick();
                h.assertTrue(h.getLevel().getBlockState(target).isAir(), "a real push resumes the retained cut");
                h.assertFalse(context.stall || root.isStalled(), "resuming physical work does not introduce the old pause");
            } else {
                h.assertTrue(h.getLevel().getBlockState(target).isAir(), "paid feed completes the real target while the cart keeps moving: " + mode);
                h.assertTrue(paid > 0 && sign * component(axis, cart.getDeltaMovement()) > 0, "the working tool consumes energy without freezing the cart");
                if (mode.equals("falling")) h.assertTrue(h.getLevel().getBlockState(target.above()).isAir(), "falling stack clears without native waiting ticks");
                if (mode.equals("replacement")) {
                    h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
                    cart.setDeltaMovement(axis(axis, sign * .4)); cart.tick(); cart.positionRider(root); root.tick();
                    h.assertTrue(h.getLevel().getBlockState(target).isAir(), "a replacement in the same active voxel needs and receives a fresh paid stroke");
                    h.assertTrue(MinecartPhysics.state(cart).actorWork > 0, "replacement cannot reuse the previous target's work");
                }
            }
            root.discard(); cart.ejectPassengers(); cart.discard(); h.succeed();
        });
    }
}
