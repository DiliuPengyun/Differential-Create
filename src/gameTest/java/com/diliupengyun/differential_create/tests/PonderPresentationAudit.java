package com.diliupengyun.differential_create.tests;

import java.lang.reflect.Field;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.IRotate;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.AnimatedSceneElementBase;
import net.createmod.ponder.foundation.element.InputWindowElement;
import net.createmod.ponder.foundation.element.TextWindowElement;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Check displayed machinery across the whole lesson, including camera turns and moving cargo. */
final class PonderPresentationAudit {
    private static final Field FADE;
    static {
        try { FADE = AnimatedSceneElementBase.class.getDeclaredField("fade"); FADE.setAccessible(true); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private record Point(BlockPos block, Vec3 position) {}
    private record Box(double x, double y, double width, double height) {
        boolean overlaps(Box other) {
            return Math.min(x + width, other.x + other.width) - Math.max(x, other.x) > 2
                && Math.min(y + height, other.y + other.height) - Math.max(y, other.y) > 2;
        }
    }
    private static final java.util.Map<String, Field> OVERLAY_FIELDS = new java.util.HashMap<>();

    static void sample(PonderScene scene) {
        if (!scene.getId().getNamespace().equals("differentialcreate") || scene.getCurrentTime() % 5 != 0) return;
        var points = new java.util.ArrayList<Point>();
        scene.forEachVisible(WorldSectionElement.class, section -> {
            try { if (((LerpedFloat) FADE.get(section)).getValue(1) < .999f) return; }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            var pose = new PoseStack();
            ((WorldSectionElementImpl) section).transformMS(pose, 1);
            for (var pos : ((PonderWorldSectionAccessor) section).differentialcreate$selection()) {
                var state = scene.getWorld().getBlockState(pos);
                // The base plate is background; input gears at the same height are machinery.
                if (state.isAir() || pos.getY() == 0 && !(state.getBlock() instanceof IRotate)) continue;
                for (double x : new double[]{0, 1}) for (double y : new double[]{0, 1}) for (double z : new double[]{0, 1})
                    add(points, pose, pos, Vec3.atLowerCornerOf(pos).add(x, y, z));
                if (AllBlocks.MECHANICAL_PRESS.has(state))
                    for (double x : new double[]{0, 1}) for (double z : new double[]{0, 1})
                        add(points, pose, pos, Vec3.atLowerCornerOf(pos).add(x, 1.75, z));
                double radius = AllBlocks.FLYWHEEL.has(state) ? 1.5
                    : AllBlocks.CRUSHING_WHEEL.has(state) ? 1.1 : AllBlocks.LARGE_COGWHEEL.has(state) ? .96 : 0;
                if (radius > 0 && state.getBlock() instanceof IRotate rotate) for (int i = 0; i < 32; i++) {
                    double angle = i * Math.PI / 16, a = radius * Math.sin(angle), b = radius * Math.cos(angle);
                    var radial = switch (rotate.getRotationAxis(state)) {
                        case X -> new Vec3(0, a, b);
                        case Y -> new Vec3(a, 0, b);
                        case Z -> new Vec3(a, b, 0);
                    };
                    add(points, pose, pos, Vec3.atCenterOf(pos).add(radial));
                }
            }
        });
        for (var viewport : new int[][]{{480, 264}, {426, 240}, {600, 450}}) {
            // Use an independent transform so probing a viewport cannot change the live UI.
            var transform = scene.new SceneTransform();
            transform.xRotation.startWithValue(scene.getTransform().xRotation.getValue(1));
            transform.yRotation.startWithValue(scene.getTransform().yRotation.getValue(1));
            transform.updateScreenParams(viewport[0], viewport[1], 0);
            overlays(scene, transform, viewport[0], viewport[1]);
            for (var point : points) {
                var screen = transform.sceneToScreen(point.position(), 1);
                if (screen.x < 8 || screen.x > viewport[0] - 8 || screen.y < 8 || screen.y > viewport[1] - 40)
                    throw new AssertionError("Machinery leaves the usable Ponder viewport in " + scene.getId()
                        + " at tick " + scene.getCurrentTime() + " " + viewport[0] + "x" + viewport[1]
                        + ": " + point.block() + " -> " + screen.x + ", " + screen.y);
            }
        }
    }

    /** Match native speech-box layout: distinct operator actions must remain readable. */
    private static void overlays(PonderScene scene, PonderScene.SceneTransform transform, int width, int height) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        var controls = new java.util.ArrayList<Box>();
        var captions = new java.util.ArrayList<Box>();
        scene.forEachVisible(InputWindowElement.class, input -> {
            if (input.getClass() != InputWindowElement.class || input.getFade(1) < .5f) return;
            var pos = transform.sceneToScreen((Vec3) field(input, "sceneSpace"), 1);
            var key = (net.minecraft.resources.ResourceLocation) field(input, "key");
            int w = key == null ? 0 : font.width(net.createmod.ponder.foundation.PonderIndex.getLangAccess().getShared(key));
            if (field(input, "icon") != null) w += 24;
            if (!((net.minecraft.world.item.ItemStack) field(input, "item")).isEmpty()) w += 24;
            var direction = (net.createmod.catnip.math.Pointing) field(input, "direction");
            double fade = 10 * (1 - input.getFade(1));
            double x = pos.x, y = pos.y;
            switch (direction) {
                case DOWN -> { x -= w / 2; y -= 34 + fade; }
                case UP -> { x -= w / 2; y += 10 + fade; }
                case LEFT -> { x += 10 + fade; y -= 12; }
                case RIGHT -> { x -= w + 10 + fade; y -= 12; }
            }
            controls.add(new Box(x, y, w, 24));
        });
        scene.forEachVisible(TextWindowElement.class, text -> {
            if (text.getFade(1) < .5f) return;
            var anchor = (Vec3) field(text, "vec");
            var pos = anchor == null ? new net.minecraft.world.phys.Vec2(width / 2f, (height - 200) / 2f + (int) field(text, "y") - 8)
                : transform.sceneToScreen(anchor, 1);
            double yDiff = (height / 2f - pos.y - 10) / 100f;
            double targetX = width * net.minecraft.util.Mth.lerp(yDiff * yDiff, .75, .625);
            if ((boolean) field(text, "nearScene")) targetX = Math.min(targetX, pos.x + 50);
            if (transform.xRotation.settled() && transform.yRotation.settled()) targetX = (int) targetX;
            String value = ((java.util.function.Supplier<?>) field(text, "textGetter")).get().toString();
            var lines = font.getSplitter().splitLines(value, (int) Math.min(width - targetX, 180), net.minecraft.network.chat.Style.EMPTY);
            int w = lines.stream().mapToInt(font::width).max().orElse(0);
            captions.add(new Box(targetX - 10, pos.y + 3, w, font.wordWrapHeight(value, w) - 1));
        });
        for (int i = 0; i < controls.size(); i++) {
            var box = controls.get(i);
            if (captions.stream().anyMatch(box::overlaps)
                || controls.subList(i + 1, controls.size()).stream().anyMatch(box::overlaps))
                throw new AssertionError("Overlapping operator hints/captions in " + scene.getId()
                    + " at tick " + scene.getCurrentTime() + " " + width + "x" + height + ": " + box);
        }
    }

    private static Object field(Object object, String name) {
        var type = object instanceof InputWindowElement ? InputWindowElement.class : TextWindowElement.class;
        try {
            var field = OVERLAY_FIELDS.computeIfAbsent(type.getName() + "." + name, ignored -> {
                try { var result = type.getDeclaredField(name); result.setAccessible(true); return result; }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            });
            return field.get(object);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static void add(java.util.List<Point> points, PoseStack pose, BlockPos block, Vec3 position) {
        points.add(new Point(block, new Vec3(pose.last().pose().transformPosition(position.toVector3f()))));
    }
}
