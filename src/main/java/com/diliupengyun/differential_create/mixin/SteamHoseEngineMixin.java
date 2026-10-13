package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SteamEngineBlockEntity.class)
public abstract class SteamHoseEngineMixin implements SteamHoseLinks.EngineLink {
    @Unique private BlockPos differentialcreate$hose;
    @Unique private boolean differentialcreate$mountEvaluated;
    @Override public BlockPos differentialcreate$hose() { return differentialcreate$hose; }
    @Override public void differentialcreate$hose(BlockPos offset) { differentialcreate$hose = offset; differentialcreate$mountEvaluated = false; }
    @Inject(method = "tick", at = @At("HEAD"))
    private void differentialcreate$rebuildTankIndex(CallbackInfo ci) {
        var engine = (SteamEngineBlockEntity) (Object) this;
        if (!differentialcreate$mountEvaluated && differentialcreate$hose != null && engine.getLevel() != null
            && !engine.getLevel().isClientSide && !engine.isRemoved()) {
            SteamHoseLinks.refreshDirectMount(engine); differentialcreate$mountEvaluated = true;
        }
        SteamHoseLinks.ensureRegistered(engine);
    }
    @Inject(method = "remove", at = @At("HEAD"))
    private void differentialcreate$unbindOnRemove(CallbackInfo ci) {
        var engine = (SteamEngineBlockEntity) (Object) this;
        if (engine.getLevel() != null && !engine.getLevel().isClientSide) SteamHoseLinks.unlink(engine);
    }
}
