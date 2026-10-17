package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.IEjectorDynamics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.depot.EjectorBlockEntity;
import com.simibubi.create.content.logistics.depot.EjectorRenderer;
import java.util.List;
import net.createmod.catnip.data.IntAttached;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep native plate/depot rendering and use each flying item's launch snapshot. */
@Mixin(EjectorRenderer.class)
public abstract class EjectorRendererMixin {
    @WrapOperation(method = "renderSafe(Lcom/simibubi/create/content/logistics/depot/EjectorBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At(value = "FIELD",
        target = "Lcom/simibubi/create/content/logistics/depot/EjectorBlockEntity;launchedItems:Ljava/util/List;"))
    private List<IntAttached<ItemStack>> differentialcreate$deferFlights(EjectorBlockEntity be, Operation<List<IntAttached<ItemStack>>> original) {
        return be.isVirtual() ? original.call(be) : List.of();
    }
    @Inject(method = "renderSafe(Lcom/simibubi/create/content/logistics/depot/EjectorBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("RETURN"))
    private void differentialcreate$renderFlights(EjectorBlockEntity be, float partialTick, PoseStack pose,
        MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        if (be.isVirtual()) return;
        var dynamics = (IEjectorDynamics) be;
        var transform = dev.engine_room.flywheel.lib.transform.TransformStack.of(pose);
        for (var item : dynamics.differentialcreate$launchedItems()) {
            var flight = dynamics.differentialcreate$flight(item);
            float time = item.getFirst() + partialTick;
            if (flight == null || time > flight.endTime()) continue;
            pose.pushPose();
            transform.translate(flight.location(be, time).subtract(Vec3.atLowerCornerOf(be.getBlockPos())));
            Vec3 offset = VecHelper.voxelSpace(0, 2, -1);
            transform.translate(offset);
            if (PackageItem.isPackage(item.getValue())) {
                pose.translate(0, .25, 0);
                pose.scale(1.5f, 1.5f, 1.5f);
                transform.rotateYDegrees(time * 20);
            } else {
                pose.scale(.5f, .5f, .5f);
                transform.rotateYDegrees(AngleHelper.horizontalAngle(flight.facing()));
                transform.rotateXDegrees(time * 40);
            }
            transform.translateBack(offset);
            Minecraft.getInstance().getItemRenderer().renderStatic(item.getValue(), ItemDisplayContext.FIXED,
                light, overlay, pose, buffer, be.getLevel(), 0);
            pose.popPose();
        }
    }
}
