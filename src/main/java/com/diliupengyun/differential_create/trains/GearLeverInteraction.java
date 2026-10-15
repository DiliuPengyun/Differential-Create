package com.diliupengyun.differential_create.trains;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class GearLeverInteraction extends MovingInteractionBehaviour {
    @Override public boolean handlePlayerInteraction(Player player, InteractionHand hand, BlockPos localPos, AbstractContraptionEntity entity) {
        if (!(entity instanceof CarriageContraptionEntity cce) || cce.getCarriage() == null) return false;
        if (!player.mayBuild() || entity.toGlobalVector(Vec3.atCenterOf(localPos), 1).distanceToSqr(player.position()) > 64) return false;
        if (!entity.level().isClientSide) {
            var train = cce.getCarriage().train;
            int gear = TrainMechanics.stepGear(TrainPhysics.state(train).gear, player.isShiftKeyDown() ? -1 : 1, true);
            TrainPhysics.shift(train, gear);
            player.displayClientMessage(Component.translatable("gui.differentialcreate.train.gear", GearLeverBlock.label(gear)), true);
        }
        return true;
    }
}
