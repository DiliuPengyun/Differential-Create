package com.diliupengyun.differential_create.mixin;

import java.util.function.UnaryOperator;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.fluid.PumpScenes;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the native inlet and show cooperation before the opposing-pump example. */
@Mixin(PumpScenes.class)
public abstract class PumpResultSceneMixin {
    private static final BlockPos NORTH_PUMP = new BlockPos(2, 1, 1);

    @Inject(method = "speed", at = @At("HEAD"))
    private static void differentialcreate$inlet(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        new CreateSceneBuilder(builder).world().setKineticSpeed(util.select().everywhere(), 0);
        builder.addInstruction(scene -> PonderKinetics.of(scene.getWorld()).boundary(scene.getWorld(), new BlockPos(5, 0, 3), 0));
    }

    @WrapOperation(method = "speed", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlock(Lnet/minecraft/core/BlockPos;Ljava/util/function/UnaryOperator;Z)V"))
    private static void differentialcreate$cooperateThenOppose(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        UnaryOperator<BlockState> change, boolean particles, Operation<Void> original) {
        if (!pos.equals(NORTH_PUMP)) {
            original.call(world, pos, change, particles);
            return;
        }
        // First face all three pumps toward the receiving tank. The later native
        // wrench action reverses just this pump, making the counterexample visible.
        original.call(world, pos, (UnaryOperator<BlockState>) state -> {
            var result = change.apply(state);
            return result.setValue(PumpBlock.FACING, result.getValue(PumpBlock.FACING).getOpposite());
        }, particles);
    }

    @ModifyArg(method = "speed", at = @At(value = "INVOKE",
        target = "Lnet/createmod/ponder/api/scene/OverlayInstructions;showOutlineWithText(Lnet/createmod/ponder/api/scene/Selection;I)Lnet/createmod/ponder/api/element/TextElementBuilder;"), index = 1)
    private static int differentialcreate$cooperationReadingTime(int duration) { return 90; }

    @ModifyArg(method = "speed", at = @At(value = "INVOKE", ordinal = 3,
        target = "Lnet/createmod/ponder/api/scene/OverlayInstructions;showText(I)Lnet/createmod/ponder/api/element/TextElementBuilder;"), index = 0)
    private static int differentialcreate$oppositionReadingTime(int duration) { return 100; }

    @Inject(method = "speed", at = @At(value = "INVOKE", ordinal = 3,
        target = "Lnet/createmod/ponder/api/scene/OverlayInstructions;showText(I)Lnet/createmod/ponder/api/element/TextElementBuilder;"))
    private static void differentialcreate$highlightReversedPump(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        new CreateSceneBuilder(builder).overlay().showOutline(PonderPalette.RED, new Object(), util.select().position(NORTH_PUMP), 100);
    }
}
