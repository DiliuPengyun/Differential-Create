package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.IPipeDynamics;
import com.diliupengyun.differential_create.kinetics.PipeHydraulics;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FluidTransportBehaviour.class)
public abstract class FluidTransportBehaviourMixin implements IPipeDynamics {
    @Unique private PipeHydraulics differentialcreate$state = new PipeHydraulics();
    @Override public PipeHydraulics differentialcreate$fluidState() { return differentialcreate$state; }
    @Override public void differentialcreate$adoptFluidState(PipeHydraulics state) {
        differentialcreate$state = state;
        var self = (FluidTransportBehaviour) (Object) this;
        if (self.getWorld() != null && !self.getWorld().isClientSide) FluidKinetics.invalidate(self.getWorld());
    }

    @Inject(method = "cacheFlows", at = @At("HEAD"))
    private static void differentialcreate$cacheState(LevelAccessor level, BlockPos pos, CallbackInfo ci) {
        FluidKinetics.cacheState(level, pos);
    }
    @Inject(method = "loadFlows", at = @At("RETURN"))
    private static void differentialcreate$restoreState(LevelAccessor level, BlockPos pos, CallbackInfo ci) {
        FluidKinetics.restoreState(level, pos);
    }

    @Inject(method = "initialize", at = @At("RETURN"))
    private void differentialcreate$register(CallbackInfo ci) {
        FluidKinetics.register((FluidTransportBehaviour) (Object) this);
    }
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$physicalTick(CallbackInfo ci) {
        var self = (FluidTransportBehaviour) (Object) this;
        if (self.getWorld().isClientSide) { differentialcreate$state.tickVisual(); return; }
        if (self.blockEntity.isVirtual()) return;
        FluidKinetics.register(self);
        ci.cancel();
    }
    @Inject(method = "read", at = @At("RETURN"))
    private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
        differentialcreate$state.read(tag, registries);
        var self = (FluidTransportBehaviour) (Object) this;
        if (self.getWorld() != null && !self.getWorld().isClientSide) FluidKinetics.invalidate(self.getWorld());
    }
    @Inject(method = "write", at = @At("RETURN"))
    private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
        differentialcreate$state.write(tag, registries);
    }
}
