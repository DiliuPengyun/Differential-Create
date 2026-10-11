package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.MinecartActorMotion;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(Entity.class)
public abstract class MinecartSubstepMixin {
    /** Native collision checks still run. Only its coarse movement deadband
     * is removed while a paid cart substep is crossing a tool contact face. */
    @ModifyConstant(method = "move", constant = @Constant(doubleValue = 1.0E-7, ordinal = 1))
    private double differentialcreate$acceptSmallCartStep(double threshold) {
        return MinecartActorMotion.preciseStep((Entity) (Object) this) ? 0 : threshold;
    }
}
