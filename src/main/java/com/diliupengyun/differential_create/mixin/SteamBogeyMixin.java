package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.trains.*;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.foundation.blockEntity.CachedRenderBBBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractBogeyBlockEntity.class)
public abstract class SteamBogeyMixin extends CachedRenderBBBlockEntity implements SteamHoseLinks.EngineLink {
    protected SteamBogeyMixin(BlockEntityType<?> type,BlockPos pos,BlockState state) {super(type,pos,state);}
    @Override public BlockPos differentialcreate$hose() {
        var data=((AbstractBogeyBlockEntity)(Object)this).getBogeyData();
        return data.contains(SteamHoseLinks.ENGINE_KEY) && SteamHosePort.read(data) != null ? BlockPos.of(data.getLong(SteamHoseLinks.ENGINE_KEY)) : null;
    }
    @Override public void differentialcreate$hose(BlockPos offset) {
        var be=(AbstractBogeyBlockEntity)(Object)this;var data=be.getBogeyData().copy();
        data.remove(SteamHosePort.KEY);
        if(offset==null)data.remove(SteamHoseLinks.ENGINE_KEY);else data.putLong(SteamHoseLinks.ENGINE_KEY,offset.asLong());
        be.setBogeyData(data);
    }
    @Override public SteamHosePort differentialcreate$port() {
        return SteamHosePort.read(((AbstractBogeyBlockEntity)(Object)this).getBogeyData());
    }
    @Override public void differentialcreate$port(SteamHosePort port) {
        var be=(AbstractBogeyBlockEntity)(Object)this;var data=be.getBogeyData().copy();
        SteamHosePort.write(data,port);be.setBogeyData(data);
    }
    @Override public void onLoad() {super.onLoad();SteamBogey.loaded((AbstractBogeyBlockEntity)(Object)this);}
    @Override public void setRemoved() {SteamHoseLinks.detachIndex(this);super.setRemoved();}
    @Inject(method="loadAdditional",at=@At("RETURN"))
    private void differentialcreate$loadedData(CompoundTag tag,HolderLookup.Provider registries,CallbackInfo ci) {SteamBogey.loaded((AbstractBogeyBlockEntity)(Object)this);}
    @Inject(method="setBogeyData",at=@At("RETURN"))
    private void differentialcreate$changedData(CompoundTag tag,CallbackInfo ci) {SteamBogey.loaded((AbstractBogeyBlockEntity)(Object)this);}
}
