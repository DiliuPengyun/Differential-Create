package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.trains.*;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Carriage.class)
public abstract class CarriagePhysicsMixin implements CarriageSteamState.Holder {
    @Unique private CarriageSteamState differentialcreate$steam;
    @Override public CarriageSteamState differentialcreate$steam() { return differentialcreate$steam; }
    @Override public void differentialcreate$steam(CarriageSteamState state) { differentialcreate$steam = state; }
    @Inject(method = "setContraption", at = @At("HEAD"))
    private void differentialcreate$assemble(Level level, CarriageContraption contraption, CallbackInfo ci) {
        var layout = ((TrainAssembly.LayoutHolder) contraption).differentialcreate$layout();
        differentialcreate$steam = new CarriageSteamState(layout);
        var carriage = (Carriage) (Object) this;
        if (carriage.leadingBogey().bogeyData.getBoolean(SteamBogey.ENGINE))
            carriage.leadingBogey().bogeyData.putLong(SteamBogey.MOTOR_POS, net.minecraft.core.BlockPos.ZERO.asLong());
        if (carriage.isOnTwoBogeys() && carriage.trailingBogey().bogeyData.getBoolean(SteamBogey.ENGINE))
            carriage.trailingBogey().bogeyData.putLong(SteamBogey.MOTOR_POS, contraption.getSecondBogeyPos().subtract(contraption.anchor).asLong());
        if (layout != null && !layout.levers.isEmpty() && carriage.train != null) {
            var state = TrainPhysics.state(carriage.train);
            if (!state.gearInitialized) { state.gear = layout.initialGear; state.gearInitialized = true; }
        }
        for (var bogey : carriage.bogeys) if (bogey != null)
            bogey.bogeyData.putBoolean("DCGeared", bogey.bogeyData.getBoolean(SteamBogey.ENGINE));
    }
    @Inject(method = "write", at = @At("RETURN"))
    private void differentialcreate$write(DimensionPalette dimensions, HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
        if (differentialcreate$steam != null) cir.getReturnValue().put("DCSteam", differentialcreate$steam.write(registries));
    }
    @Inject(method = "read", at = @At("RETURN"))
    private static void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, TrackGraph graph,
        DimensionPalette dimensions, CallbackInfoReturnable<Carriage> cir) {
        if (tag.contains("DCSteam")) ((CarriageSteamState.Holder) cir.getReturnValue()).differentialcreate$steam(CarriageSteamState.read(tag.getCompound("DCSteam"), registries));
        else ((CarriageSteamState.Holder) cir.getReturnValue()).differentialcreate$steam(new CarriageSteamState(TrainAssembly.legacy(tag.getCompound("Entity").getCompound("Contraption"))));
    }
}
