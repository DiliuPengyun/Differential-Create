package com.diliupengyun.differential_create.client;

import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamJetParticleData;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.phys.Vec3;

/** Continuous bypass exhaust using the same steam jet as Create's cylinder exhaust. */
public final class SteamExhaustParticles {
    private SteamExhaustParticles() {}

    public static void tick(SteamEngineBlockEntity engine, SteamExhaustAnimation animation) {
        var shaft = engine.getShaft();
        Float vent = shaft == null || !shaft.isPoweredBy(engine.getBlockPos()) ? null : ClientNetworkSpeeds.steamVentOf(shaft);
        int count = animation.advance(vent == null ? 0 : vent);
        var level = engine.getLevel();
        if (count == 0 || level == null) return;
        var facing = SteamEngineBlock.getFacing(engine.getBlockState());
        for (int i = 0; i < count; i++) {
            // Match the existing outlet orientation for wall, floor and ceiling installations.
            Vec3 offset = new Vec3(0, 0, 1).add(VecHelper.offsetRandomly(Vec3.ZERO, level.random, 1)
                .multiply(1, 1, 0).normalize().scale(.5));
            offset = VecHelper.rotate(offset, AngleHelper.verticalAngle(facing), Axis.X);
            offset = VecHelper.rotate(offset, AngleHelper.horizontalAngle(facing), Axis.Y);
            Vec3 outlet = offset.scale(.5).add(Vec3.atCenterOf(engine.getBlockPos()));
            Vec3 direction = offset.subtract(Vec3.atLowerCornerOf(facing.getNormal()).scale(.75));
            level.addParticle(new SteamJetParticleData(1), outlet.x, outlet.y, outlet.z,
                direction.x, direction.y, direction.z);
        }
    }
}
