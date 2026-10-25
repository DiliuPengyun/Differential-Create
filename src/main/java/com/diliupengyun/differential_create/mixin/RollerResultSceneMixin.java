package com.diliupengyun.differential_create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.RollerScenes;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RollerScenes.class)
public abstract class RollerResultSceneMixin {
    @WrapOperation(method = {"fill", "clearAndPave"}, at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;hideIndependentSection(Lnet/createmod/ponder/api/element/ElementLink;Lnet/minecraft/core/Direction;)V"))
    private static void differentialcreate$finishPass(CreateSceneBuilder.WorldInstructions world,
        net.createmod.ponder.api.element.ElementLink<net.createmod.ponder.api.element.WorldSectionElement> link, net.minecraft.core.Direction direction, Operation<Void> original,
        SceneBuilder builder, SceneBuildingUtil util) {
        builder.idle(20);
        builder.addInstruction(new com.diliupengyun.differential_create.client.ponder.PonderAwait(scene ->
            com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(scene.getWorld()).trainArrived(scene.resolve(link)), 2400));
        original.call(world, link, direction);
    }
    @WrapOperation(method = {"fill", "clearAndPave"}, at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static <T extends net.minecraft.world.level.block.entity.BlockEntity> void differentialcreate$realDrumSpeed(CreateSceneBuilder.WorldInstructions world,
        net.minecraft.core.BlockPos pos, Class<T> type, java.util.function.Consumer<T> action, Operation<Void> original) {
        if (!com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity.class.isAssignableFrom(type)) original.call(world, pos, type, action);
    }
    @WrapOperation(method = "clearAndPave", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;replaceBlocks(Lnet/createmod/ponder/api/scene/Selection;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private static void differentialcreate$actualPaving(CreateSceneBuilder.WorldInstructions world, Selection selection, BlockState state,
        boolean particles, Operation<Void> original) {}
    @Inject(method = "fill", at = @At("HEAD"))
    private static void differentialcreate$emptyReceivingSpace(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        var scene = new CreateSceneBuilder(builder);
        var receiving = util.select().fromTo(0, 1, 1, 5, 3, 7);
        scene.world().setBlocks(receiving, Blocks.AIR.defaultBlockState(), false);
        for (int z = 3; z <= 5; z++) scene.world().modifyBlockEntity(new net.minecraft.core.BlockPos(6, 6, z),
            com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity.class, roller -> roller.mode.setValue(1));
    }
    @Inject(method = "fill", at = @At(value = "INVOKE", ordinal = 1,
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;showIndependentSection(Lnet/createmod/ponder/api/scene/Selection;Lnet/minecraft/core/Direction;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static void differentialcreate$slopedMode(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        var world = new CreateSceneBuilder(builder).world();
        world.setBlocks(util.select().fromTo(0, 1, 1, 5, 3, 7), Blocks.AIR.defaultBlockState(), false);
        for (int z = 3; z <= 5; z++) world.modifyBlockEntity(new net.minecraft.core.BlockPos(6, 6, z),
            com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity.class, roller -> roller.mode.setValue(2));
    }
    @WrapOperation(method = "fill", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;replaceBlocks(Lnet/createmod/ponder/api/scene/Selection;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private static void differentialcreate$newObstacleExample(CreateSceneBuilder.WorldInstructions world, Selection selected, BlockState state,
        boolean particles, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        world.setBlocks(util.select().fromTo(0, 1, 1, 5, 3, 7), Blocks.AIR.defaultBlockState(), false);
        original.call(world, selected, state, particles);
    }
}
