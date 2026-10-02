package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.ContraptionActorWork;
import com.diliupengyun.differential_create.kinetics.IContraptionPaver;
import com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import java.util.function.Predicate;
import com.simibubi.create.foundation.item.ItemHelper;
import net.neoforged.neoforge.items.IItemHandler;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RollerMovementBehaviour.class)
public abstract class RollerMovementBehaviourMixin implements IContraptionPaver {
	@Inject(method = "triggerPaver", at = @At("RETURN"))
	private void differentialcreate$continuousPaving(MovementContext context, BlockPos pos, CallbackInfo ci) {
		if (ContraptionActorWork.supported(context)) {
			context.stall = false; context.data.remove("WaitingTicks"); context.data.remove("LastPos");
		}
	}
	@Shadow protected abstract void triggerPaver(MovementContext context, BlockPos pos);
	@Override public void differentialcreate$resumePaving(MovementContext context, BlockPos pos) {
		triggerPaver(context, pos);
	}
	@Redirect(method = "tryFill", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/foundation/item/ItemHelper;extract(Lnet/neoforged/neoforge/items/IItemHandler;Ljava/util/function/Predicate;IZ)Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack differentialcreate$pavingWork(IItemHandler inventory, Predicate<ItemStack> filter, int amount, boolean simulate,
		MovementContext context, BlockPos target, BlockState toPlace) {
		boolean physical = ContraptionActorWork.supported(context);
		if (physical && ContraptionActorWork.of(context).pending()) return ItemStack.EMPTY;
		var material = ItemHelper.extract(inventory, filter, amount, simulate);
		if (physical && !material.isEmpty() && ContraptionActorWork.queueFill(context, target, toPlace, material))
			return ItemStack.EMPTY; // Native paving waits; the queued job owns the exact reserved stack.
		return material;
	}
}
