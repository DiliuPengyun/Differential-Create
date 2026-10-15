package com.diliupengyun.differential_create.mixin;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.BoilerAdmission;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.diliupengyun.differential_create.kinetics.SteamBoilerWater;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.fluids.tank.BoilerData;
import com.simibubi.create.foundation.utility.CreateLang;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Instant paid steam supply and shared power diagnostics. */
@Mixin(BoilerData.class)
public abstract class BoilerDataMixin implements BoilerAdmission {
	@org.spongepowered.asm.mixin.Unique private double differentialcreate$admission = 1;
	@org.spongepowered.asm.mixin.Unique private double differentialcreate$demand;
	@org.spongepowered.asm.mixin.Unique private double differentialcreate$waterFraction;
	@org.spongepowered.asm.mixin.Unique private final SteamBoilerWater.State differentialcreate$waterState = new SteamBoilerWater.State();
	@Override public double differentialcreate$demand() { return differentialcreate$demand; }
	@Override public double differentialcreate$waterFraction() { return differentialcreate$waterFraction; }
	@Override public SteamBoilerWater.State differentialcreate$waterState() { return differentialcreate$waterState; }
	@Override public void differentialcreate$supply(double demand, double fraction) {
		differentialcreate$demand = Double.isFinite(demand) ? Math.max(0, demand) : 0;
		differentialcreate$waterFraction = Double.isFinite(fraction) ? Math.clamp(fraction, 0, 1) : 0;
	}
	@Override public double differentialcreate$admission() { return differentialcreate$admission; }
	@Override public void differentialcreate$admission(double opening) {
		differentialcreate$admission = Double.isFinite(opening) ? Math.clamp(opening, 0, 1) : 0;
	}
	@org.spongepowered.asm.mixin.injection.Inject(method = "clear", at = @At("RETURN"))
	private void differentialcreate$clearSupply(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		SteamBoilerWater.reset((BoilerData) (Object) this);
	}
	@org.spongepowered.asm.mixin.injection.Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$worldAdmission(com.simibubi.create.content.fluids.tank.FluidTankBlockEntity controller,
		org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (controller.getLevel() == null || controller.getLevel().isClientSide || controller.isVirtual()) return;
		SteamBoilerWater.tick(controller);
		ci.cancel();
	}
	@org.spongepowered.asm.mixin.injection.Inject(method = "read", at = @At("HEAD"))
	private void differentialcreate$readAdmission(CompoundTag tag, int size,
		org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		differentialcreate$admission(tag.contains(BoilerAdmission.KEY) ? tag.getDouble(BoilerAdmission.KEY) : 1);
		differentialcreate$supply(tag.getDouble(BoilerAdmission.DEMAND_KEY), tag.getDouble(BoilerAdmission.FRACTION_KEY));
	}
	@org.spongepowered.asm.mixin.injection.Inject(method = "write", at = @At("RETURN"))
	private void differentialcreate$writeAdmission(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<CompoundTag> cir) {
		var tag = cir.getReturnValue();
		tag.putDouble(BoilerAdmission.KEY, differentialcreate$admission);
		tag.putDouble(BoilerAdmission.DEMAND_KEY, differentialcreate$demand);
		tag.putDouble(BoilerAdmission.FRACTION_KEY, differentialcreate$waterFraction);
	}
	@org.spongepowered.asm.mixin.injection.Inject(method = "getMaxHeatLevelForWaterSupply", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$admittedWater(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
		var self = (BoilerData) (Object) this;
		cir.setReturnValue(differentialcreate$waterFraction > 0 ? (int) Math.min(18,
			Mth.ceil(Math.max(self.activeHeat, self.passiveHeat ? 1 : 0) * differentialcreate$waterFraction)) : 0);
	}
	@org.spongepowered.asm.mixin.injection.Inject(method = "getEngineEfficiency", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$paidEfficiency(int size,
		org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
		var self = (BoilerData) (Object) this;
		cir.setReturnValue(self.attachedEngines > 0 ? (float) (Math.min(1,
			SourcePhysics.thermalSteamLevel(self, size) / self.attachedEngines)
			* differentialcreate$waterFraction * differentialcreate$admission) : 0);
	}
	@org.spongepowered.asm.mixin.injection.Inject(method = "addToGoggleTooltip", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$closedAdmission(List<Component> tooltip, boolean sneaking, int size,
		org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		var self = (BoilerData) (Object) this;
		if (!self.isActive() || differentialcreate$admission != 0 && (differentialcreate$demand > 0
			|| SourcePhysics.thermalSteamLevel(self, size) == 0 || self.attachedEngines == 0 || self.attachedWhistles > 0)) return;
		self.calcMinMaxForSize(size);
		CreateLang.translate("boiler.status", Component.translatable("gui.differentialcreate.boiler.admission_closed")
			.withStyle(ChatFormatting.GRAY)).forGoggles(tooltip);
		CreateLang.builder().add(self.getSizeComponent(true, false)).forGoggles(tooltip, 1);
		CreateLang.builder().add(self.getWaterComponent(true, false)).forGoggles(tooltip, 1);
		CreateLang.builder().add(self.getHeatComponent(true, false)).forGoggles(tooltip, 1);
		cir.setReturnValue(true);
	}
	@org.spongepowered.asm.mixin.Unique private int differentialcreate$previousEngines;
	@org.spongepowered.asm.mixin.Unique private int differentialcreate$previousWhistles;
	@org.spongepowered.asm.mixin.injection.Inject(method = "evaluate", at = @At("HEAD"))
	private void differentialcreate$beforeEvaluation(com.simibubi.create.content.fluids.tank.FluidTankBlockEntity controller,
		org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		var self = (BoilerData) (Object) this;
		SteamBoilerWater.bind(controller);
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
			: key.equals("boiler.per_tick")
			? CreateLang.translate(key, CreateLang.number(differentialcreate$demand).add(CreateLang.translate("generic.unit.millibuckets")))
			: CreateLang.translate(key, args);
	}
}
