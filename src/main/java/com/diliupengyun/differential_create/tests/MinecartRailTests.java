package com.diliupengyun.differential_create.tests;

import static com.diliupengyun.differential_create.tests.MinecartOperationTests.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.MinecartPhysics;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlockEntity.CartMovementMode;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Track geometry is tested separately from the straight-rail drive recurrence. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class MinecartRailTests {
    private MinecartRailTests() {}
    private static void add(Collection<TestFunction> cases, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
            cases.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 100, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (var shape : RailShape.values()) for (int sign : new int[]{-1, 1}) {
            add(cases, "minecart_rail_" + shape.getName() + "_native_" + sign, h -> geometry(h, shape, sign));
            if (shape.isAscending()) add(cases, "minecart_rail_" + shape.getName() + "_drill_" + sign, h -> slope(h, shape, sign));
            else if (shape != RailShape.EAST_WEST && shape != RailShape.NORTH_SOUTH)
                for (var mode : CartMovementMode.values()) add(cases, "minecart_rail_" + shape.getName() + "_" + mode.name().toLowerCase() + "_" + sign,
                    h -> curve(h, shape, sign, mode));
        }
        for (var facing : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) for (boolean ground : new boolean[]{true, false})
            add(cases, "minecart_rail_" + facing.getName() + "_bare_" + (ground ? "ground" : "air"), h -> offRail(h, facing, ground));
        return cases;
    }
    private record Segment(Vec3 middle, Vec3 direction, double rise) {}
    /** Native endpoints transcribed as an independent geometry expectation. */
    private static Segment segment(RailShape shape) {
        return switch (shape) {
            case NORTH_SOUTH -> new Segment(new Vec3(.5, .0625, .5), new Vec3(0, 0, 1), 0);
            case EAST_WEST -> new Segment(new Vec3(.5, .0625, .5), new Vec3(1, 0, 0), 0);
            case ASCENDING_EAST -> new Segment(new Vec3(.5, .5625, .5), new Vec3(1, 0, 0), 1);
            case ASCENDING_WEST -> new Segment(new Vec3(.5, .5625, .5), new Vec3(1, 0, 0), -1);
            case ASCENDING_NORTH -> new Segment(new Vec3(.5, .5625, .5), new Vec3(0, 0, 1), -1);
            case ASCENDING_SOUTH -> new Segment(new Vec3(.5, .5625, .5), new Vec3(0, 0, 1), 1);
            case SOUTH_EAST -> new Segment(new Vec3(.75, .0625, .75), new Vec3(1, 0, -1).normalize(), 0);
            case SOUTH_WEST -> new Segment(new Vec3(.25, .0625, .75), new Vec3(-1, 0, -1).normalize(), 0);
            case NORTH_WEST -> new Segment(new Vec3(.25, .0625, .25), new Vec3(-1, 0, 1).normalize(), 0);
            case NORTH_EAST -> new Segment(new Vec3(.75, .0625, .25), new Vec3(1, 0, 1).normalize(), 0);
        };
    }
    private static void geometry(GameTestHelper h, RailShape shape, int sign) {
        h.setBlock(START.below(), Blocks.STONE);
        var state = Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, shape);
        h.runAfterDelay(5, () -> {
            h.getLevel().setBlock(h.absolutePos(START), state, 2);
            var cart = EntityType.MINECART.create(h.getLevel()); h.getLevel().addFreshEntity(cart);
            var s = segment(shape); var start = Vec3.atLowerCornerOf(h.absolutePos(START)).add(s.middle);
            double expected = sign * .1;
            for (int tick = 0; tick < 32; tick++) {
                cart.setPos(start); cart.setDeltaMovement(s.direction.scale(expected));
                double before = expected - s.rise * cart.getSlopeAdjustment();
                var desired = s.direction.scale(before);
                var movement = new Vec3(Math.clamp(desired.x, -.4F, .4F), 0, Math.clamp(desired.z, -.4F, .4F));
                double height = s.rise * movement.dot(s.direction);
                var position = start.add(movement).add(0, height, 0);
                expected = Math.copySign(Math.abs(before) * .96 - height * .05, before);
                cart.tick();
                near(h, position.x, cart.getX(), 1e-8, "native geometry preserves X for " + shape);
                near(h, position.y, cart.getY(), 1e-8, "native geometry preserves height for " + shape);
                near(h, position.z, cart.getZ(), 1e-8, "native geometry preserves Z for " + shape);
                near(h, expected, cart.getDeltaMovement().dot(s.direction), 1e-8, "native slope and curve recurrence for " + shape);
            }
            cart.discard(); h.succeed();
        });
    }
    private static void slope(GameTestHelper h, RailShape shape, int sign) {
        h.runAfterDelay(5, () -> {
            var s = segment(shape); var facing = Direction.getNearest(s.direction.x * sign, 0, s.direction.z * sign);
            var f = new Fixture(h, facing, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, facing));
            var uphill = Direction.getNearest(s.direction.x * s.rise, 0, s.direction.z * s.rise);
            h.setBlock(START.relative(uphill), Blocks.STONE);
            h.setBlock(START.relative(uphill).above(), rail(uphill));
            h.getLevel().setBlock(h.absolutePos(START), Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, shape), 2);
            f.cart.setPos(Vec3.atLowerCornerOf(h.absolutePos(START)).add(s.middle)); f.cart.positionRider(f.root);
            var target = f.contact(); h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(START)).getValue(BlockStateProperties.RAIL_SHAPE) == shape, "slope fixture retains the requested connected rail shape");
            f.push(.4); f.tick();
            h.assertTrue(h.getLevel().getBlockState(target).isAir(), "drill cuts the block at its actual slope height, rather than the native temporary rail-move height");
            h.assertTrue(MinecartPhysics.state(f.cart).actorWork > 0 && !f.root.isStalled(), "ascending and descending work consumes energy without an actor pause");
            f.cleanup(); h.succeed();
        });
    }
    private static void curve(GameTestHelper h, RailShape shape, int sign, CartMovementMode mode) {
        h.runAfterDelay(5, () -> {
            var s = segment(shape); var facing = Direction.getNearest(s.direction.x * sign, 0, 0);
            var f = new Fixture(h, facing, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, facing));
            ((MountedContraption) f.root.getContraption()).rotationMode = mode;
            h.getLevel().setBlock(h.absolutePos(START), Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, shape), 2);
            f.cart.setPos(Vec3.atLowerCornerOf(h.absolutePos(START)).add(s.middle)); f.cart.positionRider(f.root);
            var target = f.contact(); h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
            f.cart.setDeltaMovement(s.direction.scale(sign * .4)); f.tick();
            if (mode == CartMovementMode.ROTATE_PAUSED) {
                h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.STONE), "native pause-while-rotating setting suppresses work during alignment");
                near(h, 0, MinecartPhysics.state(f.cart).actorWork, 0, "paused rotation grants no cutting work");
            } else h.assertTrue(h.getLevel().getBlockState(target).isAir() && MinecartPhysics.state(f.cart).actorWork > 0,
                "curve's actual translational feed completes a paid drill stroke");
            h.assertFalse(f.context.stall || f.root.isStalled(), "curve modes cannot restore the old drill pause");
            f.cleanup(); h.succeed();
        });
    }
    private static void offRail(GameTestHelper h, Direction facing, boolean ground) {
        h.runAfterDelay(5, () -> {
            h.setBlock(START, Blocks.AIR); h.setBlock(START.below(), ground ? Blocks.STONE : Blocks.AIR);
            var cart = EntityType.MINECART.create(h.getLevel());
            var start = Vec3.atLowerCornerOf(h.absolutePos(START)).add(.5, ground ? 0 : 3, .5);
            cart.setPos(start); h.getLevel().addFreshEntity(cart); cart.setOnGround(ground);
            cart.setDeltaMovement(vector(facing, .2).add(0, ground ? 0 : .2, 0)); cart.tick();
            var movement = vector(facing, ground ? .1 : .2).add(0, ground ? 0 : .16, 0);
            var velocity = ground ? movement : movement.scale(cart.getDragAir());
            near(h, 0, cart.position().distanceTo(start.add(movement)), 1e-8, "bare ground/air movement remains native");
            near(h, 0, cart.getDeltaMovement().distanceTo(velocity), 1e-8, "bare ground/air drag remains native");
            cart.discard(); h.succeed();
        });
    }
}
