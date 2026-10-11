package com.diliupengyun.differential_create.mixin;

import org.apache.commons.lang3.tuple.Pair;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DeployerFakePlayer.class)
public interface DeployerPlayerAccessor {
    @Accessor("blockBreakingProgress") Pair<BlockPos, Float> differentialcreate$breaking();
    @Accessor("blockBreakingProgress") void differentialcreate$breaking(Pair<BlockPos, Float> progress);
}
