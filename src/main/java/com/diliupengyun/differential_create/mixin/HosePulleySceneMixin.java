package com.diliupengyun.differential_create.mixin;

import java.util.function.Consumer;
import com.diliupengyun.differential_create.client.ponder.PonderAwait;
import com.diliupengyun.differential_create.client.ponder.PonderHoseSupply;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.infrastructure.ponder.scenes.fluid.HosePulleyScenes;
import net.createmod.ponder.api.scene.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HosePulleyScenes.class)
public abstract class HosePulleySceneMixin {
    @ModifyConstant(method = {"intro", "level"}, constant = @Constant(intValue = 50), slice = @Slice(
        from = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setKineticSpeed(Lnet/createmod/ponder/api/scene/Selection;F)V", ordinal = 0),
        to = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setKineticSpeed(Lnet/createmod/ponder/api/scene/Selection;F)V", ordinal = 1)))
    private static int differentialcreate$loweringTime(int original) { return 60; }
    @ModifyConstant(method = {"intro", "level"}, constant = @Constant(intValue = 16))
    private static int differentialcreate$retractionTime(int original) { return 32; }
    @Inject(method = "infinite", at = @At("HEAD"))
    private static void differentialcreate$ocean(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        new CreateSceneBuilder(builder).addInstruction(scene -> PonderHoseSupply.ocean(scene.getWorld()));
    }
    @Inject(method = "intro", at = @At("HEAD"))
    private static void differentialcreate$fillSupply(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        new CreateSceneBuilder(builder).addInstruction(scene -> PonderHoseSupply.setup(scene.getWorld(), true));
    }
    @Inject(method = "level", at = @At("HEAD"))
    private static void differentialcreate$drainReceiver(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        new CreateSceneBuilder(builder).addInstruction(scene -> PonderHoseSupply.setup(scene.getWorld(), false));
    }
    @WrapOperation(method = "intro", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlock(Lnet/minecraft/core/BlockPos;Ljava/util/function/UnaryOperator;Z)V"))
    private static void differentialcreate$fillBeforeReversal(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        java.util.function.UnaryOperator<BlockState> action, boolean particles, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        // At 256 RPM the remaining source blocks take about 70 ticks. Reserve
        // that work in the native timeline instead of stopping its cursor.
        builder.idle(80);
        builder.addInstruction(new com.diliupengyun.differential_create.client.ponder.PonderAwait(scene -> {
            for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) for (int y = 1; y <= 2; y++)
                if (scene.getWorld().getFluidState(new BlockPos(x, y, z)).isEmpty()) return false;
            return true;
        }, 1200));
        // Reverse only after the following lowering operation has settled.
        // Otherwise extraction starts here, then stops again while the hose moves.
    }
    @WrapOperation(method = "intro", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;propagatePipeChange(Lnet/minecraft/core/BlockPos;)V"))
    private static void differentialcreate$deferReversalUpdate(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Operation<Void> original) {}

    @Inject(method = "intro", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setKineticSpeed(Lnet/createmod/ponder/api/scene/Selection;F)V", ordinal = 5, shift = At.Shift.AFTER))
    private static void differentialcreate$drainAfterLowering(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        // The physical hose coasts for about 73 ticks after releasing the crank.
        builder.idle(80);
        builder.addInstruction(new PonderAwait(scene ->
            scene.getWorld().getBlockEntity(new BlockPos(1, 5, 1)) instanceof HosePulleyBlockEntity hose
                && hose.getSpeed() == 0, 1200));
        var world = new CreateSceneBuilder(builder).world();
        var pump = new BlockPos(3, 2, 1);
        world.modifyBlock(pump, state -> state.setValue(PumpBlock.FACING, Direction.DOWN), true);
        world.propagatePipeChange(pump);
    }

    @Inject(method = "intro", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder;markAsFinished()V"))
    private static void differentialcreate$finishDraining(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        builder.idle(25);
        builder.addInstruction(new com.diliupengyun.differential_create.client.ponder.PonderAwait(scene -> {
            for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) for (int y = 1; y <= 2; y++)
                if (!scene.getWorld().getFluidState(new BlockPos(x, y, z)).isEmpty()) return false;
            return true;
        }, 1200));
    }
    @WrapOperation(method = {"intro", "level", "infinite"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static <T extends BlockEntity> void differentialcreate$noFakeWater(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        Class<T> type, Consumer<T> operation, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        if (com.simibubi.create.content.fluids.tank.FluidTankBlockEntity.class.isAssignableFrom(type)) {
            // The ocean example includes pipe priming before its first bucket.
            builder.idle(330);
            builder.addInstruction(new com.diliupengyun.differential_create.client.ponder.PonderAwait(scene ->
                scene.getWorld().getBlockEntity(pos) instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank
                    && tank.getTankInventory().getFluidAmount() >= 1000, 1200));
        }
        if (!com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity.class.isAssignableFrom(type)
            && !com.simibubi.create.content.fluids.tank.FluidTankBlockEntity.class.isAssignableFrom(type)) original.call(world, pos, type, operation);
    }
    @WrapOperation(method = "infinite", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setKineticSpeed(Lnet/createmod/ponder/api/scene/Selection;F)V"))
    private static void differentialcreate$reachOcean(CreateSceneBuilder.WorldInstructions world, Selection selection, float speed, Operation<Void> original,
        SceneBuilder builder, SceneBuildingUtil util) {
        if (speed == 0) {
            // Release before reaching the water: the shaft's remaining momentum
            // lowers the mouth to the native three-block depth as it coasts.
            builder.idle(10);
            original.call(world, selection, speed);
            builder.idle(110);
            builder.addInstruction(new PonderAwait(scene -> {
                if (!(scene.getWorld().getBlockEntity(new BlockPos(1, 3, 2)) instanceof HosePulleyBlockEntity pulley)
                    || pulley.getSpeed() != 0) return false;
                var handler = scene.getWorld().getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, new BlockPos(1, 3, 2), null);
                return handler instanceof HosePulleyFluidHandlerAccessor hose && !scene.getWorld().getFluidState(hose.differentialcreate$rootPosition().get()).isEmpty();
            }, 1200));
            return;
        }
        original.call(world, selection, speed);
    }
    @WrapOperation(method = "level", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;hideSection(Lnet/createmod/ponder/api/scene/Selection;Lnet/minecraft/core/Direction;)V"))
    private static void differentialcreate$waitForDraining(CreateSceneBuilder.WorldInstructions world, Selection selection, Direction direction, Operation<Void> original,
        SceneBuilder builder, SceneBuildingUtil util) {
        builder.idle(130);
        builder.addInstruction(new com.diliupengyun.differential_create.client.ponder.PonderAwait(scene -> {
            for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) for (int y = 2; y <= 3; y++)
                if (!scene.getWorld().getFluidState(new BlockPos(x, y, z)).isEmpty()) return false;
            return PonderHoseSupply.stock(scene.getWorld()) >= 1000;
        }, 1200));
        original.call(world, selection, direction);
    }
    @WrapOperation(method = "intro", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private static void differentialcreate$actualFilling(CreateSceneBuilder.WorldInstructions world, BlockPos pos, BlockState state, boolean neighbors, Operation<Void> original) {}
    @WrapOperation(method = "intro", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;destroyBlock(Lnet/minecraft/core/BlockPos;)V"))
    private static void differentialcreate$actualEmptying(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Operation<Void> original) {}
    @WrapOperation(method = "level", at = {
        @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V", ordinal = 1),
        @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V", ordinal = 2),
        @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V", ordinal = 3)})
    private static void differentialcreate$actualRefilling(CreateSceneBuilder.WorldInstructions world, BlockPos pos, BlockState state, boolean neighbors, Operation<Void> original,
        SceneBuilder builder, SceneBuildingUtil util) {
        if (pos.equals(new BlockPos(1, 1, 1))) {
            builder.idle(220);
            builder.addInstruction(new com.diliupengyun.differential_create.client.ponder.PonderAwait(scene -> {
                for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) for (int y = 1; y <= 3; y++)
                    if (scene.getWorld().getFluidState(new BlockPos(x, y, z)).isEmpty()) return false;
                return true;
            }, 1200));
        }
    }
    @WrapOperation(method = "level", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;destroyBlock(Lnet/minecraft/core/BlockPos;)V"))
    private static void differentialcreate$actualDraining(CreateSceneBuilder.WorldInstructions world, BlockPos pos, Operation<Void> original) {}
}
