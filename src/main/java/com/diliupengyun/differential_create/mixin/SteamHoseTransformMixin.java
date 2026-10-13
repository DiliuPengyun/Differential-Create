package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.HolderLookup;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Contraption.class)
public abstract class SteamHoseTransformMixin {
    @WrapOperation(method = "addBlocksToWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;loadWithComponents(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V"))
    private void differentialcreate$relativeConnections(BlockEntity entity, CompoundTag tag, HolderLookup.Provider registries,
        Operation<Void> original, @Local(argsOnly = true) StructureTransform transform) {
        if (!tag.contains(SteamHoseLinks.ENGINE_KEY) && !tag.contains(SteamHoseLinks.TANK_KEY)
            && !tag.getCompound("BogeyData").contains(SteamHoseLinks.ENGINE_KEY)) { original.call(entity, tag, registries); return; }
        tag = tag.copy(); BlockPos zero = transform.apply(BlockPos.ZERO);
        var bogey = tag.getCompound("BogeyData");
        if (bogey.contains(SteamHoseLinks.ENGINE_KEY)) bogey.putLong(SteamHoseLinks.ENGINE_KEY,
            transform.apply(BlockPos.of(bogey.getLong(SteamHoseLinks.ENGINE_KEY))).subtract(zero).asLong());
        if (tag.contains(SteamHoseLinks.ENGINE_KEY)) tag.putLong(SteamHoseLinks.ENGINE_KEY,
            transform.apply(BlockPos.of(tag.getLong(SteamHoseLinks.ENGINE_KEY))).subtract(zero).asLong());
        long[] links = tag.getLongArray(SteamHoseLinks.TANK_KEY);
        for (int i = 0; i < links.length; i++) links[i] = transform.apply(BlockPos.of(links[i])).subtract(zero).asLong();
        if (links.length > 0) tag.putLongArray(SteamHoseLinks.TANK_KEY, links);
        original.call(entity, tag, registries);
    }
}
