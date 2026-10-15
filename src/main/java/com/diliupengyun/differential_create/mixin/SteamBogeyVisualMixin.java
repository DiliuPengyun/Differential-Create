package com.diliupengyun.differential_create.mixin;

import java.util.function.Consumer;
import com.diliupengyun.differential_create.client.SteamBogeyModels;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.bogey.StandardBogeyVisual;
import com.simibubi.create.foundation.render.SpecialModels;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StandardBogeyVisual.class)
public abstract class SteamBogeyVisualMixin {
    @Unique private TransformedInstance differentialcreate$cover;
    @Inject(method="<init>",at=@At("RETURN"))
    private void differentialcreate$create(VisualizationContext ctx,float partial,boolean moving,CallbackInfo ci) {
        // Carriage bogeys use the outer visualization context, not the body's
        // embedding, and receive no packed-light updates. Sample world light
        // in the shader just like the native frame and wheels.
        differentialcreate$cover=ctx.instancerProvider().instancer(InstanceTypes.TRANSFORMED,SpecialModels.smoothLit(SteamBogeyModels.ENGINE_COVER)).createInstance();
        differentialcreate$cover.setZeroTransform().setChanged();
    }
    @Inject(method="update",at=@At("RETURN"))
    private void differentialcreate$update(CompoundTag data,float angle,PoseStack pose,CallbackInfo ci) {
        if(!data.getBoolean(SteamBogey.ENGINE)) {
            differentialcreate$cover.setZeroTransform().setChanged();return;
        }
        differentialcreate$cover.setTransform(pose).translate(-.5f,SteamBogeyModels.COVER_Y,-.5f)
            .center().scale(SteamBogeyModels.COVER_SCALE,1,SteamBogeyModels.COVER_SCALE).uncenter().setChanged();
    }
    @Inject(method="hide",at=@At("RETURN"))
    private void differentialcreate$hide(CallbackInfo ci) {differentialcreate$cover.setZeroTransform().setChanged();}
    @Inject(method="updateLight",at=@At("RETURN"))
    private void differentialcreate$light(int light,CallbackInfo ci) {differentialcreate$cover.light(light).setChanged();}
    @Inject(method="collectCrumblingInstances",at=@At("RETURN"))
    private void differentialcreate$crumble(Consumer<Instance> consumer,CallbackInfo ci) {consumer.accept(differentialcreate$cover);}
    @Inject(method="delete",at=@At("RETURN"))
    private void differentialcreate$delete(CallbackInfo ci) {differentialcreate$cover.delete();}
}
