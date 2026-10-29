package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.ElasticKinetics;
import com.diliupengyun.differential_create.kinetics.MachiningClock;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

final class TickStepTests {
    static void run(GameTestHelper h) {
        var pos = new BlockPos(3, 3, 3);
        h.setBlock(pos, AllBlocks.CREATIVE_MOTOR.getDefaultState());
        CreativeMotorBlockEntity motor = h.getBlockEntity(pos);
        motor.generatedSpeed.setValue(64);
        h.runAfterDelay(10, () -> {
            var clock = h.getLevel().getServer().tickRateManager();
            var state = ElasticKinetics.state(motor);
            h.assertTrue(Math.abs(state.physicalSpeed) > 1, "fixture motor has accelerated normally");
            try {
                clock.setFrozen(true); clock.tick();
                double parked = state.angle;
                MachiningClock.advance(h.getLevel());
                h.assertTrue(state.angle == parked, "frozen machinery retains its phase");
                clock.stepGameIfPaused(1); clock.tick();
                h.assertTrue(clock.frozenTicksToRun() == 0 && clock.runsNormally(), "exercise the final requested step");
                MachiningClock.advance(h.getLevel());
                h.assertTrue(state.angle != parked, "the last requested step must advance physical shaft travel");
                double stepped = state.angle;
                clock.tick(); MachiningClock.advance(h.getLevel());
                h.assertTrue(state.angle == stepped, "no extra physical step after the budget is exhausted");
            } finally { clock.setFrozen(false); clock.tick(); }
            h.succeed();
        });
    }
}
