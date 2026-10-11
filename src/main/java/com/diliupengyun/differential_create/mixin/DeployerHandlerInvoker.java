package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.kinetics.deployer.DeployerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DeployerHandler.class)
public interface DeployerHandlerInvoker {
    @Invoker("shouldActivate") static boolean differentialcreate$shouldActivate(ItemStack item, Level world, BlockPos pos, Direction facing) {
        throw new AssertionError();
    }
}
