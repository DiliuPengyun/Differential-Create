package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.PhysicsPreview;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.equipment.sandPaper.SandPaperItem;
import com.simibubi.create.content.kinetics.deployer.*;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A virtual hand has an inventory but no server player. Use Create's recipe search. */
@Mixin(DeployerBlockEntity.class)
public abstract class PonderDeployerRecipeMixin {
    @Shadow protected ItemStack heldItem;

    @Inject(method = "getRecipe", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$recipe(ItemStack input,
        CallbackInfoReturnable<RecipeHolder<? extends Recipe<? extends RecipeInput>>> cir) {
        var self = (DeployerBlockEntity) (Object) this;
        var world = self.getLevel();
        if (!PhysicsPreview.enabled(world)) return;
        if (heldItem.isEmpty()) { cir.setReturnValue(null); return; }
        if (heldItem.getItem() instanceof SandPaperItem) {
            var polish = AllRecipeTypes.SANDPAPER_POLISHING.find(new SingleRecipeInput(input), world)
                .filter(AllRecipeTypes.CAN_BE_AUTOMATED);
            if (polish.isPresent()) { cir.setReturnValue(polish.get()); return; }
        }
        var inventory = new ItemStackHandler(2);
        inventory.setStackInSlot(0, input); inventory.setStackInSlot(1, heldItem);
        var event = new DeployerRecipeSearchEvent(self, new RecipeWrapper(inventory));
        event.addRecipe(() -> SequencedAssemblyRecipe.getRecipe(world, event.getInventory(),
            AllRecipeTypes.DEPLOYING.getType(), DeployerApplicationRecipe.class), 100);
        event.addRecipe(() -> AllRecipeTypes.DEPLOYING.find(event.getInventory(), world)
            .filter(AllRecipeTypes.CAN_BE_AUTOMATED), 50);
        event.addRecipe(() -> AllRecipeTypes.ITEM_APPLICATION.find(event.getInventory(), world)
            .filter(AllRecipeTypes.CAN_BE_AUTOMATED), 50);
        NeoForge.EVENT_BUS.post(event);
        cir.setReturnValue(event.getRecipe());
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void differentialcreate$inventory(CompoundTag tag, HolderLookup.Provider registries,
        boolean packet, CallbackInfo ci) {
        if (PhysicsPreview.enabled(((DeployerBlockEntity) (Object) this).getLevel()))
            tag.put("HeldItem", heldItem.isEmpty() ? new CompoundTag() : heldItem.save(registries));
    }
}
