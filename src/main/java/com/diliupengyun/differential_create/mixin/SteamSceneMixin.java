package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.SteamScenes;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SteamScenes.class)
public abstract class SteamSceneMixin {
    @Inject(method = "engine", at = @At("HEAD"))
    private static void differentialcreate$wellFoundation(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        new CreateSceneBuilder(builder).addInstruction(scene -> {
            // The original three-cell well lies in the base plate. Give it a real
            // floor below the template so vanilla source regeneration is possible.
            for (int x = 3; x <= 5; x++) scene.getWorld().setBlock(new BlockPos(x, -1, 1), Blocks.STONE.defaultBlockState(), 3);
            for (var be : scene.getWorld().getBlockEntities()) {
                if (be instanceof com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity engine) {
                    // These are the same direction choices as the native row-by-row
                    // +/- RPM instructions, applied to the real cylinder setting.
                    var data = engine.saveWithoutMetadata(scene.getWorld().registryAccess());
                    data.putInt("ScrollValue", engine.getBlockPos().getY() == 4 || engine.getBlockPos().getY() == 8 ? 0 : 1);
                    engine.loadWithComponents(data, scene.getWorld().registryAccess());
                }
                if (be instanceof com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity shaft) shaft.source = null;
            }
        });
    }
}
