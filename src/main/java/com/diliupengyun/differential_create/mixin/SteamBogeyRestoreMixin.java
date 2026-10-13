package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.trains.SteamBogey;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Train.class)
public abstract class SteamBogeyRestoreMixin {
    @WrapOperation(method="disassemble",at=@At(value="INVOKE",target="Lcom/simibubi/create/content/trains/bogey/AbstractBogeyBlockEntity;setBogeyData(Lnet/minecraft/nbt/CompoundTag;)V"))
    private void differentialcreate$transformedConnection(AbstractBogeyBlockEntity be,CompoundTag data,Operation<Void> original) {
        var placed=be.getBogeyData();
        if(data.getBoolean(SteamBogey.ENGINE)&&placed.contains(SteamHoseLinks.ENGINE_KEY)) {
            data=data.copy();data.putLong(SteamHoseLinks.ENGINE_KEY,placed.getLong(SteamHoseLinks.ENGINE_KEY));
        }
        original.call(be,data);
    }
}
