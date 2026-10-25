package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Fuel acceptance keeps its real effect, at the burner's displayed position. */
@Mixin(BlazeBurnerBlockEntity.class)
public abstract class PonderBurnerParticlesMixin {
    @WrapOperation(method = {"spawnParticles", "spawnParticleBurst"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private void differentialcreate$mountedFlame(Level world, ParticleOptions type, double x, double y, double z,
        double dx, double dy, double dz, Operation<Void> original) {
        if (world instanceof PonderLevel preview) {
            var source = ((BlazeBurnerBlockEntity) (Object) this).getBlockPos();
            var simulation = PonderSimulation.of(preview);
            var point = simulation.particle(source, new Vec3(x, y, z), false);
            var motion = simulation.particle(source, new Vec3(dx, dy, dz), true);
            original.call(world, type, point.x, point.y, point.z, motion.x, motion.y, motion.z);
        } else original.call(world, type, x, y, z, dx, dy, dz);
    }
}
