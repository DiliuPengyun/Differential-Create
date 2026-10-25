package com.diliupengyun.differential_create.client.ponder;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.diliupengyun.differential_create.mixin.PonderChainGearshiftAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.chainDrive.ChainGearshiftBlockEntity;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;

import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Native scenes store separate demonstration assemblies beside each other in one template.
 * Active assemblies connect at their displayed locations, excluding unused template copies. */
final class PonderDisplayTopology {
    private record Pose(Vec3 position, Vec3 rotation, WorldSectionElement section) {}
    private record Signal(Vec3 position, int power) {}
    private final Map<KineticBlockEntity, Pose> poses = new LinkedHashMap<>();
    private final java.util.List<Signal> signals = new java.util.ArrayList<>();
    private final Map<KineticBlockEntity, Map<KineticBlockEntity, BlockPos>> neighbours = new HashMap<>();

    PonderDisplayTopology(PonderLevel level) {
        level.scene.forEachVisible(WorldSectionElement.class, section -> {
            var selection = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
            if (selection == null) return;
            var transform = new PoseStack();
            ((WorldSectionElementImpl) section).transformMS(transform, 1);
            for (var pos : selection) {
                var point = transform.last().pose().transformPosition(new Vector3f(pos.getX(), pos.getY(), pos.getZ()));
                // A vertical valve wheel spins about its shaft. Its block centre
                // and shaft connection stay fixed; its rotating corner does not.
                if (level.getBlockEntity(pos) instanceof com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity handle
                    && handle.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)
                        .getAxis().isVertical()
                    && section.getAnimatedRotation().x == 0 && section.getAnimatedRotation().z == 0)
                    point = transform.last().pose().transformPosition(new Vector3f(pos.getX() + .5f, pos.getY() + .5f, pos.getZ() + .5f))
                        .sub(.5f, .5f, .5f);
                var position = new Vec3(point);
                if (level.getBlockEntity(pos) instanceof KineticBlockEntity be)
                    poses.put(be, new Pose(position, section.getAnimatedRotation(), section));
                var state = level.getBlockState(pos);
                if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER))
                    signals.add(new Signal(position, state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER)));
            }
        });
        steamInstallation(level);
        for (var entry : poses.entrySet()) if (entry.getKey() instanceof ChainGearshiftBlockEntity gearshift) {
            int signal = 0;
            if (gearshift.getBlockState().getValue(com.simibubi.create.content.kinetics.chainDrive.ChainGearshiftBlock.POWERED)) {
                for (var wire : signals)
                    if (Math.abs(wire.position().distanceToSqr(entry.getValue().position()) - 1) < 1e-6)
                        signal = Math.max(signal, wire.power());
                // Binary scripted power also works when its lever is off-stage.
                if (signal == 0) signal = 15;
            }
            ((PonderChainGearshiftAccessor) gearshift).differentialcreate$setSignal(signal);
        }
    }

    /** SteamScenes reveals a completed boiler's cylinders one row at a time. The gear
     * stack is stored elsewhere in its template. Simulate that whole active assembly
     * before revealing it, without pulling in either of the other boiler examples. */
    private void steamInstallation(PonderLevel level) {
        if (!level.scene.getId().getNamespace().equals("create") || !level.scene.getId().getPath().equals("steam_engine")) return;
        level.scene.forEachVisible(WorldSectionElement.class, section -> {
            var selection = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
            if (selection == null) return;
            boolean medium = selection.test(new BlockPos(4, 4, 5));
            boolean large = selection.test(new BlockPos(4, 10, 3));
            if (medium) {
                for (var pos : new BlockPos[]{new BlockPos(1, 4, 6), new BlockPos(1, 5, 6), new BlockPos(1, 5, 5),
                    new BlockPos(1, 4, 7), new BlockPos(1, 5, 7)})
                    assemblyMember(level, section, pos, section.getAnimatedOffset());
            }
            if (large) {
                for (int y = 4; y <= 8; y++) {
                    if (y != 6) for (int z = 3; z <= 4; z++)
                        assemblyMember(level, section, new BlockPos(1, y, z), section.getAnimatedOffset());
                    assemblyMember(level, section, new BlockPos(1, y, 7), section.getAnimatedOffset().add(0, 0, -2));
                }
            }
        });
    }

    private void assemblyMember(PonderLevel level, WorldSectionElement section, BlockPos pos, Vec3 offset) {
        if (level.getBlockEntity(pos) instanceof KineticBlockEntity be)
            poses.putIfAbsent(be, new Pose(Vec3.atLowerCornerOf(pos).add(offset), Vec3.ZERO, section));
    }

    boolean contains(KineticBlockEntity be) { return poses.containsKey(be); }

    KineticBlockEntity shaftNeighbour(KineticBlockEntity from, net.minecraft.core.Direction face) {
        if (!contains(from)) return null;
        neighbours(from);
        var offset = BlockPos.ZERO.relative(face);
        for (var entry : neighbours.get(from).entrySet())
            if (entry.getValue().equals(offset)) return entry.getKey();
        return null;
    }

    Set<KineticBlockEntity> neighbours(KineticBlockEntity from) {
        return neighbours.computeIfAbsent(from, key -> {
            var result = new LinkedHashMap<KineticBlockEntity, BlockPos>();
            var a = poses.get(from);
            var locations = PonderKinetics.locations(from);
            for (var entry : poses.entrySet()) {
                var to = entry.getKey();
                if (from == to) continue;
                var b = entry.getValue();
                Vec3 delta = b.position().subtract(a.position());
                BlockPos original = to.getBlockPos().subtract(from.getBlockPos());
                if ((a.section() == b.section() && !a.rotation().equals(Vec3.ZERO))
                    || delta.distanceToSqr(Vec3.atLowerCornerOf(original)) < 1e-8) {
                    if (!locations.contains(to.getBlockPos())) continue;
                    result.put(to, original);
                } else {
                    // Cross-section contacts are made only once the displayed blocks align.
                    if (!a.rotation().equals(Vec3.ZERO) || !b.rotation().equals(Vec3.ZERO)) continue;
                    BlockPos difference = new BlockPos((int) Math.round(delta.x), (int) Math.round(delta.y), (int) Math.round(delta.z));
                    if (delta.distanceToSqr(Vec3.atLowerCornerOf(difference)) > 1e-8
                        || difference.equals(BlockPos.ZERO) || difference.distSqr(BlockPos.ZERO) > 2) continue;
                    result.put(to, difference);
                }
            }
            return result;
        }).keySet();
    }

    float connection(KineticBlockEntity from, KineticBlockEntity to) {
        neighbours(from);
        var difference = neighbours.get(from).get(to);
        if (difference == null) return 0;
        if (difference.equals(to.getBlockPos().subtract(from.getBlockPos())))
            return NetworkTopology.connection(from, to);
        // Query Create's rules at the displayed separation without moving the actual
        // template entities or replacing their render/physics identity.
        var positioned = (KineticBlockEntity) to.getType().create(from.getBlockPos().offset(difference), to.getBlockState());
        if (positioned == null) return 0;
        positioned.setLevel(to.getLevel());
        positioned.source = to.source == null ? null
            : positioned.getBlockPos().offset(to.source.subtract(to.getBlockPos()));
        if (positioned instanceof SpeedControllerBlockEntity controller && to instanceof SpeedControllerBlockEntity originalController)
            controller.targetSpeed.setValue(originalController.targetSpeed.getValue());
        if (positioned instanceof ChainGearshiftBlockEntity && to instanceof ChainGearshiftBlockEntity)
            ((PonderChainGearshiftAccessor) positioned).differentialcreate$setSignal(
                ((PonderChainGearshiftAccessor) to).differentialcreate$getSignal());
        return NetworkTopology.connection(from, positioned);
    }
}
