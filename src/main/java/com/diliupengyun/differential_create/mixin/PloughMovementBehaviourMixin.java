package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ContraptionActorWork;
import com.simibubi.create.content.contraptions.actors.plough.PloughMovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.neoforge.common.ItemAbilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PloughMovementBehaviour.class)
public abstract class PloughMovementBehaviourMixin {
	@Redirect(method = "visitNewPosition", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/item/ItemStack;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
	private InteractionResult differentialcreate$tillWork(ItemStack hoe, UseOnContext use, MovementContext context, BlockPos pos) {
		var ground = context.world.getBlockState(use.getClickedPos());
		if (ground.getToolModifiedState(use, ItemAbilities.HOE_TILL, true) != null
			&& ContraptionActorWork.queue(context, "plough", pos, ContraptionActorWork.CYCLE_TRAVEL)) return InteractionResult.PASS;
		return hoe.useOn(use);
	}
}
