package com.diliupengyun.differential_create.client;

import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/** Interpolate accepted poses once, then hold. Lost packets and frozen ticks cannot invent strokes. */
public final class ClientMovingHands {
    private static final String RENDER = "DCHandRender";
    private ClientMovingHands() {}
    public static boolean managed(MovementContext ctx) {
        return !(ctx.contraption instanceof CarriageContraption) && ctx.data.contains(MovingDeployerWork.TAG);
    }
    public static void apply(MovingHandPayload packet) {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().equals(packet.dimension())
            || !(level.getEntity(packet.entityId()) instanceof AbstractContraptionEntity entity) || entity.getContraption() == null) return;
        for (var h : packet.hands()) for (var pair : entity.getContraption().getActors()) {
            var ctx = pair.getRight();
            if (ctx == null || !ctx.localPos.equals(h.localPos()) || !MovingDeployerWork.isHand(ctx)) continue;
            if (!ctx.data.contains(MovingDeployerWork.TAG)) ctx.data.put(MovingDeployerWork.TAG, new CompoundTag());
            var pose = ctx.data.getCompound(RENDER); boolean previous = ctx.data.contains(RENDER);
            pose.putDouble("Previous", previous ? pose.getDouble("Current") : h.extension());
            pose.putDouble("Current", h.extension()); pose.putDouble("PreviousAngle", previous ? pose.getDouble("Angle") : h.angle());
            pose.putDouble("Angle", h.angle()); pose.putLong("At", level.getGameTime()); ctx.data.put(RENDER, pose);
            ctx.data.put("HeldItem", h.held());
            if (h.controller() != null && level.getBlockEntity(h.controller()) instanceof KineticBlockEntity be) {
                var feed = ActorFeed.of(be); feed.active = feed.initialized = true;
                feed.speed = h.feedSpeed(); feed.inertia = h.inertia(); feed.brakeHeat = h.brakeHeat();
                // The rotor packet owns the controller's actual brake state.
                // An idle planter packet must not release a miner's brake.
            }
        }
    }
    private static double factor(MovementContext ctx) {
        return Math.clamp(ctx.world.getGameTime() - ctx.data.getCompound(RENDER).getLong("At")
            + Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false), 0, 1);
    }
    public static double extension(MovementContext ctx) {
        if (!ctx.data.contains(RENDER)) return ContraptionActorWork.of(ctx).hand().extension();
        var p = ctx.data.getCompound(RENDER); return Mth.lerp(factor(ctx), p.getDouble("Previous"), p.getDouble("Current"));
    }
    public static float angle(MovementContext ctx) {
        var p = ctx.data.getCompound(RENDER);
        return (float) (Mth.lerp(factor(ctx), p.getDouble("PreviousAngle"), p.getDouble("Angle")) % 360);
    }
}
