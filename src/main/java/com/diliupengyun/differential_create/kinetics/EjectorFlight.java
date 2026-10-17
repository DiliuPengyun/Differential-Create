package com.diliupengyun.differential_create.kinetics;

import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.logistics.depot.EjectorBlockEntity;
import com.simibubi.create.content.logistics.depot.EntityLauncher;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.createmod.catnip.data.IntAttached;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

/** One native launch batch, including its own collision cache. No target mutation after launch. */
public final class EjectorFlight {
    private final EntityLauncher launcher;
    private final Direction facing;
    public Pair<Vec3, BlockPos> earlyTarget;
    public float earlyTargetTime;

    public EjectorFlight(int horizontal, int vertical, Direction facing) {
        launcher = new EntityLauncher(horizontal, vertical);
        this.facing = facing;
    }
    public int horizontal() { return launcher.getHorizontalDistance(); }
    public int vertical() { return launcher.getVerticalDistance(); }
    public Direction facing() { return facing; }
    public float endTime() {
        float total = Math.max(3, (float) launcher.getTotalFlyingTicks());
        return earlyTarget == null ? total : Math.min(earlyTargetTime, total);
    }
    public Vec3 location(EjectorBlockEntity be, float time) {
        return launcher.getGlobalPos(time, facing.getOpposite(), be.getBlockPos());
    }
    public Vec3 motion(EjectorBlockEntity be, float time) {
        return launcher.getGlobalVelocity(time, facing.getOpposite(), be.getBlockPos()).scale(.5);
    }
    public DirectBeltInputBehaviour targetInventory(EjectorBlockEntity be) {
        BlockPos target = earlyTarget != null ? earlyTarget.getSecond()
            : be.getBlockPos().above(vertical()).relative(facing, Math.max(1, horizontal()));
        return BlockEntityBehaviour.get(be.getLevel(), target, DirectBeltInputBehaviour.TYPE);
    }
    /** Preserve Create's periodic tracked-item scan and funnel collision rule. */
    public boolean scan(EjectorBlockEntity be, int time) {
        if (time <= 2) return false;
        Vec3 from = location(be, time), to = location(be, time + 1);
        var hit = be.getLevel().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, CollisionContext.empty()));
        boolean miss = hit.getType() == HitResult.Type.MISS;
        if (!miss) {
            var state = be.getLevel().getBlockState(hit.getBlockPos());
            if (FunnelBlock.isFunnel(state) && state.hasProperty(FunnelBlock.EXTRACTING) && state.getValue(FunnelBlock.EXTRACTING))
                miss = true;
        }
        if (miss) {
            if (earlyTarget != null && earlyTargetTime < time + 1) {
                earlyTarget = null;
                earlyTargetTime = 0;
            }
            return false;
        }
        Vec3 point = hit.getLocation();
        earlyTarget = Pair.of(point.add(Vec3.atLowerCornerOf(hit.getDirection().getNormal()).scale(.25)), hit.getBlockPos());
        earlyTargetTime = (float) (time + from.distanceTo(point) / from.distanceTo(to));
        return true;
    }
    public void finish(EjectorBlockEntity be, IntAttached<ItemStack> item) {
        var inventory = targetInventory(be);
        if (inventory != null) item.setSecond(inventory.handleInsertion(item.getValue(), Direction.UP, false));
        if (item.getValue().isEmpty()) return;
        spawn(be, item.getValue(), earlyTarget == null ? location(be, endTime()) : earlyTarget.getFirst(), endTime());
    }
    public void drop(EjectorBlockEntity be, IntAttached<ItemStack> item) {
        spawn(be, item.getValue(), location(be, item.getFirst()), item.getFirst());
    }
    private void spawn(EjectorBlockEntity be, ItemStack stack, Vec3 position, float time) {
        var entity = new ItemEntity(be.getLevel(), position.x, position.y, position.z, stack);
        entity.setDeltaMovement(motion(be, time));
        entity.setDefaultPickUpDelay();
        be.getLevel().addFreshEntity(entity);
    }
    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Horizontal", horizontal());
        tag.putInt("Vertical", vertical());
        tag.putByte("Facing", (byte) facing.get3DDataValue());
        if (earlyTarget != null) {
            tag.put("Hit", VecHelper.writeNBT(earlyTarget.getFirst()));
            tag.put("HitBlock", NbtUtils.writeBlockPos(earlyTarget.getSecond()));
            tag.putFloat("HitTime", earlyTargetTime);
        }
        return tag;
    }
    public static EjectorFlight load(CompoundTag tag) {
        Direction direction = Direction.from3DDataValue(tag.getByte("Facing"));
        if (!direction.getAxis().isHorizontal()) direction = Direction.NORTH;
        var flight = new EjectorFlight(Math.max(1, tag.getInt("Horizontal")), tag.getInt("Vertical"), direction);
        float time = tag.getFloat("HitTime");
        if (tag.contains("Hit", Tag.TAG_LIST) && Float.isFinite(time) && time >= 0) {
            var point = VecHelper.readNBT(tag.getList("Hit", Tag.TAG_DOUBLE));
            if (Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z))
                NbtUtils.readBlockPos(tag, "HitBlock").ifPresent(pos -> {
                    flight.earlyTarget = Pair.of(point, pos);
                    flight.earlyTargetTime = time;
                });
        }
        return flight;
    }
}
