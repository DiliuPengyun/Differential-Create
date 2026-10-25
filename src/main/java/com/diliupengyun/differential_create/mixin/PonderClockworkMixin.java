package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClockworkBearingBlockEntity.class)
public abstract class PonderClockworkMixin {
    @WrapOperation(method = {"getHourTarget", "getMinuteTarget"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getDayTime()J"))
    private long differentialcreate$demonstrationTime(Level world, Operation<Long> original) {
        var self = (ClockworkBearingBlockEntity) (Object) this;
        Long time = world instanceof PonderLevel preview ? PonderSimulation.of(preview).clockwork().time(self) : null;
        return time == null ? original.call(world) : time;
    }
    @WrapOperation(method = {"getHourArmSpeed", "getMinuteArmSpeed"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/contraptions/bearing/ClockworkBearingBlockEntity;getAngularSpeed()F"))
    private float differentialcreate$paidClockTravel(ClockworkBearingBlockEntity self, Operation<Float> original) {
        return self.getLevel() instanceof PonderLevel preview ? (float) (-.3 * Math.abs(PonderKinetics.of(preview).outputSpeed(self.getBlockPos()))) : original.call(self);
    }
}
