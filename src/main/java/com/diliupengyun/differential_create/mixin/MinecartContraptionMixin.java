package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.MinecartPhysics;
import com.diliupengyun.differential_create.kinetics.MinecartActorMotion;
import com.diliupengyun.differential_create.kinetics.IMinecartOrientation;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlockEntity.CartMovementMode;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OrientedContraptionEntity.class)
public abstract class MinecartContraptionMixin implements IMinecartOrientation {
    @Unique private static final EntityDataAccessor<Boolean> differentialcreate$managed = SynchedEntityData.defineId(OrientedContraptionEntity.class, EntityDataSerializers.BOOLEAN);
    @Unique private static final EntityDataAccessor<Float> differentialcreate$yaw = SynchedEntityData.defineId(OrientedContraptionEntity.class, EntityDataSerializers.FLOAT);
    @Unique private static final EntityDataAccessor<Float> differentialcreate$pitch = SynchedEntityData.defineId(OrientedContraptionEntity.class, EntityDataSerializers.FLOAT);
    @Unique private static final EntityDataAccessor<Boolean> differentialcreate$rotating = SynchedEntityData.defineId(OrientedContraptionEntity.class, EntityDataSerializers.BOOLEAN);
    @Inject(method = "defineSynchedData", at = @At("RETURN"))
    private void differentialcreate$definePaidPose(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(differentialcreate$managed, false); builder.define(differentialcreate$yaw, 0f);
        builder.define(differentialcreate$pitch, 0f); builder.define(differentialcreate$rotating, false);
    }
    @Override public Snapshot differentialcreate$orientation() {
        var data = ((OrientedContraptionEntity) (Object) this).getEntityData();
        return data.get(differentialcreate$managed) ? new Snapshot(data.get(differentialcreate$yaw), data.get(differentialcreate$pitch), data.get(differentialcreate$rotating)) : null;
    }
    @Override public void differentialcreate$orientation(Snapshot snapshot) {
        var data = ((OrientedContraptionEntity) (Object) this).getEntityData();
        if (snapshot != null) {
            data.set(differentialcreate$yaw, snapshot.yaw()); data.set(differentialcreate$pitch, snapshot.pitch());
            data.set(differentialcreate$rotating, snapshot.rotating());
        }
        data.set(differentialcreate$managed, snapshot != null);
    }
    @Shadow protected abstract boolean updateOrientation(boolean rotationLock, boolean wasStalled, Entity riding, boolean isOnCoupling);
    @Redirect(method = "tickContraption", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/contraptions/OrientedContraptionEntity;updateOrientation(ZZLnet/minecraft/world/entity/Entity;Z)Z"))
    private boolean differentialcreate$paidTurningFeed(OrientedContraptionEntity self, boolean lock, boolean stalled, Entity riding, boolean coupled) {
        if (self.level().isClientSide) {
            var snapshot = differentialcreate$orientation();
            if (snapshot != null) { IMinecartOrientation.apply(self, snapshot); return snapshot.rotating(); }
            return updateOrientation(lock, stalled, riding, coupled);
        }
        float yaw = self.yaw, pitch = self.pitch;
        boolean rotating = updateOrientation(lock, stalled, riding, coupled);
        boolean managed = riding instanceof AbstractMinecart cart && MinecartActorMotion.turn(cart, self, yaw, pitch, rotating);
        if (managed && !coupled && !lock && ((MountedContraption) self.getContraption()).rotationMode == CartMovementMode.ROTATE_PAUSED)
            rotating = Math.abs(net.minecraft.util.Mth.wrapDegrees(self.targetYaw - self.yaw)) >= 1;
        differentialcreate$orientation(managed ? new Snapshot(self.yaw, self.pitch, rotating) : null);
        return rotating;
    }
    @Redirect(method = "powerFurnaceCartWithFuelFromStorage", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void differentialcreate$directionWithoutFreeSpeed(Entity cart, Vec3 direction) {
        MinecartPhysics.furnaceDirection(cart, direction);
    }
    @Inject(method = "powerFurnaceCartWithFuelFromStorage", at = @At("RETURN"))
    private void differentialcreate$restartRefuelledTool(Entity cart, CallbackInfo ci) { MinecartPhysics.refuelled(cart); }
}
