package com.diliupengyun.differential_create.kinetics;

import java.util.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.neoforged.neoforge.network.PacketDistributor;

/** One accepted pose for both moving-hand rendering paths, including a stopped game clock. */
public final class MovingHandSync {
    private static final Set<MovementContext> tracked = Collections.newSetFromMap(new WeakHashMap<>());
    private MovingHandSync() {}
    public static void track(MovementContext ctx) { tracked.add(ctx); }
    public static void clear() { tracked.clear(); }
    public static void flush() {
        var groups = new IdentityHashMap<AbstractContraptionEntity, List<MovingHandPayload.Hand>>();
        tracked.removeIf(ctx -> ctx.contraption.entity == null || ctx.contraption.entity.isRemoved());
        for (var ctx : tracked) {
            var entity = ctx.contraption.entity;
            if (entity.level().isClientSide || !MovingDeployerWork.supported(ctx)) continue;
            var hand = ContraptionActorWork.of(ctx).hand(); var drive = ActorDrive.of(ctx);
            var shaft = drive instanceof ContraptionPhysics.Drive s ? s.shaft() : null;
            var feed = shaft == null ? null : ActorFeed.of(shaft);
            boolean frozen = ctx.world.tickRateManager().isFrozen();
            double rpm = frozen ? 0 : shaft != null ? NetworkDynamics.preciseSpeed(shaft) : hand.cartSpeed();
            double angle = shaft != null ? ElasticKinetics.state(shaft).angle * 360 : ctx.data.getCompound(MovingDeployerWork.TAG).getDouble("Angle") * 360;
            groups.computeIfAbsent(entity, e -> new ArrayList<>()).add(new MovingHandPayload.Hand(ctx.localPos,
                (float) hand.extension(), (float) rpm, angle, hand.phase(), hand.holding(), hand.failure(),
                (net.minecraft.nbt.CompoundTag) hand.player().getMainHandItem().saveOptional(ctx.world.registryAccess()),
                shaft == null ? null : shaft.getBlockPos(), feed == null ? 0 : (float) feed.speed,
                feed == null ? 0 : feed.inertia, feed == null ? 0 : feed.brakeHeat));
        }
        groups.forEach((entity, hands) -> PacketDistributor.sendToPlayersTrackingEntity(entity,
            new MovingHandPayload(entity.level().dimension().location(), entity.getId(), hands)));
    }
}
