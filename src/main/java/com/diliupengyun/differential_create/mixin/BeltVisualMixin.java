package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.BeltScrollPhase;
import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltVisual;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Re-anchors the scrolling belt surface to the same accumulated angle as the
 * pulley. The shader's speed × absolute render time phase jumps whenever the
 * finite-torque solver changes speed.
 */
@Mixin(BeltVisual.class)
public abstract class BeltVisualMixin extends KineticBlockEntityVisual<BeltBlockEntity> {
	@Unique private double differentialcreate$phase = Double.NaN;
	@Unique private float differentialcreate$shaderSpeed;

	protected BeltVisualMixin(VisualizationContext context, BeltBlockEntity be, float partialTick) {
		super(context, be, partialTick);
	}

	@ModifyArg(method = "setup(Lcom/simibubi/create/content/processing/burner/ScrollInstance;ZLnet/createmod/catnip/render/SpriteShiftEntry;)Lcom/simibubi/create/content/processing/burner/ScrollInstance;",
		at = @At(value = "INVOKE",
			target = "Lcom/simibubi/create/content/processing/burner/ScrollInstance;speed(FF)Lcom/simibubi/create/content/processing/burner/ScrollInstance;"),
		index = 1)
	private float differentialcreate$rememberScrollSpeed(float speed) {
		differentialcreate$phase = BeltScrollPhase.visualRepeats(blockEntity);
		differentialcreate$shaderSpeed = speed;
		return speed;
	}

	@ModifyArg(method = "setup(Lcom/simibubi/create/content/processing/burner/ScrollInstance;ZLnet/createmod/catnip/render/SpriteShiftEntry;)Lcom/simibubi/create/content/processing/burner/ScrollInstance;",
		at = @At(value = "INVOKE",
			target = "Lcom/simibubi/create/content/processing/burner/ScrollInstance;offset(FF)Lcom/simibubi/create/content/processing/burner/ScrollInstance;"),
		index = 1)
	private float differentialcreate$anchorScrollOffset(float offset) {
		if (Double.isNaN(differentialcreate$phase)) return offset;
		double anchor = differentialcreate$phase - differentialcreate$shaderSpeed * ClientKineticAngles.renderTick();
		anchor -= Math.floor(anchor);
		return (float) (offset + anchor);
	}
}
