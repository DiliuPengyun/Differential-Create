package com.diliupengyun.differential_create.client;

import com.diliupengyun.differential_create.kinetics.portable.PortableEndpoint;
import com.diliupengyun.differential_create.kinetics.portable.PortableDocking;
import com.simibubi.create.AllItems;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.*;

/** Functional body-position region, rather than the renderer's head pixel dimensions. */
public final class PortableDockingPreview {
    private static final Object CONTACT = new Object(), PREPARATION = new Object();
    private PortableDockingPreview() {}
    public static void tick() {
        var client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !(client.hitResult instanceof BlockHitResult hit)
            || !(AllItems.WRENCH.isIn(client.player.getMainHandItem()) || AllItems.WRENCH.isIn(client.player.getOffhandItem()))
            || !(client.level.getBlockEntity(hit.getBlockPos()) instanceof PortableEndpoint)) return;
        var state = client.level.getBlockState(hit.getBlockPos());
        var adapter = PortableDocking.adapter(state);
        var direction = adapter == null ? null : adapter.facing(state);
        if (direction == null) return;
        var normal = Vec3.atLowerCornerOf(direction.getNormal());
        var centre = Vec3.atCenterOf(hit.getBlockPos()).add(normal.scale(2.25));
        Outliner.getInstance().showAABB(CONTACT, box(centre, direction.getAxis(), .5)).colored(0x4CAF50).lineWidth(1 / 32f);
        Outliner.getInstance().showAABB(PREPARATION, box(centre, direction.getAxis(), 1.5)).colored(0xFFC107).lineWidth(1 / 64f);
    }
    private static AABB box(Vec3 centre, Direction.Axis axis, double width) {
        double x = axis == Direction.Axis.X ? .75 : width, y = axis == Direction.Axis.Y ? .75 : width, z = axis == Direction.Axis.Z ? .75 : width;
        return new AABB(centre.x - x, centre.y - y, centre.z - z, centre.x + x, centre.y + y, centre.z + z);
    }
}
