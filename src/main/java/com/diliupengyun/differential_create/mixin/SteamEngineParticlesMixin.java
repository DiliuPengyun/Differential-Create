package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SteamEngineBlockEntity.class)
public abstract class SteamEngineParticlesMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void differentialcreate$hoseRenderer(CallbackInfo ci) {
        com.diliupengyun.differential_create.client.SteamHoseRenderer.observe((SteamEngineBlockEntity) (Object) this);
    }
    @Inject(method = "spawnParticles", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$actualCylinderIntake(CallbackInfo ci) {
        var engine = (SteamEngineBlockEntity) (Object) this;
        // Hidden cylinders still contribute to the boiler's mechanical model,
        // but must not emit at an unused template position.
        if (engine.getLevel() instanceof PonderLevel preview
            && !PonderSimulation.of(preview).physical(preview, engine.getBlockPos())) {
            ci.cancel();
            return;
        }
        var shaft = engine.getShaft();
        Float intake = shaft == null ? null : ClientNetworkSpeeds.steamIntakeOf(shaft);
        if (intake != null && intake <= 0) ci.cancel();
    }

    @WrapOperation(method = "spawnParticles", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private void differentialcreate$displayedSteam(Level world, ParticleOptions type, double x, double y, double z,
        double dx, double dy, double dz, Operation<Void> original) {
        if (world instanceof PonderLevel preview) {
            var source = ((SteamEngineBlockEntity) (Object) this).getBlockPos();
            var simulation = PonderSimulation.of(preview);
            var point = simulation.particle(source, new Vec3(x, y, z), false);
            var motion = simulation.particle(source, new Vec3(dx, dy, dz), true);
            original.call(world, type, point.x, point.y, point.z, motion.x, motion.y, motion.z);
        } else original.call(world, type, x, y, z, dx, dy, dz);
    }
}
