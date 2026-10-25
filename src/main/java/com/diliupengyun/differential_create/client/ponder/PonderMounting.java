package com.diliupengyun.differential_create.client.ponder;

import java.util.*;
import com.diliupengyun.differential_create.kinetics.ContraptionGeometry;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.level.PonderLevel;

/** Mounted mass belongs to visible scene sections, rather than an actuator's past examples. */
public final class PonderMounting {
    private record Mount(KineticBlockEntity actuator, boolean rotation, int axis,
                         Set<ContraptionGeometry.Cell> cells, Map<net.minecraft.core.BlockPos, ContraptionGeometry.Cell> templates, double ratio) {}
    private record Plan(boolean rotation, int axis, Map<Double, Set<ContraptionGeometry.Cell>> groups) {}
    private final Map<WorldSectionElement, Mount> mounts = new IdentityHashMap<>();
    private final Map<KineticBlockEntity, Plan> applied = new IdentityHashMap<>();

    public void bind(WorldSectionElement section, KineticBlockEntity actuator, boolean rotation, int axis,
                     Set<ContraptionGeometry.Cell> cells, double ratio) {
        mounts.put(section, new Mount(actuator, rotation, axis, Set.copyOf(cells), null, ratio));
        sync((PonderLevel) actuator.getLevel());
    }
    public void bindSection(WorldSectionElement section, KineticBlockEntity actuator, boolean rotation, int axis,
                            Map<net.minecraft.core.BlockPos, ContraptionGeometry.Cell> cells, double ratio) {
        mounts.put(section, new Mount(actuator, rotation, axis, Set.copyOf(cells.values()), Map.copyOf(cells), ratio));
        sync((PonderLevel) actuator.getLevel());
    }
    void release(WorldSectionElement section) {
        var old = mounts.remove(section);
        if (old != null) sync((PonderLevel) old.actuator().getLevel());
    }
    void reset() { mounts.clear(); applied.clear(); }
    public Map<KineticBlockEntity, List<WorldSectionElement>> owners() {
        var result = new IdentityHashMap<KineticBlockEntity, List<WorldSectionElement>>();
        mounts.forEach((section, mount) -> result.computeIfAbsent(mount.actuator(), ignored -> new ArrayList<>()).add(section));
        return result;
    }
    void sync(PonderLevel world) {
        var controllers = Collections.newSetFromMap(new IdentityHashMap<KineticBlockEntity, Boolean>());
        controllers.addAll(applied.keySet()); mounts.values().forEach(mount -> controllers.add(mount.actuator()));
        mounts.entrySet().removeIf(entry -> entry.getValue().actuator().isRemoved()
            || world.getBlockEntity(entry.getValue().actuator().getBlockPos()) != entry.getValue().actuator());
        var physics = PonderKinetics.of(world);
        for (var be : controllers) {
            var visible = mounts.entrySet().stream().filter(entry -> entry.getValue().actuator() == be
                && entry.getKey().isVisible() && !be.isRemoved() && world.getBlockEntity(be.getBlockPos()) == be
                && !entry.getKey().isEmpty()).toList();
            if (visible.isEmpty()) {
                physics.setAttachmentActive(be, false); applied.remove(be); continue;
            }
            var groups = new HashMap<Double, Set<ContraptionGeometry.Cell>>();
            for (var entry : visible) {
                var mount = entry.getValue(); var cells = groups.computeIfAbsent(mount.ratio(), ignored -> new HashSet<>());
                if (mount.templates() == null) cells.addAll(mount.cells());
                else mount.templates().forEach((pos, cell) -> {
                    if (!world.getBlockState(pos).isAir() && ((com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor) entry.getKey()).differentialcreate$selection().test(pos)) cells.add(cell);
                });
            }
            if (groups.values().stream().allMatch(Set::isEmpty)) { physics.setAttachmentActive(be, false); applied.remove(be); continue; }
            var example = visible.getFirst().getValue();
            var plan = new Plan(example.rotation(), example.axis(), groups);
            if (!plan.equals(applied.get(be))) {
                double inertia = 0, drag = 0;
                for (var group : groups.entrySet()) {
                    var shape = ContraptionGeometry.measure(group.getValue()); double ratio = group.getKey();
                    inertia += ratio * ratio * (plan.rotation() ? shape.rotationInertia()[plan.axis()] : shape.linearInertia());
                    drag += Math.pow(Math.abs(ratio), 3) * (plan.rotation() ? shape.rotationDrag()[plan.axis()] : shape.linearDrag(plan.axis()));
                }
                physics.attachment(world, be.getBlockPos(), inertia, 0, drag); applied.put(be, plan);
            }
            physics.setAttachmentActive(be, true);
        }
    }
}
