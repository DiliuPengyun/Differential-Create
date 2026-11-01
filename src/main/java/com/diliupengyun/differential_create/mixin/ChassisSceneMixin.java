package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderAwait;
import com.diliupengyun.differential_create.client.ponder.PonderChassisTurn;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.ChassisScenes;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChassisScenes.class)
public abstract class ChassisSceneMixin {
    @WrapOperation(method = {"linearGroup", "linearAttachement", "radial"}, at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;rotateBearing(Lnet/minecraft/core/BlockPos;FI)V"))
    private static void differentialcreate$finiteTurn(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        float degrees, int duration, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        builder.addInstruction(new PonderChassisTurn(pos, degrees, duration));
    }

    private static void differentialcreate$park(SceneBuilder builder, int reserve) {
        builder.idle(reserve);
        builder.addInstruction(new PonderAwait(scene -> PonderChassisTurn.parked(scene, new BlockPos(2, 1, 2)), 300));
    }

    @WrapOperation(method = {"linearGroup", "linearAttachement", "radial"}, at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;rotateSection(Lnet/createmod/ponder/api/element/ElementLink;DDDI)V"))
    private static void differentialcreate$shareBearing(CreateSceneBuilder.WorldInstructions world,
        ElementLink<WorldSectionElement> link, double x, double y, double z, int duration, Operation<Void> original,
        SceneBuilder builder, SceneBuildingUtil util) {
        if (duration == 0) {
            differentialcreate$park(builder, 40);
            original.call(world, link, x, y, z, duration);
            return;
        }
        // Tall and translated examples can be far from the template bearing.
        // Every section explicitly selected for this stroke shares its output.
        builder.addInstruction(scene -> {
            var section = scene.resolve(link);
            if (section != null && scene.getWorld().getBlockEntity(new BlockPos(2, 1, 2)) instanceof KineticBlockEntity be)
                PonderSimulation.of(scene.getWorld()).attach(section, be, true, 1, 1);
        });
    }

    @Inject(method = "linearGroup", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder;addKeyframe()V"))
    private static void differentialcreate$parkBeforeExclusions(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        differentialcreate$park(builder, 40);
    }

    @Inject(method = {"linearAttachement", "radial"}, at = @At(value = "INVOKE",
        target = "Lnet/createmod/ponder/api/scene/OverlayInstructions;showCenteredScrollInput(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;I)V"))
    private static void differentialcreate$parkBeforeRangeControls(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        differentialcreate$park(builder, 40);
    }

    @Inject(method = "radial", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;hideIndependentSection(Lnet/createmod/ponder/api/element/ElementLink;Lnet/minecraft/core/Direction;)V"))
    private static void differentialcreate$parkBeforeSlime(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        differentialcreate$park(builder, 40);
    }

    @Inject(method = "radial", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;destroyBlock(Lnet/minecraft/core/BlockPos;)V", ordinal = 0))
    private static void differentialcreate$parkBeforeBreaking(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        differentialcreate$park(builder, 40);
    }

    @Inject(method = "linearAttachement", at = @At(value = "INVOKE",
        target = "Lnet/createmod/ponder/api/scene/OverlayInstructions;showText(I)Lnet/createmod/ponder/api/element/TextElementBuilder;", ordinal = 5))
    private static void differentialcreate$parkBeforeGlue(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        differentialcreate$park(builder, 40);
    }

    @Inject(method = "linearGroup", at = @At("RETURN"))
    private static void differentialcreate$finishGroup(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        differentialcreate$park(builder, 80);
    }

    @Inject(method = "linearAttachement", at = @At("RETURN"))
    private static void differentialcreate$finishAttachment(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        differentialcreate$park(builder, 200);
    }
}
