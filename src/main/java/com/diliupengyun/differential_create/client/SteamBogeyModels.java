package com.diliupengyun.differential_create.client;

import com.diliupengyun.differential_create.trains.SteamBogey;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

public final class SteamBogeyModels {
    public static final PartialModel ENGINE_COVER=PartialModel.of(ResourceLocation.fromNamespaceAndPath("differentialcreate","block/steam_bogey/engine_cover"));
    // Keep a thin surface gap after the native bogey pose's 1/128-block offset.
    public static final float COVER_Y=1+1/64f, COVER_SCALE=1+1/512f;
    private SteamBogeyModels() {}
    public static void init() {}
    public static void render(CompoundTag data,PoseStack pose,MultiBufferSource source,int light,int overlay) {
        if(!data.getBoolean(SteamBogey.ENGINE))return;
        var buffer=source.getBuffer(RenderType.cutoutMipped());
        // The engine cover occupies only the lid and upper wood panels. All
        // native brass edging, casing, adhesive faces and wheel parts remain visible.
        CachedBuffers.partial(ENGINE_COVER,Blocks.AIR.defaultBlockState()).translate(-.5,COVER_Y,-.5).center().scale(COVER_SCALE,1,COVER_SCALE).uncenter()
            .light(light).overlay(overlay).renderInto(pose,buffer);
    }
}
