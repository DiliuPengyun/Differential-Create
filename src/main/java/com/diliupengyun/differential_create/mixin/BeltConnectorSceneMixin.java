package com.diliupengyun.differential_create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.BeltScenes;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The stationary inlet and the movable belt-end shaft need distinct simulation identities. */
@Mixin(BeltScenes.class)
public abstract class BeltConnectorSceneMixin {
    @WrapOperation(method = "beltConnector", at = @At(value = "INVOKE", ordinal = 1,
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;showSection(Lnet/createmod/ponder/api/scene/Selection;Lnet/minecraft/core/Direction;)V"))
    private static void differentialcreate$separateShaft(CreateSceneBuilder.WorldInstructions instructions, Selection selection,
        Direction direction, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        var scene = new CreateSceneBuilder(builder); var source = new BlockPos(4, 1, 3); var copy = new BlockPos(4, 1, 6);
        builder.addInstruction(s -> {
            var world = s.getWorld(); var originalShaft = world.getBlockEntity(source);
            world.setBlock(copy, world.getBlockState(source), 3);
            if (originalShaft != null && world.getBlockEntity(copy) != null)
                world.getBlockEntity(copy).loadWithComponents(originalShaft.saveWithoutMetadata(world.registryAccess()), world.registryAccess());
            if (world.getBlockEntity(copy) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity shaft) shaft.source = null;
        });
        original.call(instructions, selection.substract(util.select().position(source)), direction);
        var fixed = scene.world().showIndependentSection(util.select().position(copy), direction);
        scene.world().moveSection(fixed, util.vector().of(0, 0, -3), 0);
    }
}
