package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.IPipeDynamics;
import com.diliupengyun.differential_create.kinetics.IPipePressure;
import com.diliupengyun.differential_create.kinetics.PipeHydraulics;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.FlowSource;
import net.minecraft.core.Direction;
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
    @Inject(method = "wipePressure", at = @At("HEAD"))
    private void differentialcreate$archiveRemovedPorts(CallbackInfo ci) {
        var self = (FluidTransportBehaviour)(Object)this;
        if (self.getWorld().isClientSide || self.interfaces == null) return;
        for (var entry : self.interfaces.entrySet()) if (!self.canHaveFlowToward(self.blockEntity.getBlockState(), entry.getKey())) {
            var buffer = differentialcreate$state.buffers.get(entry.getKey());
            if (buffer != null && buffer.amount > 0) differentialcreate$state.retainedPorts.put(entry.getKey(), entry.getValue());
            var c = (IPipePressure)entry.getValue();
            for (var source : new FlowSource[]{c.differentialcreate$source(), c.differentialcreate$retainedSource()})
                if (source instanceof OpenEndedPipe end) {
                    var tag = end.serializeNBT(self.getWorld().registryAccess());
                    if (end.provideHandler().getCapability().getFluidInTank(0).getAmount() > 0 || tag.getDouble("DifferentialDischargeAmount") > 0)
                        differentialcreate$state.retainedPorts.put(entry.getKey(), entry.getValue());
                }
        }
    }
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$physicalTick(CallbackInfo ci) {
        var self = (FluidTransportBehaviour) (Object) this;
        if (com.diliupengyun.differential_create.kinetics.PhysicsPreview.enabled(self.getWorld())) {
            differentialcreate$state.tickVisual();
            FluidKinetics.register(self);
            ci.cancel(); return;
        }
        if (self.getWorld().isClientSide) { differentialcreate$state.tickVisual(); return; }
        if (self.blockEntity.isVirtual()) return;
        FluidKinetics.register(self);
        ci.cancel();
    }
    @Inject(method = "read", at = @At("RETURN"))
    private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
        differentialcreate$state.read(tag, registries);
        var self = (FluidTransportBehaviour) (Object) this;
        differentialcreate$state.retainedPorts.clear();
        var retained = tag.getCompound("DifferentialRetiredPorts");
        for (var side : Direction.values()) if (retained.contains(side.getName())) {
            var connection = new PipeConnection(side); connection.deserializeNBT(retained, registries, self.getPos(), packet);
            differentialcreate$state.retainedPorts.put(side, connection);
        }
        if (self.getWorld() != null && !self.getWorld().isClientSide) FluidKinetics.invalidate(self.getWorld());
    }
    @Inject(method = "write", at = @At("RETURN"))
    private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
        differentialcreate$state.write(tag, registries);
        var retained = new CompoundTag();
        for (var connection : differentialcreate$state.retainedPorts.values()) connection.serializeNBT(retained, registries, packet);
        if (!retained.isEmpty()) tag.put("DifferentialRetiredPorts", retained);
    }
}
