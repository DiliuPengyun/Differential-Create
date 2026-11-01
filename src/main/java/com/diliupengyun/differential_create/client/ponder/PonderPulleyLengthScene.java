package com.diliupengyun.differential_create.client.ponder;

import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchBlock;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;

/** Match the threshold lesson's indicator to its visible, shortened rope stroke. */
final class PonderPulleyLengthScene {
    static void tick(PonderLevel world) {
        if (world.scene == null || !world.scene.getId().getPath().equals("threshold_switch")) return;
        if (!(world.getBlockEntity(new BlockPos(2, 2, 3)) instanceof PulleyBlockEntity pulley)
            || pulley.offset <= 0) return;
        var pos = new BlockPos(1, 1, 3);
        var state = world.getBlockState(pos);
        if (!state.hasProperty(ThresholdSwitchBlock.LEVEL)) return;
        int level = Math.clamp((int) Math.floor(pulley.offset / 3 * 5 + 1e-4), 0, 5);
        if (state.getValue(ThresholdSwitchBlock.LEVEL) != level)
            world.setBlock(pos, state.setValue(ThresholdSwitchBlock.LEVEL, level), 2);
    }
}
