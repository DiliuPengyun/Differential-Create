package com.diliupengyun.differential_create.trains;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class GearLeverBlock extends Block {
    public static final IntegerProperty GEAR = IntegerProperty.create("gear", 0, TrainMechanics.REVERSE_GEAR);
    public static final MapCodec<GearLeverBlock> CODEC = simpleCodec(GearLeverBlock::new);
    private static final VoxelShape SHAPE = box(4, 0, 0, 12, 14, 16);
    public GearLeverBlock(Properties properties) { super(properties.noOcclusion()); registerDefaultState(defaultBlockState().setValue(GEAR, 1)); }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(GEAR); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.mayBuild()) return InteractionResult.PASS;
        if (!level.isClientSide) {
            int gear = TrainMechanics.stepGear(state.getValue(GEAR), player.isShiftKeyDown() ? -1 : 1, true);
            level.setBlockAndUpdate(pos, state.setValue(GEAR, gear));
            player.displayClientMessage(Component.translatable("gui.differentialcreate.train.gear", label(gear)), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    public static String label(int gear) { return gear == 0 ? "N" : gear == TrainMechanics.REVERSE_GEAR ? "R" : Integer.toString(gear); }
}
