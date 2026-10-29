package com.diliupengyun.differential_create.kinetics;

import java.util.Map;
import java.util.WeakHashMap;
import com.diliupengyun.differential_create.mixin.LinearActuatorAccessor;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.TranslatingContraption;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/** Native collision rollback must also leave a unilateral support in the physical solve. */
public final class LinearActuatorContact {
    private record Contact(Vec3 position, Direction direction) {}
    private static final ThreadLocal<Map<AbstractContraptionEntity, Contact>> contacts = ThreadLocal.withInitial(WeakHashMap::new);
    private LinearActuatorContact() {}

    /** Called after native placement policy has either disassembled or retained the body. */
    public static void collided(LinearActuatorBlockEntity actuator, Vec3 motion) {
        var root = actuator.movedContraption;
        if (root == null || root.isRemoved() || motion.lengthSqr() == 0 || !root.supportsTerrainCollision()) return;
        contacts.get().put(root, new Contact(root.position(), Direction.getNearest(motion.x, motion.y, motion.z)));
        // A native wait blocks both directions and is cleared by every RPM notification.
        // This contact instead supports only motion into the obstruction, independent of RPM.
        ((LinearActuatorAccessor) actuator).differentialcreate$setWaiting(false);
        root.setContraptionMotion(Vec3.ZERO);
        actuator.sendData();
    }

    /** Refresh once per physical tick. Retreat or removal of the obstacle releases the support. */
    public static void refresh(AbstractContraptionEntity root) {
        var contact = contacts.get().get(root);
        if (contact != null && (root.isRemoved() || root.position().distanceToSqr(contact.position) > 1e-12
            || !blocked(root, contact))) contacts.get().remove(root);
    }

    public static int stop(AbstractContraptionEntity root, Vec3 translation) {
        var contact = contacts.get().get(root);
        return contact == null ? 0 : (int) Math.signum(translation.dot(Vec3.atLowerCornerOf(contact.direction.getNormal())));
    }

    private static boolean blocked(AbstractContraptionEntity root, Contact contact) {
        if (!(root.getContraption() instanceof TranslatingContraption contraption)) return false;
        var level = root.level();
        // Probe just across the touching grid face, without moving or ticking the entity.
        var step = Vec3.atLowerCornerOf(contact.direction.getNormal()).scale(1e-4);
        var grid = BlockPos.containing(contact.position.add(step));
        if (contact.direction.getAxisDirection() == Direction.AxisDirection.POSITIVE) grid = grid.relative(contact.direction);
        if (ContraptionCollider.isCollidingWithWorld(level, contraption, grid, contact.direction)) return true;
        // Keep the same moving-structure collision rules as Create's collideBlocks.
        var bounds = root.getBoundingBox();
        for (var other : level.getEntitiesOfClass(ControlledContraptionEntity.class, bounds.inflate(1), e -> e != root)) {
            if (!other.supportsTerrainCollision() || !(other.getContraption() instanceof TranslatingContraption otherBody)
                || !bounds.move(step).intersects(other.getBoundingBox().move(other.getDeltaMovement()))) continue;
            for (var local : contraption.getOrCreateColliders(level, contact.direction))
                if (otherBody.getBlocks().containsKey(local.offset(grid).subtract(BlockPos.containing(other.position())))) return true;
        }
        return false;
    }

    public static void forget(AbstractContraptionEntity root) { contacts.get().remove(root); }
    public static void clear() { contacts.get().clear(); }
    public static void save(AbstractContraptionEntity root, CompoundTag tag) {
        var contact = contacts.get().get(root); if (contact == null) return;
        var saved = new CompoundTag();
        saved.putInt("Direction", contact.direction.get3DDataValue());
        saved.putDouble("X", contact.position.x); saved.putDouble("Y", contact.position.y); saved.putDouble("Z", contact.position.z);
        tag.put("DCLinearContact", saved);
    }
    public static void load(AbstractContraptionEntity root, CompoundTag tag) {
        contacts.get().remove(root);
        if (!tag.contains("DCLinearContact")) return;
        var saved = tag.getCompound("DCLinearContact");
        var position = new Vec3(saved.getDouble("X"), saved.getDouble("Y"), saved.getDouble("Z"));
        int direction = saved.getInt("Direction");
        if (direction >= 0 && direction < 6 && Double.isFinite(position.lengthSqr()))
            contacts.get().put(root, new Contact(position, Direction.from3DDataValue(direction)));
    }
}
