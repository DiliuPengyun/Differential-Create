package com.diliupengyun.differential_create.mixin;

import java.util.function.Consumer;
import com.diliupengyun.differential_create.client.ponder.PonderBeltInput;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.foundation.ponder.element.BeltItemElement;
import com.simibubi.create.infrastructure.ponder.scenes.ProcessingScenes;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.foundation.element.ElementLinkImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The native timeline supplies ingredients and operator actions, never recipe results. */
@Mixin(ProcessingScenes.class)
public abstract class ProcessingRecipeSceneMixin {
    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "crushingWheels", at = @At(value = "NEW", target = "(Lnet/minecraft/world/level/ItemLike;)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack differentialcreate$actualProductCue(ItemStack stack) {
        return stack.is(Items.RAW_GOLD) ? com.simibubi.create.AllItems.CRUSHED_GOLD.asStack() : stack;
    }
    @WrapOperation(method = {"millstone", "mixing", "compacting", "basin"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemOnBelt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static ElementLink<BeltItemElement> differentialcreate$noFabricatedOutput(CreateSceneBuilder.WorldInstructions world,
        BlockPos pos, Direction side, ItemStack stack, Operation<ElementLink<BeltItemElement>> original) {
        return new ElementLinkImpl<>(BeltItemElement.class);
    }
    @WrapOperation(method = "millstone", at = @At(value = "INVOKE", ordinal = 1, target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static void differentialcreate$millConsumesInput(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        Class<? extends BlockEntity> type, Consumer<?> action, Operation<Void> original) {}

    @WrapOperation(method = "mixing", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static void differentialcreate$recipeStartsMixer(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        Class<? extends BlockEntity> type, Consumer<?> action, Operation<Void> original) {}

    @WrapOperation(method = "compacting", at = @At(value = "INVOKE", ordinal = 0, target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static void differentialcreate$copperInput(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        Class<? extends BlockEntity> type, Consumer<?> action, Operation<Void> original) {
        world.modifyBlockEntity(pos.below(2), BasinBlockEntity.class,
            basin -> ItemHandlerHelper.insertItemStacked(basin.getInputInventory(), new ItemStack(Items.COPPER_INGOT, 9), false));
    }
    @WrapOperation(method = "compacting", at = @At(value = "INVOKE", ordinal = 2, target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static void differentialcreate$woodInput(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        Class<? extends BlockEntity> type, Consumer<?> action, Operation<Void> original) {
        world.modifyBlockEntity(pos.below(2), BasinBlockEntity.class,
            basin -> ItemHandlerHelper.insertItemStacked(basin.getInputInventory(), new ItemStack(Items.OAK_LOG, 4), false));
    }
    @WrapOperation(method = "compacting", at = @At(value = "INVOKE", ordinal = 1, target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static void differentialcreate$copperContact(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        Class<? extends BlockEntity> type, Consumer<?> action, Operation<Void> original) {}
    @WrapOperation(method = "compacting", at = @At(value = "INVOKE", ordinal = 3, target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static void differentialcreate$woodContact(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        Class<? extends BlockEntity> type, Consumer<?> action, Operation<Void> original) {}
    @WrapOperation(method = "basin", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static void differentialcreate$basinOperator(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        Class<? extends BlockEntity> type, Consumer<?> action, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        // The first batch consists of the four thrown bricks. In the second
        // example nine nuggets make an ingot, reused with eight supplied ingots.
        builder.addInstruction(scene -> {
            if (!(scene.getWorld().getBlockEntity(pos.below(2)) instanceof BasinBlockEntity basin)
                || scene.getWorld().getBlockState(pos.below(2)).getValue(com.simibubi.create.content.processing.basin.BasinBlock.FACING) != Direction.DOWN) return;
            var simulation = com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(scene.getWorld());
            boolean ingot = false;
            for (int slot = 0; slot < basin.getOutputInventory().getSlots(); slot++) ingot |= basin.getOutputInventory().getStackInSlot(slot).is(Items.COPPER_INGOT);
            if (ingot) simulation.initializeOnce(pos.above(),
                () -> ItemHandlerHelper.insertItemStacked(basin.getInputInventory(), new ItemStack(Items.COPPER_INGOT, 8), false));
            else simulation.initializeOnce(pos,
                () -> ItemHandlerHelper.insertItemStacked(basin.getInputInventory(), com.simibubi.create.AllItems.COPPER_NUGGET.asStack(9), false));
        });
    }
    @WrapOperation(method = "crushingWheels", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemOnBelt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static ElementLink<BeltItemElement> differentialcreate$crusherInput(CreateSceneBuilder.WorldInstructions world,
        BlockPos pos, Direction side, ItemStack stack, Operation<ElementLink<BeltItemElement>> original, SceneBuilder builder, SceneBuildingUtil util) {
        return stack.is(Items.GOLD_ORE) ? PonderBeltInput.insert(new CreateSceneBuilder(builder), pos, side, stack)
            : new ElementLinkImpl<>(BeltItemElement.class);
    }
    @WrapOperation(method = "crushingWheels", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemEntity(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/item/ItemStack;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static ElementLink<net.createmod.ponder.api.element.EntityElement> differentialcreate$crusherDrop(CreateSceneBuilder.WorldInstructions world,
        net.minecraft.world.phys.Vec3 pos, net.minecraft.world.phys.Vec3 motion, ItemStack stack,
        Operation<ElementLink<net.createmod.ponder.api.element.EntityElement>> original) {
        return stack.is(Items.GOLD_ORE) ? original.call(world, pos, motion, stack)
            : new ElementLinkImpl<>(net.createmod.ponder.api.element.EntityElement.class);
    }
    @WrapOperation(method = "crushingWheels", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyEntity(Lnet/createmod/ponder/api/element/ElementLink;Ljava/util/function/Consumer;)V"))
    private static void differentialcreate$intakeOwnsEntity(CreateSceneBuilder.WorldInstructions world,
        ElementLink<?> link, Consumer<?> action, Operation<Void> original) {}
    @WrapOperation(method = "crushingWheels", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;removeItemsFromBelt(Lnet/minecraft/core/BlockPos;)V"))
    private static void differentialcreate$intakeOwnsBelt(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Operation<Void> original) {}
    @WrapOperation(method = "crushingWheels", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$EffectInstructions;emitParticles(Lnet/minecraft/world/phys/Vec3;Lnet/createmod/ponder/api/ParticleEmitter;FI)V"))
    private static void differentialcreate$recipeParticles(CreateSceneBuilder.EffectInstructions effects, net.minecraft.world.phys.Vec3 pos,
        net.createmod.ponder.api.ParticleEmitter emitter, float count, int duration, Operation<Void> original) {}
}
