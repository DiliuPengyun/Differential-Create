package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.trains.TrainPhysics;
import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Navigation.class)
public abstract class TrainNavigationMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/trains/entity/Train;leaveStation()V"))
    private void differentialcreate$waitForActualDeparture(Train train) {
        // A navigation command cannot depart a powerless, stationary train.
        // Carriage travel clears the station only after actual rail movement.
    }
    /** Keep the native path, reservation and chained-signal scout; replace its
     * velocity assignments and configured curve/top-speed controller. */
    @Inject(method = "tick", at = @At(value = "FIELD", target = "Lcom/simibubi/create/content/trains/entity/Train;currentlyBackwards:Z",
        opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER), cancellable = true)
    private void differentialcreate$physicalControl(Level level, CallbackInfo ci) {
        TrainPhysics.navigate(((Navigation) (Object) this).train); ci.cancel();
    }
}
