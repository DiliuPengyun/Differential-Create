package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ContraptionRenderHandoff;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class ContraptionTerrainHandoffMixin {
    @Shadow private ClientLevel level;
    @Shadow @Final private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    @Shadow private SectionRenderDispatcher sectionRenderDispatcher;

    @Inject(method = "compileSections", at = @At("HEAD"))
    private void differentialcreate$finishHandoff(Camera camera, CallbackInfo ci) {
        ContraptionRenderHandoff.beforeTerrain(level, visibleSections, sectionRenderDispatcher);
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void differentialcreate$clearHandoffs(ClientLevel next, CallbackInfo ci) {
        ContraptionRenderHandoff.reset(next);
    }
}
