package com.diliupengyun.differential_create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.FunnelScenes;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FunnelScenes.class)
public abstract class FunnelSceneMixin {
    @WrapOperation(method = "intro", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;showIndependentSection(Lnet/createmod/ponder/api/scene/Selection;Lnet/minecraft/core/Direction;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static ElementLink<WorldSectionElement> differentialcreate$transferOwnership(CreateSceneBuilder.WorldInstructions world,
        Selection selection, Direction direction, Operation<ElementLink<WorldSectionElement>> original,
        SceneBuilder builder, SceneBuildingUtil util) {
        builder.addInstruction(scene -> scene.getBaseWorldSection().erase(selection));
        return original.call(world, selection, direction);
    }
}
