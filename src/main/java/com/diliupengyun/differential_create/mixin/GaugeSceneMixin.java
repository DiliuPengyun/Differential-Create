package com.diliupengyun.differential_create.mixin;

import java.util.function.Consumer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.KineticsScenes;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep the native gauge lesson; its two loads now actually cut a supplied workpiece. */
@Mixin(KineticsScenes.class)
public abstract class GaugeSceneMixin {
    @WrapOperation(method = "gauge", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private static void differentialcreate$workingLoad(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        BlockState state, boolean redraw, Operation<Void> original) {
        if (!AllBlocks.CRUSHING_WHEEL.has(state)) {
            original.call(world, pos, state, redraw);
            return;
        }
        original.call(world, pos, AllBlocks.MECHANICAL_SAW.getDefaultState()
            .setValue(BlockStateProperties.FACING, Direction.UP)
            .setValue(com.simibubi.create.content.kinetics.saw.SawBlock.AXIS_ALONG_FIRST_COORDINATE, true), redraw);
        world.modifyBlockEntity(pos, SawBlockEntity.class,
            saw -> saw.inventory.insertItem(0, new ItemStack(Items.OAK_LOG), false));
    }

    @WrapOperation(method = "gauge", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntityNBT(Lnet/createmod/ponder/api/scene/Selection;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static void differentialcreate$measuredDial(CreateSceneBuilder.WorldInstructions world, Selection selection,
        Class<? extends BlockEntity> type, Consumer<CompoundTag> edit, Operation<Void> original) {
        if (type != StressGaugeBlockEntity.class) original.call(world, selection, type, edit);
    }

    @WrapOperation(method = "gauge", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;toggleRedstonePower(Lnet/createmod/ponder/api/scene/Selection;)V"))
    private static void differentialcreate$demonstrateMeasuredSignal(CreateSceneBuilder.WorldInstructions world,
        Selection selection, Operation<Void> original) {
        // The speedometer uses this shared method too. Both comparators follow
        // their instruments; only the load lesson has these two processing saws.
        for (int x : new int[]{4, 5})
            world.modifyBlockEntity(new BlockPos(x, 1, 3), SawBlockEntity.class,
                saw -> saw.inventory.insertItem(0, new ItemStack(Items.OAK_LOG), false));
    }
}
