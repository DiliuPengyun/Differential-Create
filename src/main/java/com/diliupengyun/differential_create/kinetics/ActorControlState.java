package com.diliupengyun.differential_create.kinetics;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsMovement;
import net.minecraft.world.item.ItemStack;

/** Native all/type selection for previews, checked against Create's real moving interaction. */
public final class ActorControlState {
    private ActorControlState() {}

    public static boolean disabled(Contraption contraption, ItemStack filter) {
        return contraption.isActorTypeDisabled(ItemStack.EMPTY) || contraption.isActorTypeDisabled(filter);
    }

    public static void toggle(Contraption contraption, ItemStack filter) {
        boolean disable = true, invert = false;
        var disabled = contraption.getDisabledActors();
        for (var iterator = disabled.iterator(); iterator.hasNext();) {
            var present = iterator.next();
            boolean same = ContraptionControlsMovement.isSameFilter(present, filter);
            if (present.isEmpty()) {
                iterator.remove(); disable = false;
                if (!same) invert = true;
                continue;
            }
            if (!same) continue;
            iterator.remove(); disable = false; break;
        }
        if (invert) for (var actor : contraption.getActors()) {
            var behaviour = MovementBehaviour.REGISTRY.get(actor.getLeft().state());
            var type = behaviour == null ? null : behaviour.canBeDisabledVia(actor.getRight());
            if (type == null || ContraptionControlsMovement.isSameFilter(type, filter) || contraption.isActorTypeDisabled(type)) continue;
            disabled.add(type);
        }
        if (filter.isEmpty()) disabled.clear();
        if (disable) disabled.add(filter.copy());
        contraption.setActorsActive(filter, !disable);
    }
}
