package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractMinecart.class)
public abstract class MinecartEntityMixin implements IMinecartDynamics {
    @Unique private final MinecartPhysics.State differentialcreate$cart = new MinecartPhysics.State();
    @Override public MinecartPhysics.State differentialcreate$cartState() { return differentialcreate$cart; }
    @Inject(method = "tick", at = @At("HEAD"))
    private void differentialcreate$mass(CallbackInfo ci) {
        var self = (AbstractMinecart) (Object) this;
        if (MinecartPhysics.enabled(self)) {
            var state = MinecartPhysics.state(self); state.actorWork = 0;
            if (self.getDeltaMovement().horizontalDistanceSqr() > 1e-16) state.dockingStart = false;
            MinecartPhysics.refresh(self);
        }
    }
    @Inject(method = "applyNaturalSlowdown", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$drag(CallbackInfo ci) {
        var self = (AbstractMinecart) (Object) this;
        if (MinecartPhysics.enabled(self)) { MinecartPhysics.slowdown(self); ci.cancel(); }
    }
    @Redirect(method = "moveAlongTrack", at = @At(value = "INVOKE", ordinal = 5,
        target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 differentialcreate$railDrive(Vec3 before, double x, double y, double z) {
        return MinecartPhysics.impulse((AbstractMinecart) (Object) this, before, new Vec3(x, y, z));
    }
    @Redirect(method = "moveAlongTrack", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/vehicle/AbstractMinecart;moveMinecartOnRail(Lnet/minecraft/core/BlockPos;)V"))
    private void differentialcreate$paidToolFeed(AbstractMinecart cart, net.minecraft.core.BlockPos pos) {
        MinecartActorMotion.move(cart, pos);
    }
    @Redirect(method = "comeOffTrack", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/vehicle/AbstractMinecart;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
    private void differentialcreate$derailedToolFeed(AbstractMinecart cart, net.minecraft.world.entity.MoverType type, Vec3 requested) {
        MinecartActorMotion.offRail(cart, type, requested);
    }
    @Redirect(method = "moveAlongTrack", at = @At(value = "INVOKE", ordinal = 0,
        target = "Lnet/minecraft/world/phys/Vec3;multiply(DDD)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 differentialcreate$railBrake(Vec3 before, double x, double y, double z) {
        return MinecartPhysics.brake((AbstractMinecart) (Object) this, before);
    }
    @ModifyConstant(method = "moveAlongTrack", constant = @Constant(doubleValue = .03))
    private double differentialcreate$brakeThreshold(double threshold) {
        var self = (AbstractMinecart) (Object) this;
        return MinecartPhysics.enabled(self) ? threshold * MinecartMotion.BASE_INERTIA / MinecartPhysics.state(self).inertia : threshold;
    }
    @Redirect(method = "moveAlongTrack", at = @At(value = "INVOKE", ordinal = 1,
        target = "Lnet/minecraft/world/entity/vehicle/AbstractMinecart;setDeltaMovement(DDD)V"))
    private void differentialcreate$railStart(AbstractMinecart cart, double x, double y, double z) {
        var before = cart.getDeltaMovement();
        var state = MinecartPhysics.state(cart);
        // A powered rail keeps feeding a loaded tool after cutting exhausts the
        // cart's speed; empty-cart native wall-start behaviour remains intact.
        if (MinecartPhysics.enabled(cart) && x == before.x && z == before.z && (state.actorWork > 0 || state.dockingStart)
            && MinecartPhysics.mounted(cart) != null && state.actorDirection.horizontalDistanceSqr() > 0) {
            var next = before.add(state.actorDirection.scale(state.dockingStart ? .02 : MinecartMotion.RAIL_ACCELERATION)); x = next.x; z = next.z;
            state.dockingStart = false;
        }
        cart.setDeltaMovement(MinecartPhysics.impulse(cart, before, new Vec3(x, y, z).subtract(before)));
    }
    @Redirect(method = "moveMinecartOnRail", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/vehicle/AbstractMinecart;isVehicle()Z"))
    private boolean differentialcreate$realRider(AbstractMinecart cart) { return MinecartPhysics.occupied(cart); }
    @Inject(method = "addAdditionalSaveData", at = @At("RETURN"))
    private void differentialcreate$save(CompoundTag tag, CallbackInfo ci) {
        MinecartPhysics.save((AbstractMinecart) (Object) this, tag);
    }
    @Inject(method = "readAdditionalSaveData", at = @At("RETURN"))
    private void differentialcreate$load(CompoundTag tag, CallbackInfo ci) {
        MinecartPhysics.load((AbstractMinecart) (Object) this, tag);
    }
}
