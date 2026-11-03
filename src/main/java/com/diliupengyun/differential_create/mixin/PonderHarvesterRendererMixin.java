package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderToolAnimation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.actors.harvester.HarvesterBlockEntity;
import com.simibubi.create.content.contraptions.actors.harvester.HarvesterRenderer;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HarvesterRenderer.class)
public abstract class PonderHarvesterRendererMixin {
    @WrapOperation(method = "renderSafe", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/contraptions/actors/harvester/HarvesterRenderer;transform(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Direction;Lnet/createmod/catnip/render/SuperByteBuffer;FLnet/minecraft/world/phys/Vec3;)V"))
    private void differentialcreate$integratedBlade(Level world, Direction facing, SuperByteBuffer buffer, float speed, Vec3 pivot,
        Operation<Void> original, HarvesterBlockEntity be, float partialTicks) {
        if (!PonderToolAnimation.transform(be, partialTicks, facing, buffer, pivot)) original.call(world, facing, buffer, speed, pivot);
    }
}
