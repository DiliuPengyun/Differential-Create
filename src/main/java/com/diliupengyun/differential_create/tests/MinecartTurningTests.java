package com.diliupengyun.differential_create.tests;

import static com.diliupengyun.differential_create.tests.MinecartOperationTests.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.MinecartPhysics;
import com.diliupengyun.differential_create.kinetics.MinecartMotion;
import com.diliupengyun.differential_create.kinetics.IMinecartOrientation;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlockEntity.CartMovementMode;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Isolate orientation-induced feed: targets are outside the translation-only contact. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class MinecartTurningTests {
    private MinecartTurningTests() {}
    private static void add(Collection<TestFunction> cases, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
            cases.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 120, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (var facing : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH})
            for (int sign : new int[]{-1, 1}) {
                for (String tool : new String[]{"drill", "saw", "harvester", "plough", "roller"})
                    for (String operation : new String[]{"normal", "disabled", "backward"})
                        add(cases, "minecart_turn_arc_" + tool + "_" + operation + "_" + facing.getName() + "_" + sign,
                            h -> arc(h, facing, sign, tool, operation));
                for (var mode : new CartMovementMode[]{CartMovementMode.ROTATE_PAUSED, CartMovementMode.ROTATION_LOCKED})
                    add(cases, "minecart_turn_mode_" + mode.name().toLowerCase() + "_" + facing.getName() + "_" + sign,
                        h -> mode(h, facing, sign, mode));
                for (String operation : new String[]{"resume", "reload", "reverse", "replace", "stroke", "wire"})
                    add(cases, "minecart_turn_partial_" + operation + "_" + facing.getName() + "_" + sign,
                        h -> partial(h, facing, sign, operation));
                for (String kind : new String[]{"drill", "saw"}) for (var mode : CartMovementMode.values())
                    add(cases, "minecart_turn_coupled_yaw_" + kind + "_" + mode.name().toLowerCase() + "_" + facing.getName() + "_" + sign,
                        h -> coupled(h, facing, sign, kind, false, mode, "both"));
                for (String source : new String[]{"stationary", "frozen"})
                    add(cases, "minecart_turn_coupled_source_" + source + "_" + facing.getName() + "_" + sign,
                        h -> coupled(h, facing, sign, "drill", false, CartMovementMode.ROTATE, source));
                add(cases, "minecart_turn_coupled_pitch_" + facing.getName() + "_" + sign,
                    h -> coupled(h, facing, sign, "drill", true, CartMovementMode.ROTATE, "both"));
                add(cases, "minecart_turn_coupled_joint_" + facing.getName() + "_" + sign,
                    h -> coupled(h, facing, sign, "drill", true, CartMovementMode.ROTATE, "joint"));
                add(cases, "minecart_turn_mode_aligned_" + facing.getName() + "_" + sign, h -> aligned(h, facing, sign));
                add(cases, "minecart_turn_stabilized_" + facing.getName() + "_" + sign, h -> stabilized(h, facing, sign));
            }
        return cases;
    }
    private static Vec3 point(Fixture f) {
        var behaviour = MovementBehaviour.REGISTRY.get(f.context.state);
        return f.context.contraption.entity.toGlobalVector(Vec3.atCenterOf(f.context.localPos).add(behaviour.getActiveAreaOffset(f.context)), 1);
    }
    private record Contact(Fixture f, BlockPos target, float yaw) {}
    private static BlockState tool(String kind, Direction facing) {
        return switch (kind) {
            case "drill" -> AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, facing);
            case "saw" -> AllBlocks.MECHANICAL_SAW.getDefaultState().setValue(BlockStateProperties.FACING, facing);
            case "harvester" -> AllBlocks.MECHANICAL_HARVESTER.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            case "plough" -> AllBlocks.MECHANICAL_PLOUGH.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            default -> AllBlocks.MECHANICAL_ROLLER.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        };
    }
    private static Contact contact(GameTestHelper h, Direction facing, int sign, String kind, boolean backward) {
        return contact(h, facing, sign, kind, backward, 90);
    }
    private static Contact contact(GameTestHelper h, Direction facing, int sign, String kind, boolean backward, int angle) {
        var f = new Fixture(h, facing, tool(kind, backward ? facing.getOpposite() : facing),
            "rideable", true, false, null, sign * 6);
        ((MountedContraption) f.root.getContraption()).rotationMode = CartMovementMode.ROTATE;
        f.root.setInitialOrientation(facing);
        float yaw = f.root.getInitialYaw() + sign * angle;
        f.root.startAtYaw(yaw); f.push(.4);
        var start = point(f);
        // Observe the native empty-space turn, then replay it with a target.
        f.root.tick(); var end = point(f);
        var delta = end.subtract(start);
        boolean x = Math.abs(delta.x) > Math.abs(delta.z);
        double coordinate = x ? start.x : start.z, motion = x ? delta.x : delta.z;
        h.assertTrue(Math.abs(motion) > .4, "long-arm native turn provides enough motion for a cutting stroke");
        double shift = (motion > 0 ? .85 : .15) - (coordinate - Math.floor(coordinate));
        var offset = x ? new Vec3(shift, 0, 0) : new Vec3(0, 0, shift);
        f.cart.setPos(f.cart.position().add(offset)); f.cart.positionRider(f.root);
        start = start.add(offset); var target = BlockPos.containing(end.add(offset));
        h.assertTrue(!target.equals(BlockPos.containing(start)), "fixture target is reached by rotation, rather than translation");
        f.root.startAtYaw(yaw); f.context.position = start; f.context.firstMovement = false; f.push(.4);
        f.context.data.remove("DCActorCell");
        return new Contact(f, target, yaw);
    }
    private static double energy(Fixture f) { return MinecartMotion.energy(MinecartPhysics.refresh(f.cart), f.cart.getDeltaMovement().length()); }
    private static double cost(Fixture f, String kind) {
        double rating = BlockStressValues.getImpact(f.context.state.getBlock());
        if (rating <= 0) rating = kind.equals("roller") ? 8 : 2;
        return rating * (kind.equals("drill") ? 1500 : kind.equals("saw") ? 2000 : 1000) / 1200;
    }
    private static void seed(Contact c, String kind) {
        var world = c.f.h.getLevel();
        switch (kind) {
            case "drill" -> world.setBlockAndUpdate(c.target, Blocks.STONE.defaultBlockState());
            case "saw" -> world.setBlockAndUpdate(c.target, Blocks.OAK_LOG.defaultBlockState());
            case "harvester" -> {
                world.setBlockAndUpdate(c.target.below(), Blocks.FARMLAND.defaultBlockState());
                world.setBlockAndUpdate(c.target, Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7));
            }
            case "plough" -> { world.setBlockAndUpdate(c.target, Blocks.AIR.defaultBlockState()); world.setBlockAndUpdate(c.target.below(), Blocks.DIRT.defaultBlockState()); }
            case "roller" -> {
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                    world.setBlockAndUpdate(c.target.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                    world.setBlockAndUpdate(c.target.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState());
                }
                c.f.context.blockEntityData.putInt("ScrollValue", 1);
                c.f.context.blockEntityData.put("Filter", new ItemStack(Items.STONE).save(world.registryAccess()));
                world.setBlockAndUpdate(c.target, Blocks.AIR.defaultBlockState()); world.setBlockAndUpdate(c.target.below(), Blocks.BEDROCK.defaultBlockState());
            }
        }
    }
    private static boolean complete(Contact c, String kind) {
        var world = c.f.h.getLevel(); var state = world.getBlockState(c.target);
        return switch (kind) {
            case "drill", "saw" -> state.isAir();
            case "harvester" -> state.is(Blocks.WHEAT) && state.getValue(BlockStateProperties.AGE_7) == 0;
            case "plough" -> world.getBlockState(c.target.below()).is(Blocks.FARMLAND);
            default -> state.is(Blocks.STONE);
        };
    }
    private static void arc(GameTestHelper h, Direction facing, int sign, String kind, String operation) {
        h.runAfterDelay(5, () -> {
            var c = contact(h, facing, sign, kind, operation.equals("backward")); var f = c.f;
            seed(c, kind);
            if (operation.equals("disabled")) f.root.getContraption().setActorsActive(ItemStack.EMPTY, false);
            double before = energy(f), work = MinecartPhysics.state(f.cart).actorWork;
            f.root.tick();
            double paid = MinecartPhysics.state(f.cart).actorWork - work;
            h.assertTrue(complete(c, kind) == operation.equals("normal"), "turning " + kind + " obeys " + operation + " at a rotation-only contact");
            near(h, operation.equals("normal") ? cost(f, kind) : 0, paid, 1e-8, "circular contact pays exactly one job, or none while inactive");
            near(h, paid, before - energy(f), 1e-8, "rotation work equals the cart's actual kinetic energy loss");
            h.assertFalse(f.root.isStalled() || f.context.stall, "turning cut does not restore native parking to break a block");
            if (operation.equals("normal")) {
                int count = items(f, switch (kind) { case "drill" -> Items.COBBLESTONE; case "saw" -> Items.OAK_LOG; case "harvester" -> Items.WHEAT; default -> Items.STONE; });
                if (!kind.equals("plough")) near(h, kind.equals("roller") ? 15 : 1, count, 0, "one circular job has exactly one drop or material debit");
                f.push(0); for (int i = 0; i < 4; i++) f.root.tick();
                near(h, work + paid, MinecartPhysics.state(f.cart).actorWork, 0, "native follow-up actor ticks cannot replay circular work");
            }
            f.cleanup(); h.succeed();
        });
    }
    private static void mode(GameTestHelper h, Direction facing, int sign, CartMovementMode mode) {
        h.runAfterDelay(5, () -> {
            var c = contact(h, facing, sign, "drill", false); var f = c.f;
            seed(c, "drill"); ((MountedContraption) f.root.getContraption()).rotationMode = mode;
            double before = energy(f), work = MinecartPhysics.state(f.cart).actorWork;
            f.root.tick();
            h.assertFalse(complete(c, "drill"), "locked orientation or unfinished paused alignment grants no rotation cutting");
            near(h, work, MinecartPhysics.state(f.cart).actorWork, 0, "inactive rotation mode pays no work");
            near(h, before, energy(f), 1e-8, "inactive rotation mode takes no cutting energy");
            near(h, mode == CartMovementMode.ROTATION_LOCKED ? 0 : 1, Math.abs(f.root.yaw - c.yaw) > 0 ? 1 : 0, 0,
                "locked body holds its heading; paused mode still approaches the native heading");
            f.cleanup(); h.succeed();
        });
    }
    private static void partial(GameTestHelper h, Direction facing, int sign, String operation) {
        h.runAfterDelay(5, () -> {
            var c = contact(h, facing, sign, "drill", false); var f = c.f; seed(c, "drill");
            f.push(.01); f.root.tick();
            double progress = f.context.data.getDouble("DCActorTravel"), work = MinecartPhysics.state(f.cart).actorWork;
            h.assertTrue(progress > 0 && progress < 1500 && !complete(c, "drill"), "rotation contact can exhaust kinetic energy partway through a real stone cut");
            near(h, 0, f.cart.getDeltaMovement().length(), 0, "insufficient energy stops the rotating tool's drive");
            float stopped = f.root.yaw;
            for (int i = 0; i < 5; i++) f.root.tick();
            near(h, progress, f.context.data.getDouble("DCActorTravel"), 0, "stopped rotation retains progress without advancing native timers");
            near(h, stopped, f.root.yaw, 0, "zero kinetic energy cannot continue orientation feed");
            near(h, work, MinecartPhysics.state(f.cart).actorWork, 0, "zero feed pays no additional work");
            if (operation.equals("reload")) {
                f.reload(); near(h, progress, f.context.data.getDouble("DCActorTravel"), 0, "native entity reload retains the partial arc cut");
                f.root.tick(); near(h, progress, f.context.data.getDouble("DCActorTravel"), 0, "reloaded stationary tool grants no free work");
            } else if (operation.equals("wire")) {
                wire(h, f);
            } else if (operation.equals("reverse")) {
                f.push(-.4); f.root.tick();
                near(h, 0, f.context.data.getDouble("DCActorTravel"), 0, "reversing the circular sweep abandons its previous target");
                h.assertFalse(complete(c, "drill"), "retreating tool cannot finish the target for free");
            } else if (operation.equals("replace")) {
                h.getLevel().setBlockAndUpdate(c.target, Blocks.AIR.defaultBlockState());
                h.getLevel().setBlockAndUpdate(c.target, Blocks.STONE.defaultBlockState());
            }
            double paidBefore = MinecartPhysics.state(f.cart).actorWork;
            var radiusVector = point(f).subtract(f.root.getAnchorVec().add(.5, .5, .5));
            double radius = Math.sqrt(radiusVector.horizontalDistanceSqr() + 1.0 / 6);
            double expectedAngle = .25 * (1 - progress / 1500) / radius * 180 / Math.PI;
            double resumeSpeed = operation.equals("stroke") ? Math.sqrt(2 * (cost(f, "drill") - work) / MinecartPhysics.refresh(f.cart)) / 512 : .4;
            f.push(resumeSpeed); for (int i = 0; i < 4 && !complete(c, "drill"); i++) f.root.tick();
            h.assertTrue(complete(c, "drill"), "physical rotation resumes a paid cut after " + operation);
            double expected = cost(f, "drill") - (operation.equals("resume") || operation.equals("reload") || operation.equals("stroke") || operation.equals("wire") ? work : 0);
            near(h, expected, MinecartPhysics.state(f.cart).actorWork - paidBefore, 1e-7, "resume preserves paid work; a replaced or abandoned target pays a new full job");
            near(h, 1, items(f, Items.COBBLESTONE), 0, "partial rotation produces one final drop");
            if (operation.equals("stroke")) {
                near(h, expectedAngle, Math.abs(net.minecraft.util.Mth.wrapDegrees(f.root.yaw - stopped)), .001,
                    "changing speed preserves the remaining physical quarter-block cutting stroke");
                near(h, 0, f.cart.getDeltaMovement().length(), 1e-7, "exact remaining work budget finishes at zero kinetic energy");
            }
            f.cleanup(); h.succeed();
        });
    }
    private static void pose(Fixture f, float yaw, float pitch) { f.root.yaw = yaw; f.root.pitch = pitch; }
    private static void wire(GameTestHelper h, Fixture f) {
        var snapshot = ((IMinecartOrientation) f.root).differentialcreate$orientation();
        h.assertTrue(snapshot != null && snapshot.yaw() == f.root.yaw && snapshot.pitch() == f.root.pitch,
            "server publishes the accepted partial pose rather than the full unpaid native turn");
        var packet = new ClientboundSetEntityDataPacket(f.root.getId(), f.root.getEntityData().getNonDefaultValues());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess(),
            net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
        try {
            ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buffer, packet);
            var decoded = ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buffer);
            var replica = new OrientedContraptionEntity(f.root.getType(), h.getLevel());
            replica.getEntityData().assignValues(decoded.packedItems());
            var received = ((IMinecartOrientation) replica).differentialcreate$orientation();
            h.assertTrue(snapshot.equals(received), "late tracking through the actual vanilla packet codec retains yaw, pitch and rotation mode state");
            IMinecartOrientation.apply(replica, received);
            near(h, f.root.getViewYRot(1), replica.getViewYRot(1), 0, "rendered replica heading follows the accepted server pose");
            near(h, f.root.getViewXRot(1), replica.getViewXRot(1), 0, "rendered replica tilt follows the accepted server pose");
        } finally { buffer.release(); }
    }
    /** An independent dense native-transform reference selects a cell that lies
     * on the arc, outside both endpoints and even the straight endpoint chord. */
    private static BlockPos intermediate(GameTestHelper h, Fixture f, float yaw, float pitch, float endYaw, float endPitch) {
        var original = f.cart.position(); var other = f.support.position();
        float yawDelta = net.minecraft.util.Mth.wrapDegrees(endYaw - yaw), pitchDelta = endPitch - pitch;
        for (int a = 0; a < 5; a++) for (int b = 0; b < 5; b++) {
            var offset = vector(f.facing, a * .2).add(pitchDelta == 0 ? vector(f.facing.getClockWise(), b * .2) : new Vec3(0, b * .2, 0));
            f.cart.setPos(original.add(offset)); f.support.setPos(other.add(offset)); f.cart.positionRider(f.root);
            pose(f, yaw, pitch); var start = point(f);
            pose(f, yaw + yawDelta, endPitch); var end = point(f);
            Set<BlockPos> chord = new HashSet<>();
            for (int i = 0; i <= 500; i++) chord.add(BlockPos.containing(start.add(end.subtract(start).scale(i / 500.0))));
            BlockPos previous = null;
            Vec3 entry = null;
            for (int i = 0; i <= 500; i++) {
                pose(f, yaw + yawDelta * i / 500, pitch + pitchDelta * i / 500);
                var p = point(f); var cell = BlockPos.containing(p);
                if (!cell.equals(previous)) { previous = cell; entry = p; }
                if (!chord.contains(cell) && p.distanceTo(entry) > .35) {
                    var sweep = new HashSet<BlockPos>();
                    for (int j = 0; j <= 500; j++) {
                        pose(f, yaw + yawDelta * j / 500, pitch + pitchDelta * j / 500);
                        sweep.add(BlockPos.containing(point(f)));
                    }
                    for (var contact : sweep) h.getLevel().setBlockAndUpdate(contact, Blocks.AIR.defaultBlockState());
                    pose(f, yaw, pitch); f.context.position = start; f.context.firstMovement = false; f.context.data.remove("DCActorCell");
                    h.assertTrue(!cell.equals(BlockPos.containing(start)) && !cell.equals(BlockPos.containing(end)), "intermediate target is absent from native tick endpoints");
                    return cell;
                }
            }
        }
        throw new GameTestAssertException("could not find a full-stroke intermediate native arc cell outside its endpoint chord");
    }
    private static double coupledEnergy(Fixture f) {
        return energy(f) + MinecartMotion.energy(MinecartPhysics.refresh(f.support), f.support.getDeltaMovement().length());
    }
    private static void coupled(GameTestHelper h, Direction facing, int sign, String kind, boolean pitch, CartMovementMode mode, String source) {
        h.runAfterDelay(5, () -> {
            var direction = pitch ? (sign > 0 ? Direction.UP : Direction.DOWN) : facing;
            var f = new Fixture(h, facing, tool(kind, direction), "rideable", true, false, null, pitch ? 6 : -sign * 6, true, pitch);
            ((MountedContraption) f.root.getContraption()).rotationMode = mode;
            f.root.getContraption().setActorsActive(ItemStack.EMPTY, false);
            MinecartPhysics.refresh(f.cart); MinecartPhysics.refresh(f.support);
            f.push(.4); f.support.setDeltaMovement(vector(facing, .4)); f.root.tick();
            float yaw = f.root.yaw, oldPitch = f.root.pitch;
            var movement = pitch ? new Vec3(0, sign * 4, 0) : vector(facing.getClockWise(), sign * 4);
            if (source.equals("joint")) movement = movement.add(vector(facing.getClockWise(), sign * 4));
            f.support.setPos(f.support.position().add(movement));
            f.root.tick(); float endYaw = f.root.yaw, endPitch = f.root.pitch;
            h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(endYaw - yaw)) + Math.abs(endPitch - oldPitch) > 40,
                "real two-cart geometry produces a long circular yaw or pitch sweep");
            if (source.equals("joint")) h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(endYaw - yaw)) > 30 && Math.abs(endPitch - oldPitch) > 30,
                "joint fixture changes both native yaw and pitch during one tick");
            var target = intermediate(h, f, source.equals("joint") ? endYaw : yaw, oldPitch, endYaw, endPitch);
            if (source.equals("joint")) { pose(f, yaw, oldPitch); f.context.position = point(f); }
            f.root.getContraption().setActorsActive(ItemStack.EMPTY, true);
            var c = new Contact(f, target, yaw); seed(c, kind);
            if (source.equals("stationary")) f.support.setDeltaMovement(Vec3.ZERO);
            if (source.equals("frozen")) {
                f.support.getData(com.simibubi.create.AllAttachmentTypes.MINECART_CONTROLLER).setStalledExternally(true);
                // Saved/externally supplied velocity on a frozen support is not
                // available to its tool feed and must be left untouched.
                f.support.setDeltaMovement(vector(facing, .4));
            }
            var supportVelocity = f.support.getDeltaMovement();
            double before = coupledEnergy(f);
            double work = MinecartPhysics.state(f.cart).actorWork + MinecartPhysics.state(f.support).actorWork;
            f.root.tick();
            h.assertTrue(complete(c, kind), "coupled " + (pitch ? "pitch" : "yaw") + " sweep cuts an intermediate target missed by both endpoints and their chord");
            wire(h, f);
            double paid = MinecartPhysics.state(f.cart).actorWork + MinecartPhysics.state(f.support).actorWork - work;
            near(h, cost(f, kind), paid, 1e-7, "two supporting carts pay one circular tool job exactly once");
            near(h, paid, before - coupledEnergy(f), 1e-7, "combined support kinetic energy pays the complete circular job");
            boolean shared = source.equals("both") || source.equals("joint");
            h.assertTrue(MinecartPhysics.state(f.cart).actorWork > 0 && (MinecartPhysics.state(f.support).actorWork > 0) == shared,
                "only moving unfrozen supports contribute kinetic energy to the tool");
            if (!shared) near(h, 0, supportVelocity.distanceTo(f.support.getDeltaMovement()), 0,
                "stationary and frozen support velocities are unaffected by the cutting budget");
            near(h, 1, items(f, kind.equals("drill") ? Items.COBBLESTONE : Items.OAK_LOG), 0, "an intermediate circular target drops exactly once");
            f.push(0); f.support.setDeltaMovement(Vec3.ZERO);
            for (int i = 0; i < 3; i++) f.root.tick();
            near(h, work + paid, MinecartPhysics.state(f.cart).actorWork + MinecartPhysics.state(f.support).actorWork, 0,
                "native coupled actor ticks cannot repeat paid work after stopping");
            h.assertFalse(f.context.stall || f.root.isStalled(), "coupled arc work adds no native global drill pause");
            f.cleanup(); h.succeed();
        });
    }
    private static void aligned(GameTestHelper h, Direction facing, int sign) {
        h.runAfterDelay(5, () -> {
            var c = contact(h, facing, sign, "drill", false, 5); var f = c.f; seed(c, "drill");
            ((MountedContraption) f.root.getContraption()).rotationMode = CartMovementMode.ROTATE_PAUSED;
            double work = MinecartPhysics.state(f.cart).actorWork;
            f.root.tick();
            h.assertTrue(complete(c, "drill"), "pause-while-rotating mode resumes work on the final aligned angular step");
            near(h, cost(f, "drill"), MinecartPhysics.state(f.cart).actorWork - work, 1e-8, "final alignment pays one complete circular cut");
            h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(f.root.targetYaw - f.root.yaw)) < 1,
                "accepted paid pose remains inside the native alignment threshold");
            h.assertFalse(((IMinecartOrientation) f.root).differentialcreate$orientation().rotating(), "alignment state is synchronized to clients");
            f.cleanup(); h.succeed();
        });
    }
    private static void stabilized(GameTestHelper h, Direction facing, int sign) {
        h.runAfterDelay(5, () -> {
            var side = facing.getCounterClockWise(); var bearing = START.above().relative(side);
            h.setBlock(bearing.above(), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
            for (int i = 1; i < 6; i++) h.setBlock(START.above(3).relative(side, i), Blocks.SLIME_BLOCK);
            h.setBlock(START.above(3).relative(side, 6), tool("drill", Direction.UP));
            var f = new Fixture(h, facing, tool("drill", facing), "rideable", true, false,
                AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
            f.root.tick();
            var child = f.root.getPassengers().stream().filter(e -> e instanceof OrientedContraptionEntity).map(e -> (OrientedContraptionEntity) e).findFirst().orElseThrow();
            child.tick();
            f.context = child.getContraption().getActors().getFirst().getRight();
            ((MountedContraption) f.root.getContraption()).rotationMode = CartMovementMode.ROTATE;
            f.root.setInitialOrientation(facing); f.root.startAtYaw(f.root.getInitialYaw() + sign * 90); f.root.positionRider(child);
            var heading = child.applyRotation(new Vec3(1, 0, 0), 1);
            var target = BlockPos.containing(point(f)); f.context.position = point(f); f.context.data.remove("DCActorCell");
            h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
            double work = MinecartPhysics.state(f.cart).actorWork;
            f.push(.4); f.root.tick(); child.tick();
            h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.STONE) && f.context.data.getDouble("DCActorTravel") > 0,
                "stabilized child receives its mount's short orbit, rather than rotating its entire long arm with the parent");
            for (int i = 0; i < 4 && !h.getLevel().getBlockState(target).isAir(); i++) { f.root.tick(); child.tick(); }
            h.assertTrue(h.getLevel().getBlockState(target).isAir(), "actual bearing-stabilized drill completes a paid cut while the cart turns");
            near(h, cost(f, "drill"), MinecartPhysics.state(f.cart).actorWork - work, 1e-7, "stabilized child and parent actor ticks cannot duplicate the cut");
            near(h, 0, heading.distanceTo(child.applyRotation(new Vec3(1, 0, 0), 1)), 0, "vertical stabilized bearing holds its child's world heading");
            int drops = 0; var inventory = child.getContraption().getStorage().getAllItems();
            for (int i = 0; i < inventory.getSlots(); i++) if (inventory.getStackInSlot(i).is(Items.COBBLESTONE)) drops += inventory.getStackInSlot(i).getCount();
            for (var item : h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(2)))
                if (item.getItem().is(Items.COBBLESTONE)) drops += item.getItem().getCount();
            near(h, 1, drops, 0, "stabilized circular cut produces one inventory or world drop");
            child.discard(); f.cleanup(); h.succeed();
        });
    }
}
