package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotationIndicatorParticleData;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;

/**
 * Direction effects describe the actual shaft at emission, including while a
 * finite-torque reversal is still braking the old direction.
 */
@Mixin(targets = "com.simibubi.create.foundation.ponder.CreateSceneBuilder$EffectInstructions")
public abstract class PonderDirectionIndicatorMixin {
    @Shadow @Final private CreateSceneBuilder this$0;

    @Inject(method = "rotationDirectionIndicator", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$measuredDirection(BlockPos pos, CallbackInfo ci) {
        this$0.addInstruction(scene -> {
            var level = scene.getWorld();
            var state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof KineticBlock block)) return;
            if (!(level.getBlockEntity(pos) instanceof KineticBlockEntity be)) return;

            Axis axis = block.getRotationAxis(state);
            float speed = be.getTheoreticalSpeed();
            if (speed == 0) return;
            var speedLevel = IRotate.SpeedLevel.of(speed);
            int color = speed > 0 ? 15425035 : 1476519;
            int particleSpeed = speedLevel.getParticleSpeed() * (int) Math.signum(speed);
            var center = VecHelper.getCenterOf(pos);
            var data = new RotationIndicatorParticleData(color, (float) particleSpeed,
                block.getParticleInitialRadius(), block.getParticleTargetRadius(), 20, axis);
            for (int i = 0; i < 20; i++)
                level.addParticle(data, center.x, center.y, center.z, 0, 0, 0);
        });
        ci.cancel();
    }
}
