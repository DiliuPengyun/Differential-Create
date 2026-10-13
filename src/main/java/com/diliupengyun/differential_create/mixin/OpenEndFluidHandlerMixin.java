package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.FluidBudget;
import com.diliupengyun.differential_create.kinetics.OpenPipeDischarge;
import com.simibubi.create.api.effect.OpenPipeEffectHandler;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.foundation.fluid.FluidHelper;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.simibubi.create.content.fluids.OpenEndedPipe$OpenEndFluidHandler")
public abstract class OpenEndFluidHandlerMixin extends FluidTank implements OpenPipeDischarge.Placement {
    @Shadow(remap = false) @Final private OpenEndedPipe this$0;
    // A placement counter for liquid already outside the pipe, not drainable
    // stock. Only a new real discharge can advance it or place a source block.
    @Unique private FluidStack differentialcreate$discharged = FluidStack.EMPTY;
    @Unique private double differentialcreate$dischargedAmount;

    protected OpenEndFluidHandlerMixin() {
        super(1000);
    }

    @Override
    public void differentialcreate$clearDischarge() {
        differentialcreate$discharged = FluidStack.EMPTY;
        differentialcreate$dischargedAmount = 0;
    }

    @Override
    public FluidStack differentialcreate$worldFluid() {
        var world = this$0.getWorld();
        return world == null || !world.isLoaded(this$0.getOutputPos()) ? FluidStack.EMPTY
            : ((OpenEndedPipeAccessor) this$0).differentialcreate$removeFluidFromSpace(true);
    }

    @Override
    public FluidStack differentialcreate$takeWorld(FluidStack filter, int amount, double receivingSpace) {
        if (amount <= 0 || !getFluid().isEmpty()) return FluidStack.EMPTY;
        var offered = differentialcreate$worldFluid();
        if (offered.isEmpty() || offered.getAmount() > getCapacity()
            || filter != null && !FluidStack.isSameFluidSameComponents(offered, filter)
            || !(receivingSpace >= offered.getAmount())) return FluidStack.EMPTY;
        var boundary = (OpenEndedPipeAccessor) this$0;
        var extracted = boundary.differentialcreate$removeFluidFromSpace(false);
        if (extracted.isEmpty()) return FluidStack.EMPTY;
        int taken = Math.min(amount, extracted.getAmount());
        // This is a reservation within the receiving network's real capacity,
        // not an additional tank and not a source of pressure.
        int remainder = extracted.getAmount() - taken;
        if (remainder > 0) super.fill(extracted.copyWithAmount(remainder), FluidAction.EXECUTE);
        boundary.differentialcreate$setPulling(true);
        differentialcreate$clearDischarge();
        return extracted.copyWithAmount(taken);
    }

    @Override
    public FluidTank readFromNBT(HolderLookup.Provider registries, CompoundTag tag) {
        super.readFromNBT(registries, tag);
        var discharged = FluidStack.parseOptional(registries, tag.getCompound("DifferentialDischarge"));
        double amount = tag.contains("DifferentialDischargeAmount")
            ? tag.getDouble("DifferentialDischargeAmount") : discharged.getAmount();
        differentialcreate$clearDischarge();
        if (!discharged.isEmpty() && FluidHelper.hasBlockState(discharged.getFluid()) && amount > 0 && Double.isFinite(amount)) {
            differentialcreate$discharged = discharged.copyWithAmount(1);
            differentialcreate$dischargedAmount = Math.min(Math.nextDown((double) getCapacity()), amount);
        }
        return this;
    }

    @Override
    public CompoundTag writeToNBT(HolderLookup.Provider registries, CompoundTag tag) {
        super.writeToNBT(registries, tag);
        if (differentialcreate$discharged.isEmpty()) {
            tag.remove("DifferentialDischarge");
            tag.remove("DifferentialDischargeAmount");
        } else {
            tag.put("DifferentialDischarge", differentialcreate$discharged.save(registries));
            tag.putDouble("DifferentialDischargeAmount", differentialcreate$dischargedAmount);
        }
        tag.remove("DifferentialIntakeHeight");
        return tag;
    }

    @Override
    public double differentialcreate$discharge(FluidStack identity, double amount, FluidAction action) {
        var world = this$0.getWorld();
        if (world == null || !world.isLoaded(this$0.getOutputPos()) || identity.isEmpty()
            || !(amount > 0) || !Double.isFinite(amount)) return 0;
        var boundary = (OpenEndedPipeAccessor) this$0;
        if (!boundary.differentialcreate$provideFluidToSpace(identity, true)
            || !getFluid().isEmpty() && !FluidStack.isSameFluidSameComponents(getFluid(), identity)) return 0;

        boolean hasBlockState = FluidHelper.hasBlockState(identity.getFluid());
        var outside = world.getFluidState(this$0.getOutputPos());
        boolean collision = hasBlockState && !outside.isEmpty()
            && FluidHelper.convertToStill(outside.getType()) != identity.getFluid();
        // Waterlogging retains its native integer staging. Ordinary air spills
        // spend only the actual incoming parcel, not the rest of an intake.
        if (!collision && hasBlockState
            && world.getBlockState(this$0.getOutputPos()).hasProperty(BlockStateProperties.WATERLOGGED)) return -1;

        var effect = OpenPipeEffectHandler.REGISTRY.get(identity.getFluid());
        double accepted = Math.min(amount, effect != null && !hasBlockState ? 1 : getCapacity());
        if (action.simulate()) return accepted;
        double dischargedAmount = accepted + (collision || !hasBlockState ? getFluidAmount() : 0);
        var discharged = identity.copyWithAmount((int) Math.max(1, Math.ceil(dischargedAmount)));
        double placed = dischargedAmount;
        if (FluidStack.isSameFluidSameComponents(differentialcreate$discharged, identity))
            placed += differentialcreate$dischargedAmount;
        boolean bucket = FluidBudget.receiptSpace(getCapacity(), placed) == 0;
        if ((collision || !hasBlockState || bucket)
            && !boundary.differentialcreate$provideFluidToSpace(
                !collision && hasBlockState ? identity.copyWithAmount(getCapacity()) : discharged, false)) return 0;

        if (collision || !hasBlockState) setFluid(FluidStack.EMPTY);
        differentialcreate$clearDischarge();
        // The whole accepted parcel has really left the pipe. Placement spends
        // only one bucket of its credit; an overshoot remains outside, including
        // when an integer capability cannot report a fractional receipt.
        double remainder = bucket ? FluidBudget.receiptSpace(placed, getCapacity()) : placed;
        if (hasBlockState && !collision && remainder > 0) {
            differentialcreate$discharged = identity.copyWithAmount(1);
            differentialcreate$dischargedAmount = remainder;
        }
        boundary.differentialcreate$setPulling(false);
        if (effect != null) effect.apply(world, this$0.getAOE(), discharged.copy());
        OpenPipeDischarge.emit(this$0, discharged);
        return accepted;
    }

    @Inject(method = "fill", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$fillOwnedStock(FluidStack resource, FluidAction action, CallbackInfoReturnable<Integer> cir) {
        double discharged = differentialcreate$discharge(resource, resource.getAmount(), action);
        if (discharged >= 0) {
            cir.setReturnValue((int) discharged);
            return;
        }
        int accepted = super.fill(resource, action);
        cir.setReturnValue(accepted);
        if (action.simulate()) return;
        var boundary = (OpenEndedPipeAccessor) this$0;
        if (accepted > 0) {
            boundary.differentialcreate$setPulling(false);
            differentialcreate$clearDischarge();
        }
        if (getFluidAmount() != getCapacity()) return;
        var spent = getFluid().copy();
        if (boundary.differentialcreate$provideFluidToSpace(spent, false)) {
            setFluid(FluidStack.EMPTY);
            var effect = OpenPipeEffectHandler.REGISTRY.get(spent.getFluid());
            if (effect != null) effect.apply(this$0.getWorld(), this$0.getAOE(), spent.copy());
            OpenPipeDischarge.emit(this$0, spent);
        }
    }

    @Inject(method = "drainInner", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$drainOwnedIntake(int amount, FluidStack filter, FluidAction action,
                                                    CallbackInfoReturnable<FluidStack> cir) {
        cir.setReturnValue(FluidStack.EMPTY);
        var world = this$0.getWorld();
        if (world == null || !world.isLoaded(this$0.getOutputPos()) || amount <= 0) return;
        amount = Math.min(amount, getCapacity());
        var boundary = (OpenEndedPipeAccessor) this$0;
        if (!getFluid().isEmpty()) {
            if (filter != null && !FluidStack.isSameFluidSameComponents(getFluid(), filter)) return;
            // Calling the parent's integer overload avoids its virtual dispatch
            // back through this handler's filtered drain overload.
            var drained = super.drain(amount, action);
            if (action.execute() && !drained.isEmpty()) {
                boundary.differentialcreate$setPulling(true);
                // This packet was already taken from the world. Drawing its
                // saved remainder cannot erase fluid previously spilled outside.
            }
            cir.setReturnValue(drained);
            return;
        }
        var offered = differentialcreate$worldFluid();
        if (offered.isEmpty() || filter != null && !FluidStack.isSameFluidSameComponents(offered, filter)) return;
        if (action.simulate()) {
            cir.setReturnValue(offered.copyWithAmount(Math.min(amount, offered.getAmount())));
            return;
        }
        // A generic capability request cannot promise space for more than it
        // requests. Small execute calls must leave an indivisible source alone.
        if (amount >= offered.getAmount())
            cir.setReturnValue(differentialcreate$takeWorld(filter, amount, amount));
    }
}
