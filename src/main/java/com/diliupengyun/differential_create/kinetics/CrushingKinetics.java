package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Geometry identifies a material contact; it never joins the wheels' rigid networks. */
public final class CrushingKinetics {
    private CrushingKinetics() {}
    public record Pair(CrushingWheelControllerBlockEntity controller, CrushingWheelBlockEntity a, CrushingWheelBlockEntity b) {
        public double speed() { return CrushingWork.speed(NetworkDynamics.preciseSpeed(a), NetworkDynamics.preciseSpeed(b)); }
        public boolean active() { return controller.isOccupied() && (controller.hasEntity() || controller.inventory.remainingTime > 0); }
        public double torque() { return Math.min(Math.max(0, a.calculateStressApplied()), Math.max(0, b.calculateStressApplied())); }
        public double reaction() {
            if (!active()) return 0;
            return Math.abs(speed()) > 5e-9 ? Math.copySign(torque(), speed())
                : ((ICrushingDynamics) controller).differentialcreate$reaction();
        }
        public Direction facing() {
            Direction side = Direction.getNearest(b.getBlockPos().getX() - a.getBlockPos().getX(),
                b.getBlockPos().getY() - a.getBlockPos().getY(), b.getBlockPos().getZ() - a.getBlockPos().getZ());
            Direction.Axis axis = a.getBlockState().getValue(BlockStateProperties.AXIS);
            Vec3 normal = new Vec3(axis == Direction.Axis.X ? 1 : 0, axis == Direction.Axis.Y ? 1 : 0, axis == Direction.Axis.Z ? 1 : 0);
            Vec3 feed = normal.cross(Vec3.atLowerCornerOf(side.getNormal())).scale(Math.signum(speed()));
            return Direction.getNearest(feed.x, feed.y, feed.z);
        }
    }
    public static Pair pair(CrushingWheelControllerBlockEntity controller) {
        var level = controller.getLevel();
        if (level == null) return null;
        // Iterate positive directions only, so ordering is independent of the wheel that called us.
        for (Direction side : new Direction[]{Direction.EAST, Direction.UP, Direction.SOUTH}) {
            BlockPos ap = controller.getBlockPos().relative(side.getOpposite()), bp = controller.getBlockPos().relative(side);
            if (!level.isLoaded(ap) || !level.isLoaded(bp)) continue;
            if (level.getBlockEntity(ap) instanceof CrushingWheelBlockEntity a && level.getBlockEntity(bp) instanceof CrushingWheelBlockEntity b) {
                Direction.Axis axis = a.getBlockState().getValue(BlockStateProperties.AXIS);
                if (axis != side.getAxis() && b.getBlockState().getValue(BlockStateProperties.AXIS) == axis) return new Pair(controller, a, b);
            }
        }
        return null;
    }
    public static List<Pair> pairs(List<KineticNetwork> networks) {
        var result = new LinkedHashMap<BlockPos, Pair>();
        for (var network : networks) for (var be : network.members.keySet())
            if (be instanceof CrushingWheelBlockEntity wheel && !be.isRemoved())
                for (var pair : pairs(wheel)) result.put(pair.controller.getBlockPos(), pair);
        return new ArrayList<>(result.values());
    }
    public static List<Pair> pairs(CrushingWheelBlockEntity wheel) {
        var result = new ArrayList<Pair>();
        if (wheel.getLevel() == null) return result;
        for (Direction side : Direction.values()) {
            if (side.getAxis() == wheel.getBlockState().getValue(BlockStateProperties.AXIS)) continue;
            BlockPos pos = wheel.getBlockPos().relative(side);
            if (wheel.getLevel().isLoaded(pos) && wheel.getLevel().getBlockEntity(pos) instanceof CrushingWheelControllerBlockEntity controller) {
                Pair pair = pair(controller);
                if (pair != null && (pair.a == wheel || pair.b == wheel)) result.add(pair);
            }
        }
        return result;
    }
    public static double reaction(CrushingWheelBlockEntity wheel) {
        double torque = 0;
        for (var pair : pairs(wheel)) torque += (pair.a == wheel ? -1 : 1) * pair.reaction();
        return torque;
    }
    public static void update(CrushingWheelControllerBlockEntity controller) {
        if (controller.getLevel() == null || controller.isRemoved()) return;
        Pair pair = pair(controller);
        double speed = pair == null ? 0 : pair.speed();
        float published = (float) (Math.abs(speed) / 50);
        BlockState state = controller.getBlockState();
        boolean valid = pair != null && speed != 0;
        Direction facing = valid ? pair.facing() : state.getValue(CrushingWheelControllerBlock.FACING);
        if (!controller.getLevel().isClientSide && (valid != state.getValue(CrushingWheelControllerBlock.VALID)
            || facing != state.getValue(CrushingWheelControllerBlock.FACING)))
            controller.getLevel().setBlockAndUpdate(controller.getBlockPos(), state.setValue(CrushingWheelControllerBlock.VALID, valid)
                .setValue(CrushingWheelControllerBlock.FACING, facing));
        if (controller.crushingspeed != published) {
            controller.crushingspeed = published;
            if (!controller.getLevel().isClientSide) controller.sendData();
        }
        if (valid && !controller.getLevel().isClientSide) {
            pair.a.award(AllAdvancements.CRUSHING_WHEEL); pair.b.award(AllAdvancements.CRUSHING_WHEEL);
            if (Math.abs(speed) > AllConfigs.server().kinetics.maxRotationSpeed.get() - 1) pair.a.award(AllAdvancements.CRUSHER_MAXED);
        }
    }
    public static void updateControllers(BlockState state, Level level, BlockPos pos, Direction side) {
        if (level == null || side.getAxis() == state.getValue(BlockStateProperties.AXIS)) return;
        BlockPos at = pos.relative(side), other = pos.relative(side, 2);
        if (!level.isLoaded(at) || !level.isLoaded(other)) return;
        BlockState neighbour = level.getBlockState(other), current = level.getBlockState(at);
        boolean exists = AllBlocks.CRUSHING_WHEEL_CONTROLLER.has(current);
        boolean geometry = AllBlocks.CRUSHING_WHEEL.has(neighbour)
            && neighbour.getValue(BlockStateProperties.AXIS) == state.getValue(BlockStateProperties.AXIS);
        if (!geometry) {
            if (exists) level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            return;
        }
        if (!exists) {
            if (!current.canBeReplaced()) return;
            level.setBlockAndUpdate(at, AllBlocks.CRUSHING_WHEEL_CONTROLLER.getDefaultState());
        }
        if (level.getBlockEntity(at) instanceof CrushingWheelControllerBlockEntity controller) update(controller);
    }
}
