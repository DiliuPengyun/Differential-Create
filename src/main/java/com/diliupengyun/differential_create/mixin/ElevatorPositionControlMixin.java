package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ActorFeed;
import com.diliupengyun.differential_create.kinetics.ContraptionMotion;
import com.diliupengyun.differential_create.kinetics.PositionServo;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A floor is a servo target, not a wall or a position teleport. */
@Mixin(ElevatorPulleyBlockEntity.class)
public abstract class ElevatorPositionControlMixin {
    @Shadow private boolean arrived;
    @Shadow private int clientOffsetTarget;
    @Shadow private float prevSpeed;
    @Inject(method = "getMovementSpeed", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$physicalTravel(CallbackInfoReturnable<Float> cir) {
        var self = (ElevatorPulleyBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide || !self.running || !ActorFeed.enabled(self)) return;
        int target = ((ElevatorPulleyAccessor) self).differentialcreate$targetOffset();
        if (target != clientOffsetTarget) { clientOffsetTarget = target; self.sendData(); }
        Vec3 motion = ContraptionMotion.linear(self.movedContraption);
        float travel = motion == null ? 0 : (float) -motion.y;
        arrived = Math.abs(target - self.offset - travel) < PositionServo.offsetTolerance(self.offset) && Math.abs(ActorFeed.of(self).speed) <= PositionServo.SPEED_EPSILON;
        prevSpeed = travel;
        cir.setReturnValue(travel);
    }
    @Redirect(method = "tick", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;setPos(Lnet/minecraft/world/phys/Vec3;)V"))
    private void differentialcreate$noArrivalTeleport(AbstractContraptionEntity entity, Vec3 target) {
        var self = (ElevatorPulleyBlockEntity) (Object) this;
        if (self.getLevel().isClientSide) {
            if (com.diliupengyun.differential_create.client.ClientNetworkSpeeds.linearMotionOf(self, entity.getId()) == null) entity.setPos(target);
        } else if (!ActorFeed.enabled(self)) entity.setPos(target);
    }
}
