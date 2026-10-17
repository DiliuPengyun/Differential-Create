package com.diliupengyun.differential_create.client.ponder;

import java.util.function.UnaryOperator;

import com.diliupengyun.differential_create.client.SteamHoseGeometry;
import com.diliupengyun.differential_create.client.SteamHoseRenderer;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.element.AnimatedSceneElementBase;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** A saved hose connection follows both displayed endpoints, including carriage movement. */
public final class PonderHoseElement extends AnimatedSceneElementBase {
    private final BlockPos enginePos;

    public PonderHoseElement(BlockPos enginePos) {
        this.enginePos = enginePos.immutable();
        forceApplyFade(1);
    }

    public BlockPos enginePos() { return enginePos; }

    public SteamHoseGeometry.Connection connection(PonderLevel world, float partial) {
        var engine = world.getBlockEntity(enginePos);
        if (!(engine instanceof SteamHoseLinks.EngineLink link) || link.differentialcreate$hose() == null) return null;
        var tank = SteamHoseLinks.tank(engine);
        if (tank == null) return null;
        BlockPos tankPos = enginePos.offset(link.differentialcreate$hose());
        var from = transform(world, enginePos, partial);
        var to = transform(world, tankPos, partial);
        if (from == null || to == null) return null;
        var local = SteamHoseGeometry.connect(engine.getBlockState(), Vec3.atCenterOf(enginePos),
            SteamHoseRenderer.bounds(tank), Vec3.atCenterOf(tankPos), link.differentialcreate$port());
        return SteamHoseGeometry.between(local.engine().transform(from), local.tank().transform(to));
    }

    private static UnaryOperator<Vec3> transform(PonderLevel world, BlockPos pos, float partial) {
        WorldSectionElement[] owner = {null};
        world.scene.forEachVisible(WorldSectionElement.class, section -> {
            var selection = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
            if (selection != null && selection.test(pos)) owner[0] = section;
        });
        if (owner[0] == null) return null;
        var pose = new PoseStack();
        ((WorldSectionElementImpl) owner[0]).transformMS(pose, partial);
        var matrix = pose.last().pose();
        return point -> new Vec3(matrix.transformPosition(new Vector3f((float) point.x, (float) point.y, (float) point.z)));
    }

    @Override
    protected void renderFirst(PonderLevel world, MultiBufferSource buffer, GuiGraphics graphics, float fade, float partial) {
        var connection = connection(world, partial);
        if (connection != null) SteamHoseRenderer.renderPonder(graphics.pose(), buffer, connection);
    }
}
