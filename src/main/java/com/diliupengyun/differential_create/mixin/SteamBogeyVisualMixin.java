package com.diliupengyun.differential_create.mixin;

import java.util.function.Consumer;
import com.diliupengyun.differential_create.client.SteamBogeyModels;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.bogey.StandardBogeyVisual;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StandardBogeyVisual.class)
public abstract class SteamBogeyVisualMixin {
    @Unique private TransformedInstance differentialcreate$cylinders,differentialcreate$pistons;
    @Unique private float differentialcreate$height;
    @Inject(method="<init>",at=@At("RETURN"))
    private void differentialcreate$create(VisualizationContext ctx,float partial,boolean moving,CallbackInfo ci) {
        differentialcreate$height=(Object)this instanceof StandardBogeyVisual.Large?.25f:0;
        differentialcreate$cylinders=ctx.instancerProvider().instancer(InstanceTypes.TRANSFORMED,Models.partial(SteamBogeyModels.CYLINDERS)).createInstance();
        differentialcreate$pistons=ctx.instancerProvider().instancer(InstanceTypes.TRANSFORMED,Models.partial(SteamBogeyModels.PISTONS)).createInstance();
    }
    @Inject(method="update",at=@At("RETURN"))
    private void differentialcreate$update(CompoundTag data,float angle,PoseStack pose,CallbackInfo ci) {
        if(!data.getBoolean(SteamBogey.ENGINE)) {
            differentialcreate$cylinders.setZeroTransform().setChanged();differentialcreate$pistons.setZeroTransform().setChanged();return;
        }
        differentialcreate$cylinders.setTransform(pose).translate(-.5f,differentialcreate$height,-.5f).setChanged();
        differentialcreate$pistons.setTransform(pose).translate(-.5f,differentialcreate$height,-.5f+(float)SteamBogeyModels.offset(data)).setChanged();
    }
    @Inject(method="hide",at=@At("RETURN"))
    private void differentialcreate$hide(CallbackInfo ci) {differentialcreate$cylinders.setZeroTransform().setChanged();differentialcreate$pistons.setZeroTransform().setChanged();}
    @Inject(method="updateLight",at=@At("RETURN"))
    private void differentialcreate$light(int light,CallbackInfo ci) {differentialcreate$cylinders.light(light).setChanged();differentialcreate$pistons.light(light).setChanged();}
    @Inject(method="collectCrumblingInstances",at=@At("RETURN"))
    private void differentialcreate$crumble(Consumer<Instance> consumer,CallbackInfo ci) {consumer.accept(differentialcreate$cylinders);consumer.accept(differentialcreate$pistons);}
    @Inject(method="delete",at=@At("RETURN"))
    private void differentialcreate$delete(CallbackInfo ci) {differentialcreate$cylinders.delete();differentialcreate$pistons.delete();}
}
