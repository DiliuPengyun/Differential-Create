package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import com.diliupengyun.differential_create.mixin.ContraptionAccessor;
import com.simibubi.create.AllAttachmentTypes;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.contraptions.minecart.MinecartSim2020;
import com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController;
import com.simibubi.create.content.contraptions.minecart.capability.MinecartController;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.MinecartFurnace;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.phys.Vec3;

/** A native cart retains rail geometry and item/entity behaviour. Mounted mass,
 * air drag, propulsion and coupling reactions use one reference inertia. */
public final class MinecartPhysics {
    public static final class State {
        public double inertia = MinecartMotion.BASE_INERTIA;
        public boolean awaitingStructure;
        public boolean assembling;
        public double savedPushX, savedPushZ;
        public double actorWork;
        public Vec3 actorDirection = Vec3.ZERO;
    }
    private record Body(List<AbstractContraptionEntity> structures, double share, boolean incomplete) {}
    private MinecartPhysics() {}
    public static State state(AbstractMinecart cart) { return ((IMinecartDynamics) cart).differentialcreate$cartState(); }
    public static boolean enabled(AbstractMinecart cart) { return !cart.level().isClientSide; }
    public static boolean occupied(AbstractMinecart cart) {
        return cart.getPassengers().stream().anyMatch(e -> !(e instanceof AbstractContraptionEntity));
    }
    public static AbstractContraptionEntity mounted(AbstractMinecart cart) {
        for (var passenger : cart.getPassengers())
            if (passenger instanceof AbstractContraptionEntity entity && !entity.isRemoved()
                && entity.getContraption() instanceof MountedContraption) return entity;
        return null;
    }
    private static AbstractMinecart otherSupport(AbstractMinecart cart) {
        var controller = cart.getData(AllAttachmentTypes.MINECART_CONTROLLER);
        for (boolean main : new boolean[]{true, false}) if (controller.hasContraptionCoupling(main)) {
            var id = controller.getCoupledCart(main);
            var other = CapabilityMinecartController.getIfPresent(cart.level(), id);
            if (other != null && other.isPresent()) return other.cart();
            if (cart.level() instanceof ServerLevel level && level.getEntity(id) instanceof AbstractMinecart support) return support;
        }
        return null;
    }
    static List<AbstractMinecart> supports(AbstractMinecart cart) {
        var other = cart.getData(AllAttachmentTypes.MINECART_CONTROLLER).isCoupledThroughContraption() ? otherSupport(cart) : null;
        return other == null || other == cart ? List.of(cart) : List.of(cart, other);
    }
    private static Body body(AbstractMinecart cart) {
        var root = mounted(cart); var other = otherSupport(cart);
        if (root == null && other != null) root = mounted(other);
        if (root == null) return new Body(List.of(), 1, false);
        var structures = new ArrayList<AbstractContraptionEntity>(); structures.add(root);
        var visited = new HashSet<java.util.UUID>(); visited.add(root.getUUID());
        boolean incomplete = false;
        for (int i = 0; i < structures.size(); i++) {
            var parent = structures.get(i);
            for (var id : ((ContraptionAccessor) parent.getContraption()).differentialcreate$stabilizedChildren().keySet()) {
                Entity child = parent.getPassengers().stream().filter(e -> e.getUUID().equals(id)).findFirst().orElse(null);
                if (child == null && cart.level() instanceof ServerLevel level) child = level.getEntity(id);
                if (child instanceof AbstractContraptionEntity entity && !entity.isRemoved() && entity.getContraption() != null) {
                    if (visited.add(id)) structures.add(entity);
                } else incomplete = true;
            }
        }
        // One mounted body carried by two carts contributes half to each support.
        return new Body(structures, cart.getData(AllAttachmentTypes.MINECART_CONTROLLER).isCoupledThroughContraption() ? .5 : 1, incomplete);
    }
    static List<AbstractContraptionEntity> structures(AbstractMinecart cart) { return body(cart).structures; }
    public static double refresh(AbstractMinecart cart) {
        // Native furnace assembly/disassembly reads its own saved entity NBT.
        // Create deserializes the controller with a null cart reference; waiting
        // for a player tracking event would leave activation/coupling inert.
        var controller = cart.getData(AllAttachmentTypes.MINECART_CONTROLLER);
        if (enabled(cart) && controller != MinecartController.EMPTY && controller.cart() != cart) controller.sendData(cart);
        var state = state(cart); var body = body(cart);
        if (body.structures.isEmpty() && cart.getData(AllAttachmentTypes.MINECART_CONTROLLER).isCoupledThroughContraption()) {
            var other = otherSupport(cart);
            if (other == null || state(other).awaitingStructure && other.tickCount < 20) return state.inertia;
            // Both supports are loaded. An ejected/disassembled body no longer
            // contributes mass even if Create retains its coupling metadata.
        }
        if (state.awaitingStructure && (body.structures.isEmpty() && cart.tickCount < 20 || body.incomplete)) return state.inertia;
        double next = MinecartMotion.BASE_INERTIA;
        for (var entity : body.structures) next += body.share * ContraptionPhysics.shape(entity.getContraption()).linearInertia();
        if (body.incomplete && next < state.inertia) return state.inertia;
        double factor = MinecartMotion.attachedSpeed(1, state.inertia, next);
        if (factor != 1) cart.setDeltaMovement(cart.getDeltaMovement().scale(factor));
        state.inertia = next;
        state.awaitingStructure = false;
        return next;
    }
    public static void save(AbstractMinecart cart, CompoundTag tag) {
        tag.putDouble("DCMinecartInertia", state(cart).inertia);
        tag.putBoolean("DCMinecartStructure", !body(cart).structures.isEmpty() || state(cart).awaitingStructure);
        var direction = state(cart).actorDirection;
        if (direction.lengthSqr() > 0) {
            var feed = new ListTag(); feed.add(DoubleTag.valueOf(direction.x)); feed.add(DoubleTag.valueOf(direction.y)); feed.add(DoubleTag.valueOf(direction.z));
            tag.put("DCMinecartFeed", feed);
        }
    }
    public static void load(AbstractMinecart cart, CompoundTag tag) {
        double saved = tag.getDouble("DCMinecartInertia");
        state(cart).inertia = Double.isFinite(saved) && saved >= MinecartMotion.BASE_INERTIA ? saved : MinecartMotion.BASE_INERTIA;
        state(cart).awaitingStructure = tag.getBoolean("DCMinecartStructure");
        var feed = tag.getList("DCMinecartFeed", Tag.TAG_DOUBLE);
        var direction = feed.size() == 3 ? new Vec3(feed.getDouble(0), feed.getDouble(1), feed.getDouble(2)) : Vec3.ZERO;
        state(cart).actorDirection = Double.isFinite(direction.lengthSqr()) && direction.lengthSqr() > 1e-12 ? direction.normalize() : Vec3.ZERO;
    }
    private static double airDrag(AbstractMinecart cart, Vec3 movement) {
        if (movement.horizontalDistanceSqr() == 0) return 0;
        var body = body(cart); double result = 0;
        var direction = new Vec3(movement.x, 0, movement.z).normalize().scale(ContraptionGeometry.LINEAR_RADIUS);
        for (var entity : body.structures) {
            var local = entity.reverseRotation(direction, 1);
            result += body.share * ContraptionPhysics.shape(entity.getContraption()).translatedDrag(local.x, local.y, local.z);
        }
        return result;
    }
    private static Vec3 speed(Vec3 vector, double magnitude) {
        return vector.horizontalDistanceSqr() == 0 ? Vec3.ZERO
            : new Vec3(vector.x, 0, vector.z).scale(magnitude / vector.horizontalDistance());
    }
    /** Native drag constants, including container fullness and water, calibrated
     * at the unassembled reference mass. A Create structure is not a seated rider. */
    public static void slowdown(AbstractMinecart cart) {
        double j = refresh(cart); Vec3 motion = cart.getDeltaMovement();
        double factor = occupied(cart) ? .997 : .96;
        if (cart instanceof AbstractMinecartContainer container) {
            float f = .98F;
            if (container.getLootTable() == null) f += (15 - AbstractContainerMenu.getRedstoneSignalFromContainer(container)) * .001F;
            if (cart.isInWater()) f *= .95F;
            factor = f;
        } else {
            if (cart instanceof MinecartFurnace furnace) {
                double push = Math.hypot(furnace.xPush, furnace.zPush);
                if (push > Math.sqrt(1e-7)) {
                    furnace.xPush /= push; furnace.zPush /= push;
                    double a = cart.isInWater() ? .08 : .8, b = cart.isInWater() ? .1 : 1;
                    // Apply the same affine motor law in each horizontal component.
                    var proposed = new Vec3(MinecartMotion.affine(motion.x, a, b * furnace.xPush, j), 0,
                        MinecartMotion.affine(motion.z, a, b * furnace.zPush, j));
                    motion = limitedDrive(motion, proposed, j, MinecartMotion.FURNACE_ACCELERATION, MinecartMotion.FURNACE_POWER);
                } else motion = speed(motion, MinecartMotion.coast(motion.horizontalDistance(), .98, j, 0));
            }
            if (cart.isInWater()) factor *= .95F;
        }
        double next = MinecartMotion.coast(motion.horizontalDistance(), factor, j, airDrag(cart, motion));
        cart.setDeltaMovement(speed(motion, next));
    }
    private static Vec3 limitedDrive(Vec3 before, Vec3 next, double j, double acceleration, double power) {
        var delta = next.subtract(before);
        double bound = acceleration * MinecartMotion.BASE_INERTIA / j;
        if (delta.horizontalDistance() > bound) next = before.add(delta.scale(bound / delta.horizontalDistance()));
        double allowed = MinecartMotion.energy(j, before.horizontalDistance()) + power * RotationMath.TICK_MINUTES;
        if (MinecartMotion.energy(j, next.horizontalDistance()) > allowed)
            next = speed(next, Math.sqrt(2 * allowed / j) / MinecartMotion.RPM_PER_SPEED);
        return next;
    }
    public static Vec3 impulse(AbstractMinecart cart, Vec3 before, Vec3 delta) {
        if (!enabled(cart)) return before.add(delta);
        double previousInertia = state(cart).inertia, j = refresh(cart);
        before = before.scale(MinecartMotion.attachedSpeed(1, previousInertia, j));
        Vec3 next = before.add(delta.scale(MinecartMotion.BASE_INERTIA / j));
        double allowed = MinecartMotion.energy(j, before.horizontalDistance()) + MinecartMotion.RAIL_POWER * RotationMath.TICK_MINUTES;
        if (MinecartMotion.energy(j, next.horizontalDistance()) > allowed)
            next = speed(next, Math.sqrt(2 * allowed / j) / MinecartMotion.RPM_PER_SPEED);
        return next;
    }
    public static Vec3 brake(AbstractMinecart cart, Vec3 before) {
        if (!enabled(cart)) return before.multiply(.5, 0, .5);
        double previousInertia = state(cart).inertia, j = refresh(cart);
        before = before.scale(MinecartMotion.attachedSpeed(1, previousInertia, j));
        return speed(before, MinecartMotion.coast(before.horizontalDistance(), .5, j, 0));
    }
    public static void target(AbstractMinecart cart, Vec3 target) {
        if (!enabled(cart)) { cart.setDeltaMovement(target); return; }
        double j = refresh(cart); var before = cart.getDeltaMovement();
        var delta = target.subtract(before); double distance = delta.horizontalDistance();
        if (distance == 0) return;
        double amount = Math.min(distance, MinecartMotion.CONTROL_ACCELERATION) * MinecartMotion.BASE_INERTIA / j;
        var next = before.add(delta.scale(amount / distance));
        double allowed = MinecartMotion.energy(j, before.horizontalDistance()) + MinecartMotion.CONTROL_POWER * RotationMath.TICK_MINUTES;
        if (MinecartMotion.energy(j, next.horizontalDistance()) > allowed)
            next = speed(next, Math.sqrt(2 * allowed / j) / MinecartMotion.RPM_PER_SPEED);
        cart.setDeltaMovement(next);
    }
    public static void assembled(AbstractMinecart cart) {
        if (!enabled(cart)) return;
        if (mounted(cart) != null && cart instanceof MinecartFurnace furnace) {
            furnace.xPush = state(cart).savedPushX; furnace.zPush = state(cart).savedPushZ;
        }
        refresh(cart); var other = otherSupport(cart); if (other != null) refresh(other);
    }
    public static void furnaceDirection(Entity riding, Vec3 direction) {
        if (riding instanceof MinecartFurnace furnace) {
            if (direction.horizontalDistanceSqr() == 0 && mounted(furnace) != null) direction = state(furnace).actorDirection;
            if (direction.horizontalDistanceSqr() > 0) { furnace.xPush = direction.x; furnace.zPush = direction.z; }
        }
        // Fuel replenishment remains native; it must not also assign a free velocity.
    }
    public static void refuelled(Entity riding) {
        if (riding instanceof MinecartFurnace furnace && enabled(furnace)
            && ((com.simibubi.create.foundation.mixin.accessor.MinecartFurnaceAccessor) furnace).create$getFuel() > 1
            && Math.hypot(furnace.xPush, furnace.zPush) < Math.sqrt(1e-7)) furnaceDirection(furnace, furnace.getDeltaMovement());
    }
    static Vec3 tangent(AbstractMinecart cart) {
        BlockPos pos = cart.getCurrentRailPosition(); var block = cart.level().getBlockState(pos);
        if (block.getBlock() instanceof BaseRailBlock rail)
            return MinecartSim2020.getRailVec(rail.getRailDirection(block, cart.level(), pos, cart));
        var motion = cart.getDeltaMovement();
        return motion.horizontalDistanceSqr() > 0 ? new Vec3(motion.x, 0, motion.z).normalize() : new Vec3(1, 0, 0);
    }
    public static void coupling(Level level, Couple<AbstractMinecart> carts, double length) {
        var first = carts.getFirst(); var second = carts.getSecond();
        double a = refresh(first), b = refresh(second);
        Vec3 separation = second.position().subtract(first.position()); double distance = separation.length();
        if (distance < 1e-9) return;
        var normal = separation.scale(1 / distance); var axisA = tangent(first); var axisB = tangent(second);
        boolean moveA = !level.tickRateManager().isEntityFrozen(first) && !first.getData(AllAttachmentTypes.MINECART_CONTROLLER).isStalled();
        boolean moveB = !level.tickRateManager().isEntityFrozen(second) && !second.getData(AllAttachmentTypes.MINECART_CONTROLLER).isStalled();
        var result = MinecartMotion.couple(first.getDeltaMovement().dot(axisA), second.getDeltaMovement().dot(axisB),
            a, b, axisA.dot(normal), axisB.dot(normal), distance - length, moveA, moveB);
        if (moveA) first.setDeltaMovement(axisA.scale(result.first()));
        if (moveB) second.setDeltaMovement(axisB.scale(result.second()));
    }
}
