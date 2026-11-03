package com.diliupengyun.differential_create.client.ponder;

import com.mojang.blaze3d.vertex.PoseStack;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.InputWindowElement;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** A click cue follows the same interpolated pose as its moving button. */
public final class PonderAttachedControls extends InputWindowElement {
    private final ElementLink<WorldSectionElement> owner;
    private final BlockPos button;

    public PonderAttachedControls(ElementLink<WorldSectionElement> owner, BlockPos button) {
        super(Vec3.ZERO, Pointing.DOWN);
        this.owner = owner; this.button = button.immutable();
        builder().rightClick();
    }

    public BlockPos button() { return button; }
    public Vec3 anchor(PonderScene scene, float partialTicks) {
        var section = scene.resolve(owner);
        var local = Vec3.atLowerCornerOf(button).add(.5, .85, .5);
        if (section == null) return local;
        var pose = new PoseStack();
        ((WorldSectionElementImpl) section).transformMS(pose, partialTicks);
        return new Vec3(pose.last().pose().transformPosition(local.toVector3f()));
    }

    @Override public void render(PonderScene scene, PonderUI screen, GuiGraphics graphics, float partialTicks, float fade) {
        var origin = scene.getTransform().sceneToScreen(Vec3.ZERO, partialTicks);
        var point = scene.getTransform().sceneToScreen(anchor(scene, partialTicks), partialTicks);
        graphics.pose().pushPose();
        graphics.pose().translate(point.x - origin.x, point.y - origin.y, 0);
        super.render(scene, screen, graphics, partialTicks, fade);
        graphics.pose().popPose();
    }
}
