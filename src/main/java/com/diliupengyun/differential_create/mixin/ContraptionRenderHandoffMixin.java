package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ContraptionRenderHandoff;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionDisassemblyPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContraptionEntity.class)
public abstract class ContraptionRenderHandoffMixin {
    @Inject(method = "readAdditional", at = @At("RETURN"))
    private void differentialcreate$assemblyMesh(CompoundTag tag, boolean spawnData, CallbackInfo ci) {
        if (spawnData) ContraptionRenderHandoff.assembled((AbstractContraptionEntity) (Object) this);
    }

    @Inject(method = "handleDisassemblyPacket", at = @At("HEAD"))
    private static void differentialcreate$placementMesh(ContraptionDisassemblyPacket packet, CallbackInfo ci) {
        var world = Minecraft.getInstance().level;
        if (world != null && world.getEntity(packet.entityId()) instanceof AbstractContraptionEntity entity)
            ContraptionRenderHandoff.disassembled(entity, packet.transform());
    }
}
