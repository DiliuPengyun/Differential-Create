package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderBeltInput;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.foundation.ponder.element.BeltItemElement;
import com.simibubi.create.infrastructure.ponder.scenes.fluid.SpoutScenes;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpoutScenes.class)
public abstract class SpoutSceneMixin {
    @Inject(method = "filling", at = @At("HEAD"))
    private static void differentialcreate$initialHoney(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        // Restore the original template's stock using the current fluid format.
        // The legacy FluidName/Amount tags no longer load into FluidStack.
        var scene = new CreateSceneBuilder(builder);
        var honey = com.simibubi.create.AllFluids.HONEY.get().getSource();
        var fill = net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
        scene.world().modifyBlockEntity(util.grid().at(1, 1, 4), com.simibubi.create.content.fluids.tank.FluidTankBlockEntity.class,
            tank -> tank.getTankInventory().fill(new net.neoforged.neoforge.fluids.FluidStack(honey, 10000), fill));
        scene.world().modifyBlockEntity(util.grid().at(2, 3, 2), com.simibubi.create.content.fluids.spout.SpoutBlockEntity.class,
            spout -> spout.getBehaviour(com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour.TYPE)
                .getPrimaryHandler().fill(new net.neoforged.neoforge.fluids.FluidStack(honey, 1000), fill));
    }
    @WrapOperation(method = "filling", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$EffectInstructions;emitParticles(Lnet/minecraft/world/phys/Vec3;Lnet/createmod/ponder/api/ParticleEmitter;FI)V"))
    private static void differentialcreate$actualParticles(CreateSceneBuilder.EffectInstructions effects, net.minecraft.world.phys.Vec3 pos,
        net.createmod.ponder.api.ParticleEmitter emitter, float count, int duration, Operation<Void> original) {}
    @WrapOperation(method = "filling", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemOnBelt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static ElementLink<BeltItemElement> differentialcreate$belt(CreateSceneBuilder.WorldInstructions world,
        BlockPos pos, Direction side, ItemStack stack, Operation<ElementLink<BeltItemElement>> original, SceneBuilder builder, SceneBuildingUtil util) {
        var scene = new CreateSceneBuilder(builder);
        return stack.is(Items.HONEY_BOTTLE) ? PonderBeltInput.convert(scene, pos, Items.GLASS_BOTTLE, stack)
            : PonderBeltInput.insert(scene, pos, side, stack);
    }
    @WrapOperation(method = "filling", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemOnBeltLike(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)V"))
    private static void differentialcreate$depot(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Direction side,
        ItemStack stack, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        if (stack.is(Items.HONEY_BOTTLE)) PonderBeltInput.convert(new CreateSceneBuilder(builder), pos, Items.GLASS_BOTTLE, stack);
        else original.call(world, pos, side, stack);
    }
    @WrapOperation(method = "filling", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;removeItemsFromBelt(Lnet/minecraft/core/BlockPos;)V"))
    private static void differentialcreate$retain(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Operation<Void> original) {}
    @WrapOperation(method = "filling", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;stallBeltItem(Lnet/createmod/ponder/api/element/ElementLink;Z)V"))
    private static void differentialcreate$contact(CreateSceneBuilder.WorldInstructions world, ElementLink<BeltItemElement> item,
        boolean held, Operation<Void> original) {}
}
