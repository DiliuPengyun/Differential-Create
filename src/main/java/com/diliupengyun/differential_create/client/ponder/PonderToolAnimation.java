package com.diliupengyun.differential_create.client.ponder;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.actors.harvester.HarvesterBlockEntity;
import com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour;
import com.simibubi.create.content.contraptions.actors.roller.RollerBlock;
import com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity;
import com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.drill.DrillMovementBehaviour;
import com.simibubi.create.content.kinetics.saw.SawMovementBehaviour;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/** Moving tool phase belongs to its assembly, including while its speed is zero. */
public final class PonderToolAnimation {
    private double previous, angle;

    void tick(MovementContext context) {
        var behaviour = MovementBehaviour.REGISTRY.get(context.state);
        float speed = context.getAnimationSpeed();
        if ((behaviour instanceof DrillMovementBehaviour || behaviour instanceof SawMovementBehaviour
            || behaviour instanceof HarvesterMovementBehaviour) && !behaviour.isActive(context)) speed = 0;
        if (behaviour instanceof RollerMovementBehaviour) {
            if (context.contraption.stalled) speed = 0;
            else if (VecHelper.isVecPointingTowards(context.relativeMotion, context.state.getValue(RollerBlock.FACING).getOpposite())) speed = -speed;
        }
        if (behaviour instanceof HarvesterMovementBehaviour && context.contraption.stalled) speed = 0;
        previous = angle;
        // MovementContext reports degrees/second, not the RPM used by stationary shafts.
        angle += speed / 20d;
        if (context.world.getBlockEntity(context.localPos) instanceof HarvesterBlockEntity harvester) harvester.setAnimatedSpeed(speed);
        if (context.world.getBlockEntity(context.localPos) instanceof RollerBlockEntity roller) roller.setAnimatedSpeed(speed);
    }

    double angle(float partialTicks) { return net.minecraft.util.Mth.lerp(partialTicks, previous, angle); }

    /** Use the native blade pivot and facing, replacing only the time-times-speed phase. */
    public static boolean transform(BlockEntity be, float partialTicks, Direction facing, SuperByteBuffer buffer, Vec3 pivot) {
        if (!(be.getLevel() instanceof PonderLevel world)) return false;
        var angle = PonderSimulation.of(world).mountedToolAngle(be.getBlockPos(), partialTicks);
        if (angle == null) return false;
        var offset = new Vec3(0, pivot.y / 16, pivot.z / 16);
        buffer.rotateCentered(AngleHelper.rad(AngleHelper.horizontalAngle(facing)), Direction.UP)
            .translate(offset.x, offset.y, offset.z)
            .rotate(AngleHelper.rad((float) (angle % 360)), Direction.WEST)
            .translate(-offset.x, -offset.y, -offset.z);
        return true;
    }
}
