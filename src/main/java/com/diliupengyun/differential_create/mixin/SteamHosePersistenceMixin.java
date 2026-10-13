package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SteamEngineBlockEntity inherits persistence from SmartBlockEntity. */
@Mixin(SmartBlockEntity.class)
public abstract class SteamHosePersistenceMixin {
    @Inject(method = "write", at = @At("RETURN"))
    private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean client, CallbackInfo ci) {
        if ((Object) this instanceof SteamHoseLinks.EngineLink engine && engine.differentialcreate$hose() != null)
            tag.putLong(SteamHoseLinks.ENGINE_KEY, engine.differentialcreate$hose().asLong());
    }
    @Inject(method = "read", at = @At("RETURN"))
    private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean client, CallbackInfo ci) {
        if ((Object) this instanceof SteamHoseLinks.EngineLink engine)
            engine.differentialcreate$hose(tag.contains(SteamHoseLinks.ENGINE_KEY) ? BlockPos.of(tag.getLong(SteamHoseLinks.ENGINE_KEY)) : null);
    }
}
