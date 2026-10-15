package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.trains.TrainPhysics;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import java.util.Map;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Train.class)
public abstract class TrainPhysicsMixin implements TrainPhysics.Holder {
    @Unique private final TrainPhysics.State differentialcreate$physics = new TrainPhysics.State();
    @Override public TrainPhysics.State differentialcreate$physics() { return differentialcreate$physics; }
    @Shadow private void collideWithOtherTrains(Level level, Carriage carriage) { throw new AssertionError(); }
    @Inject(method = "tick", at = @At("HEAD"))
    private void differentialcreate$begin(Level level, CallbackInfo ci) {
        differentialcreate$physics.level = level;
        if (((Train) (Object) this).graph == null) TrainPhysics.advance((Train) (Object) this);
    }
    @Inject(method = "tickPassiveSlowdown", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$advance(CallbackInfo ci) { TrainPhysics.advance((Train) (Object) this); ci.cancel(); }
    @ModifyVariable(method = "tick", at = @At(value = "STORE", ordinal = 0), name = "distance")
    private double differentialcreate$distance(double speed) { return differentialcreate$physics.distance; }
    @Inject(method = "frontSignalListener", at = @At("RETURN"), cancellable = true)
    private void differentialcreate$finiteArrival(CallbackInfoReturnable<TravellingPoint.IEdgePointListener> cir) {
        var train = (Train) (Object) this; var original = cir.getReturnValue();
        cir.setReturnValue((distance, event) -> {
            var point = event.getFirst();
            boolean station = point instanceof GlobalStation s && train.navigation.destination == s
                && s.canApproachFrom(event.getSecond().getSecond());
            boolean signal = point instanceof SignalBoundary s && train.navigation.waitingForSignal != null
                && s.id.equals(train.navigation.waitingForSignal.getFirst());
            // Parking tolerance is 0.15 m/s. A missed target at higher speed
            // cannot remove momentum: occupy a crossed signal and keep braking.
            if ((station || signal) && Math.abs(train.speed * 20) > .15) {
                if (signal) { train.navigation.waitingForSignal = null; original.test(distance, event); }
                TrainPhysics.overrun(train); return false;
            }
            return original.test(distance, event);
        });
    }
    @Inject(method = "tickDerailedSlowdown", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$derailedFriction(CallbackInfo ci) { ci.cancel(); }
    @Inject(method = "burnFuel", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$realBurners(CallbackInfo ci) { ((Train) (Object) this).fuelTicks = 0; ci.cancel(); }
    @Inject(method = "disassemble", at = @At("HEAD"))
    private void differentialcreate$returnContainers(Direction direction, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        TrainPhysics.prepareDisassembly((Train) (Object) this);
    }
    @Inject(method = "approachTargetSpeed", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$finiteForce(float modifier, CallbackInfo ci) {
        var self = (Train) (Object) this;
        TrainPhysics.manual(self, (int) Math.signum(self.targetSpeed), modifier > 1); ci.cancel();
    }
    @Inject(method = "maxSpeed", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$estimate(CallbackInfoReturnable<Float> cir) { cir.setReturnValue((float) TrainPhysics.nominalSpeed((Train) (Object) this)); }
    @Inject(method = "maxTurnSpeed", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$turnEstimate(CallbackInfoReturnable<Float> cir) { cir.setReturnValue((float) TrainPhysics.nominalSpeed((Train) (Object) this)); }
    @Inject(method = "acceleration", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$braking(CallbackInfoReturnable<Float> cir) { cir.setReturnValue((float) TrainPhysics.brakeAcceleration((Train) (Object) this)); }
    @Redirect(method = "tick", at = @At(value = "FIELD", target = "Lcom/simibubi/create/content/trains/entity/Carriage;stalled:Z", opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private boolean differentialcreate$noRestoredMomentum(Carriage carriage) { return false; }
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/trains/entity/Carriage;travel(Lnet/minecraft/world/level/Level;Lcom/simibubi/create/content/trains/graph/TrackGraph;DLcom/simibubi/create/content/trains/entity/TravellingPoint;Lcom/simibubi/create/content/trains/entity/TravellingPoint;I)D"))
    private double differentialcreate$sweptTravel(Carriage carriage, Level level, TrackGraph graph, double distance,
        TravellingPoint forward, TravellingPoint backward, int type) {
        double travelled = 0, remaining = distance;
        do {
            double segment = Math.clamp(remaining, -1, 1);
            var departedStation = ((Train) (Object) this).getCurrentStation();
            double actual = carriage.travel(level, graph, segment, forward, backward, type);
            if (Double.isFinite(actual) && Math.abs(actual) > 1e-6 && departedStation != null
                && ((Train) (Object) this).getCurrentStation() == departedStation)
                ((Train) (Object) this).leaveStation();
            travelled += actual; remaining -= segment;
            if (carriage == ((Train) (Object) this).carriages.get(((Train) (Object) this).speed < 0 ? ((Train) (Object) this).carriages.size() - 1 : 0))
                collideWithOtherTrains(level, carriage);
            if (carriage.blocked || ((Train) (Object) this).graph == null || Math.abs(actual - segment) > 1e-6) break;
        } while (Math.abs(remaining) > 1e-9);
        return travelled;
    }
    @Inject(method = "write", at = @At("RETURN"))
    private void differentialcreate$write(DimensionPalette dimensions, HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) { cir.getReturnValue().put("DCTrain", differentialcreate$physics.write()); }
    @Inject(method = "read", at = @At("RETURN"))
    private static void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, Map<UUID, TrackGraph> graphs,
        DimensionPalette dimensions, CallbackInfoReturnable<Train> cir) {
        if (tag.contains("DCTrain")) {
            var saved = tag.getCompound("DCTrain"); var train = cir.getReturnValue();
            TrainPhysics.state(train).read(saved);
            if (saved.getBoolean("StationAssist")) train.navigation.cancelNavigation();
        }
        cir.getReturnValue().speedBeforeStall = null;
    }
}
