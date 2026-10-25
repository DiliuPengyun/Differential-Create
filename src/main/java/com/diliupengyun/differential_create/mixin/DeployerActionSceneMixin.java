package com.diliupengyun.differential_create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.DeployerScenes;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DeployerScenes.class)
public abstract class DeployerActionSceneMixin {
    @WrapOperation(method = "modes", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;replaceBlocks(Lnet/createmod/ponder/api/scene/Selection;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private static void differentialcreate$actualHoe(CreateSceneBuilder.WorldInstructions world, Selection selection, BlockState state,
        boolean particles, Operation<Void> original) {}
    @WrapOperation(method = "modes", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemEntity(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/item/ItemStack;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static net.createmod.ponder.api.element.ElementLink<net.createmod.ponder.api.element.EntityElement> differentialcreate$actualDrop(CreateSceneBuilder.WorldInstructions world,
        net.minecraft.world.phys.Vec3 pos, net.minecraft.world.phys.Vec3 motion, net.minecraft.world.item.ItemStack stack,
        Operation<net.createmod.ponder.api.element.ElementLink<net.createmod.ponder.api.element.EntityElement>> original) { return new net.createmod.ponder.foundation.element.ElementLinkImpl<>(net.createmod.ponder.api.element.EntityElement.class); }
}
