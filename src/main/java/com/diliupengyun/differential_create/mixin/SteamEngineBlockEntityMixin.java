package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.SteamEnginePhysics;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SteamEngineBlockEntity.class)
public abstract class SteamEngineBlockEntityMixin {
	/** Vanilla's zero-efficiency shortcut changes the sign of a cold, north/west-facing engine. */
	@ModifyVariable(method = "tick", at = @At(value = "STORE", ordinal = 0), name = "conveyedSpeedLevel")
	private int differentialcreate$consistentColdDirection(int original) {
		var engine = (SteamEngineBlockEntity) (Object) this;
		var shaft = engine.getShaft();
		if (shaft == null) return original;
		if (shaft.getBlockState().getValue(BlockStateProperties.AXIS) == Direction.Axis.Y) return 1;
		Direction facing = SteamEngineBlock.getFacing(engine.getBlockState());
		if (facing.getAxis() == Direction.Axis.Y) facing = engine.getBlockState().getValue(SteamEngineBlock.FACING);
		return (int) GeneratingKineticBlockEntity.convertToDirection(1, facing);
	}

	@Redirect(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/steamEngine/PoweredShaftBlockEntity;getTheoreticalSpeed()F"))
	private float differentialcreate$driveDirection(PoweredShaftBlockEntity shaft) {
		return SteamEnginePhysics.drivingSpeed(shaft);
	}

	@Inject(method = "getTank", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$loadedBoiler(CallbackInfoReturnable<FluidTankBlockEntity> cir) {
		cir.setReturnValue(SteamEnginePhysics.tank((SteamEngineBlockEntity) (Object) this));
	}

	@Inject(method = "getShaft", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$loadedShaft(CallbackInfoReturnable<PoweredShaftBlockEntity> cir) {
		cir.setReturnValue(SteamEnginePhysics.shaft((SteamEngineBlockEntity) (Object) this));
	}
	@Inject(method = "isValid", at = @At("HEAD"), cancellable = true)
	private void differentialcreate$validHoseSupply(CallbackInfoReturnable<Boolean> cir) {
		var engine = (SteamEngineBlockEntity) (Object) this;
		if (engine.isRemoved() || engine.getLevel() == null) {
			cir.setReturnValue(false); return;
		}
		if (((com.diliupengyun.differential_create.trains.SteamHoseLinks.EngineLink) engine).differentialcreate$hose() != null) {
			cir.setReturnValue(!engine.isRemoved() && SteamEnginePhysics.tank(engine) != null);
			return;
		}
		if (!engine.getLevel().isLoaded(engine.getBlockPos().relative(SteamEngineBlock.getFacing(engine.getBlockState()).getOpposite())))
			cir.setReturnValue(false);
	}
}
