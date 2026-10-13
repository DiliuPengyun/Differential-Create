package com.diliupengyun.differential_create.mixin;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.fluids.tank.BoilerData;
import com.simibubi.create.foundation.utility.CreateLang;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keep boiler diagnostics and the insufficient-water hint; replace only obsolete stress capacity. */
@Mixin(BoilerData.class)
public abstract class BoilerDataMixin {
	@org.spongepowered.asm.mixin.Unique private int differentialcreate$previousEngines;
	@org.spongepowered.asm.mixin.Unique private int differentialcreate$previousWhistles;
	@org.spongepowered.asm.mixin.injection.Inject(method = "evaluate", at = @At("HEAD"))
	private void differentialcreate$beforeEvaluation(com.simibubi.create.content.fluids.tank.FluidTankBlockEntity controller,
		org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		var self = (BoilerData) (Object) this;
		differentialcreate$previousEngines = self.attachedEngines;
		differentialcreate$previousWhistles = self.attachedWhistles;
	}
	@org.spongepowered.asm.mixin.injection.Inject(method = "evaluate", at = @At("RETURN"), cancellable = true)
	private void differentialcreate$remoteEngines(com.simibubi.create.content.fluids.tank.FluidTankBlockEntity controller,
		org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		var self = (BoilerData) (Object) this;
		if (self.attachedEngines > 0) self.attachedEngines -= com.diliupengyun.differential_create.trains.SteamHoseLinks.overriddenDirectCount(controller);
		self.attachedEngines += com.diliupengyun.differential_create.trains.SteamHoseLinks.remoteCount(controller);
		cir.setReturnValue(self.attachedEngines != differentialcreate$previousEngines || self.attachedWhistles != differentialcreate$previousWhistles);
	}
	@ModifyVariable(method = "addToGoggleTooltip", at = @At("STORE"), ordinal = 0)
	private double differentialcreate$peakPower(double original, List<Component> tooltip, boolean sneaking, int size) {
		return SourcePhysics.steamPeakPower(BlockStressValues.getCapacity(AllBlocks.STEAM_ENGINE.get()),
			(BoilerData) (Object) this, size);
	}

	@Redirect(method = "addToGoggleTooltip", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/foundation/utility/CreateLang;translate(Ljava/lang/String;[Ljava/lang/Object;)Lnet/createmod/catnip/lang/LangBuilder;"))
	private LangBuilder differentialcreate$powerLabel(String key, Object[] args) {
		return key.equals("tooltip.capacityProvided")
			? CreateLang.builder().add(Component.translatable("gui.differentialcreate.boiler.peak_power"))
			: CreateLang.translate(key, args);
	}
}
