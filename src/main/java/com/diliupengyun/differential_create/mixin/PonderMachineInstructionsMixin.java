package com.diliupengyun.differential_create.mixin;

import java.util.function.Consumer;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Configure interactions in the original script; actual transfers decide the hand's held item. */
@Mixin(targets = "com.simibubi.create.foundation.ponder.CreateSceneBuilder$WorldInstructions")
public abstract class PonderMachineInstructionsMixin {
    @Shadow @Final private CreateSceneBuilder this$0;
    @Inject(method = "rotateBearing", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$bearingInput(BlockPos pos, float degrees, int duration, CallbackInfo ci) {
        if (duration <= 0) {
            var placement = com.simibubi.create.foundation.ponder.instruction.AnimateBlockEntityInstruction.bearing(pos, degrees, 0);
            this$0.addInstruction(scene -> {
                if (scene.getWorld().getBlockEntity(pos) instanceof com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity)
                    com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(scene.getWorld()).clockwork().stop(scene.getWorld(), pos);
                else placement.tick(scene);
            });
            ci.cancel();
            return;
        }
        var selection = net.createmod.ponder.foundation.SelectionImpl.of(new net.minecraft.world.level.levelgen.structure.BoundingBox(pos));
        this$0.addInstruction(new net.createmod.ponder.foundation.instruction.TickingInstruction(false, duration) {
            private boolean suppliesInput, stabilized;
            @Override public void reset(net.createmod.ponder.foundation.PonderScene scene) { super.reset(scene); suppliesInput = stabilized = false; }
            @Override public void tick(net.createmod.ponder.foundation.PonderScene scene) {
                boolean first = remainingTicks == totalTicks;
                super.tick(scene);
                var physics = com.diliupengyun.differential_create.client.ponder.PonderKinetics.of(scene.getWorld());
                var simulation = com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(scene.getWorld());
                if (first) stabilized = simulation.stabilizeBearing(scene.getWorld(), pos);
                if (first && !stabilized) suppliesInput = simulation.motionInput(scene.getWorld(), pos);
                if (first && suppliesInput) physics.command(scene.getWorld(), selection, rpm -> degrees / (.3f * duration));
            }
        });
        ci.cancel();
    }
    @Inject(method = {"setCraftingResult", "changeBeltItemTo"}, at = @At("HEAD"), cancellable = true)
    private void differentialcreate$recipeResults(CallbackInfo ci) { ci.cancel(); }
    @Inject(method = "animateBogey", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$wheels(BlockPos pos, float distance, int duration, CallbackInfo ci) {
        var animation = com.simibubi.create.foundation.ponder.instruction.AnimateBlockEntityInstruction.bogey(pos, distance, duration + 1);
        this$0.addInstruction(new net.createmod.ponder.foundation.instruction.TickingInstruction(false, duration + 1) {
            @Override public void tick(net.createmod.ponder.foundation.PonderScene scene) {
                super.tick(scene);
                if (!com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(scene.getWorld()).trainBogey(pos)) animation.tick(scene);
            }
            @Override public void reset(net.createmod.ponder.foundation.PonderScene scene) { super.reset(scene); animation.reset(scene); }
        }); ci.cancel();
    }
    @Inject(method = "moveDeployer", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$hand(BlockPos pos, float distance, int duration, CallbackInfo ci) {
        this$0.addInstruction(new com.diliupengyun.differential_create.client.ponder.PonderShaftAnimation(pos, distance, duration)); ci.cancel();
    }
    @Inject(method = "movePulley", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$rope(BlockPos pos, float distance, int duration, CallbackInfo ci) {
        this$0.addInstruction(scene -> {
            if (scene.getWorld().getBlockEntity(pos) instanceof com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity pulley)
                com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(scene.getWorld()).pulley(pulley).command(distance, duration <= 0);
        });
        ci.cancel();
    }
    @Inject(method = "instructArm", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$armInventory(BlockPos pos, ArmBlockEntity.Phase phase, ItemStack held,
        int target, CallbackInfo ci) {
        // Native instructions illustrate a pick/deposit; the real arm discovers its configured endpoints.
        this$0.addInstruction(scene -> {
            if (scene.getWorld().getBlockEntity(pos) instanceof ArmBlockEntity arm) arm.lazyTick();
        });
        ci.cancel();
    }
}
