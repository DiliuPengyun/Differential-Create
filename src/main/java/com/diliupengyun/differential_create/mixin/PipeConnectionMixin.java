package com.diliupengyun.differential_create.mixin;

import java.util.Optional;
import com.diliupengyun.differential_create.kinetics.IPipePressure;
import com.simibubi.create.content.fluids.FlowSource;
import com.simibubi.create.content.fluids.PipeConnection;
import net.createmod.catnip.data.Couple;
import net.neoforged.neoforge.fluids.FluidStack;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PipeConnection.class)
public abstract class PipeConnectionMixin implements IPipePressure {
    @Shadow Couple<Float> pressure;
    @Shadow Optional<FlowSource> source;
    @Shadow Optional<FlowSource> previousSource;
    @Shadow @Final Direction side;
    @Shadow Optional<PipeConnection.Flow> flow;
    @Unique private OpenEndedPipe differentialcreate$ownedIntake;

    @Unique private boolean differentialcreate$hasOwnedData(OpenEndedPipe end, Level world) {
        if (end == null) return false;
        if (end.provideHandler().getCapability().getFluidInTank(0).getAmount() > 0) return true;
        if (world == null) return false;
        if (end.serializeNBT(world.registryAccess()).getDouble("DifferentialDischargeAmount") > 0) return true;
        var owner = end.getOutputPos().relative(side.getOpposite());
        com.simibubi.create.content.fluids.FluidTransportBehaviour pipe;
        if (world instanceof net.minecraft.server.level.ServerLevel server) {
            var chunk = server.getChunkSource().getChunkNow(owner.getX() >> 4, owner.getZ() >> 4);
            var be = chunk == null ? null : chunk.getBlockEntity(owner);
            pipe = be instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity smart
                ? smart.getBehaviour(com.simibubi.create.content.fluids.FluidTransportBehaviour.TYPE) : null;
        } else pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(world, owner);
        if (pipe == null) return false;
        var buffer = com.diliupengyun.differential_create.kinetics.FluidKinetics.state(pipe).buffers.get(side);
        return buffer != null && !buffer.delivery && buffer.amount > 0;
    }
    @Inject(method = "wipePressure", at = @At("HEAD"))
    private void differentialcreate$retainIntakeAcrossSourceChanges(CallbackInfo ci) {
        differentialcreate$rememberIntake(null);
    }
    @Inject(method = "determineSource", at = @At("HEAD"))
    private void differentialcreate$retainIntakeBeforeRetyping(Level world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        differentialcreate$rememberIntake(world);
        if (differentialcreate$hasOwnedData(differentialcreate$ownedIntake, world)
            && com.simibubi.create.content.fluids.FluidPropagator.isOpenEnd(world, pos, side))
            previousSource = Optional.of(differentialcreate$ownedIntake);
    }
    @Unique private void differentialcreate$rememberIntake(Level world) {
        // Several neighbour callbacks may replace previousSource with a blocked
        // or inventory source before the next common physics tick. Already
        // extracted world stock belongs to this surviving port throughout.
        if (world == null && differentialcreate$ownedIntake != null) world = differentialcreate$ownedIntake.getWorld();
        if (differentialcreate$hasOwnedData(differentialcreate$ownedIntake, world)) return;
        var old = source.orElse(previousSource.orElse(null));
        if (world == null && old instanceof OpenEndedPipe end) world = end.getWorld();
        differentialcreate$ownedIntake = old instanceof OpenEndedPipe end && differentialcreate$hasOwnedData(end, world) ? end : null;
    }

    @Override public FlowSource differentialcreate$source() { return source.orElse(null); }
    @Override public FlowSource differentialcreate$retainedSource() {
        // Keep the identity through its final owned sub-mB buffer too; the
        // graph separately decides whether any retained intake is still owed.
        return differentialcreate$ownedIntake != null ? differentialcreate$ownedIntake : previousSource.orElse(null);
    }
    @Inject(method = "serializeNBT", at = @At("RETURN"))
    private void differentialcreate$saveRetainedInventory(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
        // Neighbour updates can clear the active source between the last fluid
        // update and a save/unload. Create retains that handler in previousSource
        // and later reuses it, but normally serializes only the active source.
        // Its real intake stock and already-outside discharge credit still exist.
        var old = differentialcreate$ownedIntake != null ? differentialcreate$ownedIntake : previousSource.orElse(null);
        if (old instanceof OpenEndedPipe retained && (!(source.orElse(null) instanceof OpenEndedPipe)
            || retained != source.orElse(null) && (retained.provideHandler().getCapability().getFluidInTank(0).getAmount() > 0
                || retained.serializeNBT(registries).getDouble("DifferentialDischargeAmount") > 0)))
            tag.getCompound(side.getName()).put("OpenEnd", retained.serializeNBT(registries));
    }
    @Override public void differentialcreate$showFlow(FluidStack identity, double outward) {
        boolean inbound = outward < 0;
        float rate = (float) Math.abs(outward * 2);
        pressure.setFirst(inbound ? rate : 0f);
        pressure.setSecond(inbound ? 0f : rate);
        if (identity.isEmpty()) {
            flow = Optional.empty();
            return;
        }
        PipeConnection self = (PipeConnection) (Object) this;
        PipeConnection.Flow shown = flow.orElse(null);
        if (shown == null || shown.inbound != inbound || !FluidStack.isSameFluidSameComponents(shown.fluid, identity))
            shown = self.new Flow(inbound, identity.copyWithAmount(1));
        shown.complete = true;
        shown.progress.setValue(1);
        flow = Optional.of(shown);
    }
    @Inject(method = "tickFlowProgress", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$stationaryPresentation(Level level, BlockPos pos, CallbackInfo ci) {
        if (pressure.getFirst() == 0 && pressure.getSecond() == 0) {
            var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(level, pos);
            if (pipe != null && !pipe.blockEntity.isVirtual()) ci.cancel();
        }
    }

    @Inject(method = {"spawnParticles", "spawnSplashOnRim"}, at = @At("HEAD"), cancellable = true)
    private void differentialcreate$confirmedOpenEndParticles(Level level, BlockPos pos, FluidStack fluid, CallbackInfo ci) {
        if (!(source.orElse(null) instanceof com.simibubi.create.content.fluids.OpenEndedPipe)) return;
        var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(level, pos);
        // The server sends the actual world discharge once. A nonzero internal
        // transfer, suction, reload or old Flow object is not an outward spill.
        if (pipe != null && !pipe.blockEntity.isVirtual()) ci.cancel();
    }
}
