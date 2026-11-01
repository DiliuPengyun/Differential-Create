package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.PistonScenes;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.ParrotElement;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PistonScenes.class)
public abstract class PistonSceneMixin {
    @Inject(method = "movement", at = @At("RETURN"))
    private static void differentialcreate$finishAfterLoadArrives(SceneBuilder builder,
        net.createmod.ponder.api.scene.SceneBuildingUtil util, CallbackInfo ci) {
        builder.idle(60);
        builder.addInstruction(new com.diliupengyun.differential_create.client.ponder.PonderAwait(scene ->
            PonderSimulation.of(scene.getWorld()).point(new net.minecraft.core.BlockPos(3, 1, 1)).x <= 1.001, 160));
    }

    @WrapOperation(method = "poles", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$SpecialInstructions;moveParrot(Lnet/createmod/ponder/api/element/ElementLink;Lnet/minecraft/world/phys/Vec3;I)V"))
    private static void differentialcreate$rideActualPiston(CreateSceneBuilder.SpecialInstructions special,
        ElementLink<ParrotElement> bird, Vec3 delta, int duration, Operation<Void> original,
        @Local(argsOnly = true) SceneBuilder builder, @Local(ordinal = 0) ElementLink<WorldSectionElement> platform) {
        builder.addInstruction(scene -> {
            var passenger = scene.resolve(bird);
            var support = scene.resolve(platform);
            if (passenger != null && support != null) PonderSimulation.of(scene.getWorld()).passenger(passenger, support);
        });
        // The real 16 RPM stroke takes about 65 ticks, including startup.
        // Reserve its playback time instead of finishing at the old scripted 40 ticks.
        builder.idle(80);
    }
}
