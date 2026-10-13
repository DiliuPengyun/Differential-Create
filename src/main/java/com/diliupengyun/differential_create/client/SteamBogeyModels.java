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
    public static final PartialModel CYLINDERS=PartialModel.of(ResourceLocation.fromNamespaceAndPath("differentialcreate","block/steam_bogey/cylinders"));
    public static final PartialModel PISTONS=PartialModel.of(ResourceLocation.fromNamespaceAndPath("differentialcreate","block/steam_bogey/pistons"));
    private SteamBogeyModels() {}
    public static void init() {}
    public static double offset(CompoundTag data) {
        double angle=data.getDouble(SteamBogey.TURNS)*2*Math.PI;
        if(data.contains(SteamBogey.SAMPLE))angle+=Math.clamp(ClientKineticAngles.renderTick()-data.getDouble(SteamBogey.SAMPLE),0,10)
            *data.getDouble(SteamBogey.RPM)*Math.PI/600;
        return Math.sin(angle)/16;
    }
    public static void render(CompoundTag data,PoseStack pose,MultiBufferSource source,int light,int overlay,boolean large) {
        if(!data.getBoolean(SteamBogey.ENGINE))return;
        var buffer=source.getBuffer(RenderType.cutoutMipped());
        double y=large?.25:0;
        CachedBuffers.partial(CYLINDERS,Blocks.AIR.defaultBlockState()).translate(-.5,y,-.5).light(light).overlay(overlay).renderInto(pose,buffer);
        CachedBuffers.partial(PISTONS,Blocks.AIR.defaultBlockState()).translate(-.5,y,-.5+offset(data)).light(light).overlay(overlay).renderInto(pose,buffer);
    }
}
