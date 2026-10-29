package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClockworkBearingBlockEntity.class)
public abstract class PonderClockworkMixin {
    @WrapOperation(method = {"getHourTarget", "getMinuteTarget"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getDayTime()J"))
    private long differentialcreate$demonstrationTime(Level world, Operation<Long> original) {
        var self = (ClockworkBearingBlockEntity) (Object) this;
        Long time = world instanceof PonderLevel preview ? PonderSimulation.of(preview).clockwork().time(self) : null;
        return time == null ? original.call(world) : time;
    }
    @Inject(method = {"getHourArmSpeed", "getMinuteArmSpeed"}, at = @At("HEAD"), cancellable = true)
    private void differentialcreate$physicalHands(CallbackInfoReturnable<Float> cir) {
        var self = (ClockworkBearingBlockEntity) (Object) this;
        if (self.getLevel() instanceof PonderLevel preview && PonderSimulation.of(preview).clockwork().topAngle(self, 1) != null)
            cir.setReturnValue(0f);
    }
    @Inject(method = "getInterpolatedAngle", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$topPose(float partialTicks, CallbackInfoReturnable<Float> cir) {
        var self = (ClockworkBearingBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof PonderLevel preview)) return;
        Double angle = PonderSimulation.of(preview).clockwork().topAngle(self, partialTicks + 1);
        if (angle != null) cir.setReturnValue(angle.floatValue());
    }
}
