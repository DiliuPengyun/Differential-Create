package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.GantryScenes;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GantryScenes.class)
public abstract class GantrySceneMixin {
    @WrapOperation(method = "redstone", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;showSection(Lnet/createmod/ponder/api/scene/Selection;Lnet/minecraft/core/Direction;)V", ordinal = 2))
    private static void differentialcreate$outputAtSettledCarriage(CreateSceneBuilder.WorldInstructions world,
        Selection selection, Direction direction, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        var output = world.showIndependentSection(selection, direction);
        builder.addInstruction(scene -> {
            var carriage = PonderSimulation.of(scene.getWorld()).point(new BlockPos(4, 2, 2));
            scene.resolve(output).setAnimatedOffset(carriage.subtract(new Vec3(1, 2, 2)), true);
        });
    }
}
