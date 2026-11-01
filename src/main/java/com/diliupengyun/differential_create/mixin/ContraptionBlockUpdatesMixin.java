package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ContraptionBlockUpdates;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Contraption.class)
public abstract class ContraptionBlockUpdatesMixin {
    @Inject(method = "addBlocksToWorld", at = @At("RETURN"))
    private void differentialcreate$publishBeforeRemoval(Level level, StructureTransform transform, CallbackInfo ci) {
        if (level instanceof ServerLevel server)
            ContraptionBlockUpdates.placed(server, (Contraption) (Object) this, transform);
    }
}
