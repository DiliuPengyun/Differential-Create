package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawRenderer;
import net.createmod.ponder.api.level.PonderLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Moving saws select their blade animation from feed, rather than a template shaft. */
@Mixin(SawRenderer.class)
public abstract class PonderSawRendererMixin {
    @ModifyExpressionValue(method = "renderBlade", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/saw/SawBlockEntity;getSpeed()F"))
    private float differentialcreate$movingBlade(float original, SawBlockEntity be) {
        if (!(be.getLevel() instanceof PonderLevel world)) return original;
        var simulation = PonderSimulation.of(world);
        var start = simulation.mountedToolAngle(be.getBlockPos(), 0);
        var end = simulation.mountedToolAngle(be.getBlockPos(), 1);
        // Native contraption saws always use the active blade in their cutting direction.
        return start == null || end == null ? original : Math.abs(end - start) > 1e-9 ? 1 : 0;
    }
}
