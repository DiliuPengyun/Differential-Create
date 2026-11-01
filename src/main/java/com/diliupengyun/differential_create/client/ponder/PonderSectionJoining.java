package com.diliupengyun.differential_create.client.ponder;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import net.createmod.ponder.api.element.WorldSectionElement;

/** Incoming blocks already belong to the moving frame during their fade-in. */
public final class PonderSectionJoining {
    private final Map<WorldSectionElement, WorldSectionElement> parents = new IdentityHashMap<>();

    public void add(WorldSectionElement section, WorldSectionElement parent) { parents.put(section, parent); }
    public void reset() { parents.clear(); }

    public void finish() {
        parents.keySet().removeIf(section -> !section.isVisible());
        Set<WorldSectionElement> applied = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var section : parents.keySet()) apply(section, applied);
    }

    private void apply(WorldSectionElement section, Set<WorldSectionElement> applied) {
        var parent = parents.get(section);
        if (parent == null || !applied.add(section)) return;
        apply(parent, applied);
        var source = (PonderWorldSectionAccessor) parent;
        var target = (PonderWorldSectionAccessor) section;
        section.setCenterOfRotation(source.differentialcreate$centre());
        section.stabilizeRotation(source.differentialcreate$stabilization());
        section.setAnimatedRotation(parent.getAnimatedRotation(), false);
        section.setAnimatedOffset(parent.getAnimatedOffset(), false);
        // Copy both endpoints, including the very first fade-in frame. Keeping the
        // template's old pose would still detach the block between server ticks.
        target.differentialcreate$previousRotation(source.differentialcreate$previousRotation());
        target.differentialcreate$previousOffset(source.differentialcreate$previousOffset());
    }
}
