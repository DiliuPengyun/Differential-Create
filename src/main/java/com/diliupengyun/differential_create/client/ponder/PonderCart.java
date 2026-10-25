package com.diliupengyun.differential_create.client.ponder;

import com.diliupengyun.differential_create.kinetics.MinecartMotion;
import net.createmod.ponder.api.element.MinecartElement;
import net.minecraft.world.phys.Vec3;

/** A finite hand/rail drive command; actual cart mass, power and braking determine travel. */
final class PonderCart {
    final MinecartElement element;
    Vec3 velocity = Vec3.ZERO, requested = Vec3.ZERO;
    Vec3 previousVelocity = Vec3.ZERO;
    double inertia = MinecartMotion.BASE_INERTIA;
    int remaining;
    int commandedTicks;
    double drag;
    PonderCart(MinecartElement element) { this.element = element; }
    void command(Vec3 delta, int ticks) { requested = delta.scale(1d / Math.max(1, ticks)); remaining = commandedTicks = ticks; }
    void tick(net.createmod.ponder.api.level.PonderLevel world) {
        previousVelocity = velocity;
        var input = remaining-- > 0 ? requested : Vec3.ZERO;
        var accessor = (com.diliupengyun.differential_create.mixin.PonderMinecartAccessor) element;
        var entity = accessor.differentialcreate$cart();
        var at = net.minecraft.core.BlockPos.containing(accessor.differentialcreate$origin().add(element.getPositionOffset()));
        var rail = world.getBlockState(at);
        if (!(rail.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock)) rail = world.getBlockState(at.below());
        if (input.lengthSqr() > 0 && rail.getBlock() instanceof com.simibubi.create.content.redstone.rail.ControllerRailBlock && entity != null) {
            double target = entity.getMaxSpeedWithRail() * rail.getValue(com.simibubi.create.content.redstone.rail.ControllerRailBlock.POWER) / 15d;
            input = Vec3.atLowerCornerOf(com.simibubi.create.content.redstone.rail.ControllerRailBlock.getAccelerationVector(rail)).normalize().scale(target);
        }
        double x = MinecartMotion.target(velocity.x, input.x, inertia);
        double y = MinecartMotion.target(velocity.y, input.y, inertia);
        double z = MinecartMotion.target(velocity.z, input.z, inertia);
        double factor = entity instanceof net.minecraft.world.entity.vehicle.AbstractMinecartContainer ? .995 : .96;
        x = MinecartMotion.coast(x, factor, inertia, drag); z = MinecartMotion.coast(z, factor, inertia, drag);
        Vec3 next = new Vec3(x, y, z);
        velocity = next;
    }
    void integrate() { element.setPositionOffset(element.getPositionOffset().add(previousVelocity.add(velocity).scale(.5)), false); }
}
