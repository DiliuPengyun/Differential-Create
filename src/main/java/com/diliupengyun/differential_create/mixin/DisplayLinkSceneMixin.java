package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.DisplayScenes;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The displayed gauge reports its real preview state instead of the old 1024 su sample. */
@Mixin(DisplayScenes.class)
public abstract class DisplayLinkSceneMixin {
    @WrapOperation(method = "link", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setDisplayBoardText(Lnet/minecraft/core/BlockPos;ILnet/minecraft/network/chat/Component;)V"))
    private static void differentialcreate$liveGauge(CreateSceneBuilder.WorldInstructions world, BlockPos board,
        int line, Component text, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        boolean load = differentialcreate$stressUnit(text);
        builder.addInstruction(scene -> PonderKinetics.of(scene.getWorld())
            .displayReading(board, line, load ? new BlockPos(3, 3, 1) : null));
        if (!load) original.call(world, board, line, text);
    }

    private static boolean differentialcreate$stressUnit(Component text) {
        return text.getContents() instanceof TranslatableContents translation
                && translation.getKey().equals("create.generic.unit.stress")
            || text.getSiblings().stream().anyMatch(DisplayLinkSceneMixin::differentialcreate$stressUnit);
    }
}
