package com.diliupengyun.differential_create.mixin;

import java.util.function.Consumer;
import com.diliupengyun.differential_create.client.ponder.PonderBeltInput;
import com.diliupengyun.differential_create.client.ponder.PonderNativePressing;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.AllItems;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adapt only the native press's timed fabrication; retain its layout, camera, captions and inputs. */
@Mixin(ProcessingScenes.class)
public abstract class PressingSceneMixin {
    @Inject(method = "pressing", at = @At(value = "INVOKE", ordinal = 0,
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static void differentialcreate$processWorkpieces(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        builder.addInstruction(new PonderNativePressing(new BlockPos(2, 3, 2), new BlockPos(2, 1, 1)));
    }

    @WrapOperation(method = "pressing", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemOnBeltLike(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)V"))
    private static void differentialcreate$depotInput(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        Direction side, ItemStack stack, Operation<Void> original) {
        if (!stack.is(AllItems.COPPER_SHEET.get())) original.call(world, pos, side, stack);
    }

    @WrapOperation(method = "pressing", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;createItemOnBelt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static ElementLink<BeltItemElement> differentialcreate$beltInput(CreateSceneBuilder.WorldInstructions world,
        BlockPos pos, Direction side, ItemStack stack, Operation<ElementLink<BeltItemElement>> original,
        SceneBuilder builder, SceneBuildingUtil util) {
        return stack.is(AllItems.COPPER_SHEET.get()) ? new ElementLinkImpl<>(BeltItemElement.class)
            : PonderBeltInput.insert(new CreateSceneBuilder(builder), pos, side, stack);
    }

    @WrapOperation(method = "pressing", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;removeItemsFromBelt(Lnet/minecraft/core/BlockPos;)V"))
    private static void differentialcreate$keepInputUntilContact(CreateSceneBuilder.WorldInstructions world,
        BlockPos pos, Operation<Void> original) {}

    @WrapOperation(method = "pressing", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;stallBeltItem(Lnet/createmod/ponder/api/element/ElementLink;Z)V"))
    private static void differentialcreate$holdAtPress(CreateSceneBuilder.WorldInstructions world,
        ElementLink<BeltItemElement> link, boolean stalled, Operation<Void> original) {}

    @WrapOperation(method = "pressing", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static void differentialcreate$contactOwnsCycle(CreateSceneBuilder.WorldInstructions world,
        BlockPos pos, Class<? extends BlockEntity> type, Consumer<?> change, Operation<Void> original) {}

    @Inject(method = "pressing", at = @At("RETURN"))
    private static void differentialcreate$finishReturnStroke(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        builder.idle(30);
    }
}
