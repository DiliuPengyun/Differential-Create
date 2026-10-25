package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.fluids.spout.SpoutBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpoutBlockEntity.class)
public abstract class PonderSpoutVisualMixin {
    @Shadow SmartFluidTankBehaviour tank;
    @Shadow boolean sendSplash;
    @Shadow protected abstract void spawnSplash(FluidStack fluid);
    @Unique private FluidStack differentialcreate$before = FluidStack.EMPTY;
    @ModifyExpressionValue(method = {"spawnProcessingParticles", "spawnSplash"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/fluids/spout/SpoutBlockEntity;isVirtual()Z"))
    private boolean differentialcreate$actualFluidParticles(boolean virtual) { return virtual && !PhysicsPreview.enabled(((SpoutBlockEntity) (Object) this).getLevel()); }
    @WrapOperation(method = {"spawnProcessingParticles", "spawnSplash"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;addAlwaysVisibleParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private void differentialcreate$displayedParticles(Level world, ParticleOptions type, double x, double y, double z,
        double dx, double dy, double dz, Operation<Void> original) {
        if (world instanceof PonderLevel preview) {
            var source = ((SpoutBlockEntity) (Object) this).getBlockPos();
            var simulation = PonderSimulation.of(preview);
            // The nozzle and the receiving item move with their section; both
            // the falling stream and completion splash must use that same pose.
            var point = simulation.particle(source, new Vec3(x, y, z), false);
            var motion = simulation.particle(source, new Vec3(dx, dy, dz), true);
            original.call(world, type, point.x, point.y, point.z, motion.x, motion.y, motion.z);
        } else original.call(world, type, x, y, z, dx, dy, dz);
    }
    @Inject(method = "whenItemHeld", at = @At("HEAD"))
    private void differentialcreate$before(com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack item,
        com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour handler,
        CallbackInfoReturnable<com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour.ProcessingResult> cir) { differentialcreate$capture(); }
    @Inject(method = "whenItemHeld", at = @At("RETURN"))
    private void differentialcreate$after(com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack item,
        com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour handler,
        CallbackInfoReturnable<com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour.ProcessingResult> cir) { differentialcreate$splash(); }
    @Inject(method = "tick", at = @At("HEAD"))
    private void differentialcreate$beforeBlock(CallbackInfo ci) { differentialcreate$capture(); }
    @Inject(method = "tick", at = @At("RETURN"))
    private void differentialcreate$afterBlock(CallbackInfo ci) { differentialcreate$splash(); }
    @Unique private void differentialcreate$capture() {
        if (PhysicsPreview.enabled(((SpoutBlockEntity) (Object) this).getLevel())) differentialcreate$before = tank.getPrimaryHandler().getFluid().copy();
    }
    @Unique private void differentialcreate$splash() {
        if (!PhysicsPreview.enabled(((SpoutBlockEntity) (Object) this).getLevel()) || !sendSplash) return;
        spawnSplash(differentialcreate$before); sendSplash = false;
    }
}
