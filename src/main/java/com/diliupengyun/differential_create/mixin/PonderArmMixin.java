package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ArmBlockEntity.class)
public abstract class PonderArmMixin implements com.diliupengyun.differential_create.client.ponder.PonderArmConfiguration {
    @org.spongepowered.asm.mixin.Shadow java.util.List<com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint> inputs;
    @org.spongepowered.asm.mixin.Shadow java.util.List<com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint> outputs;
    @org.spongepowered.asm.mixin.Shadow boolean updateInteractionPoints;
    @Override public void differentialcreate$configure(java.util.List<BlockPos> from, java.util.List<BlockPos> to) {
        var self = (ArmBlockEntity) (Object) this;
        if (!PhysicsPreview.enabled(self.getLevel())) return;
        inputs.clear(); outputs.clear(); updateInteractionPoints = false;
        for (var positions : java.util.List.of(from, to)) for (var pos : positions) {
            var point = com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.create(self.getLevel(), pos, self.getLevel().getBlockState(pos));
            if (point == null) continue;
            var mode = positions == from ? com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode.TAKE
                : com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode.DEPOSIT;
            if (point.getMode() != mode) point.cycleMode();
            (positions == from ? inputs : outputs).add(point);
        }
    }
    @WrapOperation(method = {"tick", "lazyTick"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;isClientSide:Z", opcode = Opcodes.GETFIELD))
    private boolean differentialcreate$physicalTransfers(Level world, Operation<Boolean> original) {
        return original.call(world) && !PhysicsPreview.enabled(world);
    }
    @Inject(method = "isAreaActuallyLoaded", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$previewArea(BlockPos pos, int range, CallbackInfoReturnable<Boolean> cir) {
        if (PhysicsPreview.enabled(((ArmBlockEntity) (Object) this).getLevel())) cir.setReturnValue(true);
    }
}
