package com.diliupengyun.differential_create.client.ponder;

import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.TickingInstruction;
import net.minecraft.core.BlockPos;

/** A stroke command bounds travel; only accepted shaft work can move the displayed head. */
public final class PonderShaftAnimation extends TickingInstruction {
    private final BlockPos pos;
    private final float request;
    private final boolean hand;
    private float initial;
    private double travel;
    private boolean contacted;
    public PonderShaftAnimation(BlockPos pos, float request, int ticks, boolean hand) {
        super(false, Math.max(1, ticks)); this.pos = pos.immutable(); this.request = request; this.hand = hand;
    }
    @Override protected void firstTick(PonderScene scene) {
        super.firstTick(scene); travel = 0;
        contacted = false;
        var be = scene.getWorld().getBlockEntity(pos);
        initial = hand && be instanceof DeployerBlockEntity deployer ? deployer.getHandOffset(1)
            : be instanceof PulleyBlockEntity pulley ? pulley.offset : 0;
        if (hand && be instanceof DeployerBlockEntity deployer && deployer.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING) != net.minecraft.core.Direction.DOWN)
            PonderHandActions.phase(deployer, request > 0 ? "EXPANDING" : "RETRACTING");
    }
    @Override public void tick(PonderScene scene) {
        super.tick(scene);
        travel += PonderKinetics.of(scene.getWorld()).workTravel(pos) / (hand ? 500 : 512);
        float value = (float) (initial + Math.copySign(Math.min(Math.abs(request), travel), request));
        var be = scene.getWorld().getBlockEntity(pos);
        if (hand && be instanceof DeployerBlockEntity deployer) {
            deployer.setAnimatedOffset(value);
            if (deployer.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING) != net.minecraft.core.Direction.DOWN) {
                if (!contacted && request > 0 && travel >= Math.abs(request)) { contacted = true; PonderHandActions.contact(deployer); }
                if (request < 0 && travel >= Math.abs(request)) PonderHandActions.phase(deployer, "WAITING");
            }
        }
        else if (be instanceof PulleyBlockEntity pulley) pulley.animateOffset(value);
    }
}
