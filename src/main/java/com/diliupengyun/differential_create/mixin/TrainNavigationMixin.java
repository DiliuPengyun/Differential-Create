package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.trains.TrainPhysics;
import com.diliupengyun.differential_create.trains.TrainMechanics;
import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Navigation.class)
public abstract class TrainNavigationMixin {
    @Redirect(method = "findNearestApproachable", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/trains/entity/Navigation;search(DZLjava/util/ArrayList;Lcom/simibubi/create/content/trains/entity/Navigation$StationTest;)V"))
    private void differentialcreate$stationRange(Navigation navigation, double range, boolean forward,
        java.util.ArrayList<GlobalStation> destinations, Navigation.StationTest predicate) {
        // Native search visits every station on its starting edge before checking
        // the range at the next node. Apply that range to the station itself too.
        navigation.search(range, forward, destinations, (distance, cost, reachedVia, entry, station) -> {
            var edge = entry.getSecond();
            double stationDistance = distance - (edge.getLength() - station.getLocationOn(edge));
            return stationDistance <= range && predicate.test(distance, cost, reachedVia, entry, station);
        });
    }
    @ModifyVariable(method = "findNearestApproachable", at = @At("STORE"), name = "minDistance")
    private double differentialcreate$approachMargin(double original) {
        var train = ((Navigation) (Object) this).train;
        double downhill = -TrainPhysics.state(train).grade * Math.signum(train.speed);
        return TrainMechanics.stoppingDistance(Math.abs(train.speed) * 20, downhill) + Math.abs(train.speed) * 2 + .1;
    }
    @Inject(method = "tick", at = @At("HEAD"))
    private void differentialcreate$assistSignals(Level level, CallbackInfo ci) {
        var train = ((Navigation) (Object) this).train;
        if (TrainPhysics.state(train).stationAssist) train.manualTick = false;
    }
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
