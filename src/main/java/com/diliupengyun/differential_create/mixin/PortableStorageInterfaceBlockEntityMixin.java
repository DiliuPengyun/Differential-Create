package com.diliupengyun.differential_create.mixin;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.portable.*;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(PortableStorageInterfaceBlockEntity.class)
public abstract class PortableStorageInterfaceBlockEntityMixin implements PortableEndpoint, IHaveGoggleInformation {
    @Shadow protected int transferTimer;
    @Shadow protected float distance;
    @Shadow protected LerpedFloat connectionAnimation;
    @Shadow protected Entity connectedEntity;
    @Shadow protected boolean powered;
    @Shadow public abstract void startTransferringTo(Contraption contraption, float distance);
    @Shadow protected abstract void stopTransferring();
    @Shadow protected abstract Integer getTransferTimeout();
    @Unique private DockingState differentialcreate$docking;
    @Override public DockingState differentialcreate$docking() {
        if (differentialcreate$docking == null) differentialcreate$docking = new DockingState((PortableStorageInterfaceBlockEntity) (Object) this);
        return differentialcreate$docking;
    }
    @Override public void differentialcreate$openStorage(Contraption contraption, float distance) { startTransferringTo(contraption, distance); }
    @Override public void differentialcreate$closeStorage() { stopTransferring(); }
    @Override public void differentialcreate$attachStorage(Contraption contraption, float span) {
        var state = differentialcreate$docking();
        if (!state.opening) state.legacyConnection();
        state.nativeStarted(contraption);
        distance = Math.clamp(span, 0, 2); connectedEntity = contraption.entity;
        if (!state.managed) transferTimer = 14;
    }
    @Override public int differentialcreate$timeout() { return Math.max(1, getTransferTimeout()); }
    @Override public boolean differentialcreate$legacyPermission() {
        return !powered && connectedEntity != null && connectedEntity.isAlive() && transferTimer >= 4 && transferTimer <= getTransferTimeout() + 4;
    }
    @Override public void differentialcreate$animate(float progress, float span) {
        distance = span; connectionAnimation.setValue(progress);
        transferTimer = differentialcreate$docking().phase == DockingState.Phase.TRANSFER ? getTransferTimeout() + 4 : 0;
    }
    @Inject(method = "addBehaviours", at = @At("TAIL"))
    private void differentialcreate$settings(List<BlockEntityBehaviour> behaviours, CallbackInfo ci) {
        var owner = (PortableStorageInterfaceBlockEntity) (Object) this;
        behaviours.add(new DockingSettingBehaviour(owner, true)); behaviours.add(new DockingSettingBehaviour(owner, false));
    }
    @Inject(method = "initialize", at = @At("TAIL"))
    private void differentialcreate$register(CallbackInfo ci) { PortableDocking.register(differentialcreate$docking()); }
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;tick()V", shift = At.Shift.AFTER), cancellable = true)
    private void differentialcreate$tick(CallbackInfo ci) {
        var state = differentialcreate$docking(); PortableDocking.register(state);
        if (!state.managed || state.owner.isVirtual()) return;
        ci.cancel();
        if (state.owner.getLevel().isClientSide) state.clientTick(); else state.tick();
        differentialcreate$animate(state.extension(), state.distance());
    }
    @Inject(method = "canTransfer", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$permission(CallbackInfoReturnable<Boolean> cir) { cir.setReturnValue(differentialcreate$docking().permission()); }
    @Inject(method = "isConnected", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$connected(CallbackInfoReturnable<Boolean> cir) {
        var state = differentialcreate$docking();
        if (state.managed) cir.setReturnValue(state.owner.getLevel() != null && state.owner.getLevel().isClientSide ? state.phase == DockingState.Phase.TRANSFER : state.permission());
        else if (powered) cir.setReturnValue(false);
    }
    @Inject(method = "isTransferring", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$transferring(CallbackInfoReturnable<Boolean> cir) { if (differentialcreate$docking().managed) cir.setReturnValue(differentialcreate$docking().phase == DockingState.Phase.TRANSFER); }
    @Inject(method = "startTransferringTo", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$session(Contraption contraption, float distance, CallbackInfo ci) {
        var state = differentialcreate$docking();
        if (!state.accepts(contraption)) { ci.cancel(); return; }
        if (!state.opening) state.legacyConnection();
        state.nativeStarted(contraption);
    }
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$connecting(CallbackInfo ci) { if (differentialcreate$docking().managed) ci.cancel(); }
    @Inject(method = "stopTransferring", at = @At("HEAD"))
    private void differentialcreate$invalidateSession(CallbackInfo ci) { differentialcreate$docking().nativeClosed(); }
    @Inject(method = "onContentTransferred", at = @At("HEAD"))
    private void differentialcreate$renew(CallbackInfo ci) {
        var state = differentialcreate$docking();
        if (state.managed) state.renew();
    }
    @Inject(method = "neighbourChanged", at = @At("TAIL"))
    private void differentialcreate$redstone(CallbackInfo ci) {
        var state = differentialcreate$docking();
        if (powered) state.cancel(); else PortableDocking.reenable(state);
        state.changed();
    }
    @Inject(method = "invalidate", at = @At("HEAD"))
    private void differentialcreate$remove(CallbackInfo ci) { PortableDocking.remove(differentialcreate$docking()); }
    @Inject(method = "write", at = @At("TAIL"))
    private void differentialcreate$save(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
        var own = new CompoundTag(); differentialcreate$docking().write(own); tag.put("DCPortDocking", own);
    }
    @Inject(method = "read", at = @At("TAIL"))
    private void differentialcreate$load(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
        if (tag.contains("DCPortDocking")) differentialcreate$docking().read(tag.getCompound("DCPortDocking"), packet);
    }
    @Override public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        var state = differentialcreate$docking();
        tooltip.add(Component.translatable("gui.differentialcreate.portable.state", Component.translatable("gui.differentialcreate.portable.phase." + state.phase.name().toLowerCase(java.util.Locale.ROOT))));
        tooltip.add(Component.translatable("gui.differentialcreate.portable.speed", String.format(java.util.Locale.ROOT, "%.2f", state.speed)));
        tooltip.add(Component.translatable("gui.differentialcreate.portable.load", String.format(java.util.Locale.ROOT, "%.1f", state.torque)));
        if (!state.failure.equals("none") && !state.failure.isEmpty()) tooltip.add(Component.translatable("gui.differentialcreate.portable.failure." + state.failure));
        return true;
    }
}
