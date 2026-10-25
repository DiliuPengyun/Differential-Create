package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.ponder.PonderLootPayloads;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A preview query must not advance the saved world's loot random sequence. */
@Mixin(LootTable.class)
public abstract class PonderLootTableMixin {
    @Inject(method = "getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$isolatedRandom(LootParams params, CallbackInfoReturnable<ObjectArrayList<ItemStack>> cir) {
        var random = PonderLootPayloads.random();
        if (random != null) cir.setReturnValue(((LootTable) (Object) this).getRandomItems(params, random));
    }
}
