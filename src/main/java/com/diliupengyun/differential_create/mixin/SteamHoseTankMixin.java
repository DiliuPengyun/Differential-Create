package com.diliupengyun.differential_create.mixin;

import java.util.HashSet;
import java.util.Set;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FluidTankBlockEntity.class)
public abstract class SteamHoseTankMixin implements SteamHoseLinks.TankLinks {
    @Unique private final Set<BlockPos> differentialcreate$engines = new HashSet<>();
    @Unique private double differentialcreate$feedWater;
    @Override public Set<BlockPos> differentialcreate$engines() { return differentialcreate$engines; }
    @Override public double differentialcreate$feedWater() { return differentialcreate$feedWater; }
    @Override public void differentialcreate$feedWater(double amount) { differentialcreate$feedWater = amount; }
    @Inject(method = "write", at = @At("RETURN"))
    private void differentialcreate$writeLinks(CompoundTag tag, HolderLookup.Provider registries, boolean client, CallbackInfo ci) {
        if (!differentialcreate$engines.isEmpty()) tag.putLongArray(SteamHoseLinks.TANK_KEY,
            differentialcreate$engines.stream().mapToLong(BlockPos::asLong).toArray());
        if (differentialcreate$feedWater > 0) tag.putDouble(SteamHoseLinks.WATER_KEY, differentialcreate$feedWater);
    }
    @Inject(method = "read", at = @At("RETURN"))
    private void differentialcreate$readLinks(CompoundTag tag, HolderLookup.Provider registries, boolean client, CallbackInfo ci) {
        differentialcreate$engines.clear();
        for (long pos : tag.getLongArray(SteamHoseLinks.TANK_KEY)) differentialcreate$engines.add(BlockPos.of(pos));
        double water = tag.getDouble(SteamHoseLinks.WATER_KEY);
        differentialcreate$feedWater = Double.isFinite(water) ? Math.clamp(water, 0, 1) : 0;
    }
}
