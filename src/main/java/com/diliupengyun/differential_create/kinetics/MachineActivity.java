package com.diliupengyun.differential_create.kinetics;

import com.diliupengyun.differential_create.mixin.SawBlockEntityAccessor;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.BlockBreakingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.drill.DrillBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.logistics.depot.EjectorBlockEntity;

import net.minecraft.core.Direction;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

/** Zero standby torque; operation state is independent of RPM, including a stalled cycle. */
public final class MachineActivity {

	private MachineActivity() {}

	public static boolean manages(KineticBlockEntity be) {
		return be instanceof MechanicalPressBlockEntity || be instanceof MechanicalMixerBlockEntity
			|| be instanceof MillstoneBlockEntity || be instanceof CrushingWheelBlockEntity
			|| be instanceof SawBlockEntity || be instanceof DrillBlockEntity
			|| be instanceof DeployerBlockEntity || be instanceof MechanicalCrafterBlockEntity
			|| be instanceof ArmBlockEntity || be instanceof EjectorBlockEntity
			|| ContraptionPhysics.manages(be);
	}

	/** Capture a cycle's final working tick even if it returns to idle during its tick. */
	public static void beforeTick(KineticBlockEntity be) {
		if (be.getLevel() == null || be.getLevel().isClientSide || !manages(be)) return;
		if (working(be))
			((IMemberDynamics) be).differentialcreate$member().lastWorkingTick = be.getLevel().getGameTime();
	}

	/** Crusher work is ticked by the controller, which can finish before either wheel ticks. */
	public static void beforeCrusherTick(CrushingWheelControllerBlockEntity controller) {
		var level = controller.getLevel();
		if (level == null || level.isClientSide || !controller.isOccupied()
			|| !controller.hasEntity() && controller.inventory.remainingTime <= 0) return;
		for (Direction face : Direction.values()) {
			var pos = controller.getBlockPos().relative(face);
			if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof CrushingWheelBlockEntity wheel
				&& wheel.getBlockState().getValue(BlockStateProperties.AXIS) != face.getAxis())
				((IMemberDynamics) wheel).differentialcreate$member().lastWorkingTick = level.getGameTime();
		}
	}

	public static boolean active(KineticBlockEntity be) {
		MemberState state = ((IMemberDynamics) be).differentialcreate$member();
		if (be.getLevel() == null) return false;
		if (be.getLevel().isClientSide) return state.machineActive;
		if (be instanceof CrushingWheelBlockEntity) return working(be);
		if (be instanceof MechanicalBearingBlockEntity) return working(be);
		ShaftWork work = MachineWork.of(be);
		if (work != null) return Double.isFinite(work.differentialcreate$remainingTravel());
		return state.lastWorkingTick == be.getLevel().getGameTime() || working(be);
	}

	/** Keep Create's rated stress value separate from this tick's physical torque. */
	public static float impact(KineticBlockEntity be) {
		if (ContraptionPhysics.manages(be)) return (float) ContraptionPhysics.workTorque(be);
		return manages(be) && !active(be) ? 0 : Math.max(0, be.calculateStressApplied());
	}

	/** Actual sustained shaft resistance at this speed; accelerating inertia is separate. */
	public static double loadTorque(KineticBlockEntity be, double speed) {
		if (be instanceof IPumpDynamics pump) return Math.max(0, pump.differentialcreate$torque() * Math.signum(speed));
		double impact = impact(be);
		return (be instanceof EncasedFanBlockEntity ? 0 : impact) + quadraticDrag(be) * speed * speed;
	}
	public static double quadraticDrag(KineticBlockEntity be) {
		return ContraptionPhysics.airDrag(be) + (be instanceof EncasedFanBlockEntity
			? impact(be) / Math.pow(PhysicsTuning.FAN_ANCHOR_RPM, 2) : 0);
	}

	/** Sample only machines with an operation state; invalidate balances only on transitions. */
	public static void refresh(KineticNetwork network) {
		NetworkData data = ((INetworkDynamics) network).differentialcreate$data();
		for (KineticBlockEntity be : data.activityMembers) {
			if (be.isRemoved()) continue;
			MemberState state = ((IMemberDynamics) be).differentialcreate$member();
			ContraptionPhysics.checkAttachments(be);
			boolean active = active(be);
			double drag = ContraptionPhysics.airDrag(be), load = ContraptionPhysics.workTorque(be);
			if (state.machineActivityKnown && state.machineActive == active
				&& drag == state.sampledStructureDrag && load == state.sampledStructureLoad) continue;
			state.sampledStructureDrag = drag; state.sampledStructureLoad = load;
			state.machineActive = active;
			state.machineActivityKnown = true;
			data.invalidate();
			be.sendData();
		}
	}

	public static boolean working(KineticBlockEntity be) {
		if (ContraptionPhysics.manages(be)) return !ContraptionPhysics.drives(be).isEmpty();
		if (be instanceof MechanicalPressBlockEntity press)
			return press.getPressingBehaviour() != null && press.getPressingBehaviour().running;
		if (be instanceof MechanicalMixerBlockEntity mixer) return mixer.running;
		if (be instanceof MillstoneBlockEntity mill) {
			if (mill.timer <= 0 || mill.inputInv.getStackInSlot(0).isEmpty() || millOutputBlocked(mill)) return false;
			MemberState state = ((IMemberDynamics) mill).differentialcreate$member();
			if (state.millingRecipeCheck == null)
				state.millingRecipeCheck = RecipeManager.createCheck(AllRecipeTypes.MILLING.getType());
			return state.millingRecipeCheck.getRecipeFor(new RecipeWrapper(mill.inputInv), mill.getLevel()).isPresent();
		}
		if (be instanceof SawBlockEntity saw) {
			Direction facing = saw.getBlockState().getValue(BlockStateProperties.FACING);
			if (facing.getAxis().isHorizontal()) return breakingBlock(saw);
			return facing == Direction.UP && !saw.inventory.isEmpty() && saw.inventory.remainingTime > 0
				&& (saw.inventory.appliedRecipe || !((SawBlockEntityAccessor) saw).differentialcreate$matchingRecipes().isEmpty());
		}
		if (be instanceof DrillBlockEntity drill) return breakingBlock(drill);
		if (be instanceof CrushingWheelBlockEntity wheel) {
			Direction.Axis axis = wheel.getBlockState().getValue(BlockStateProperties.AXIS);
			for (Direction face : Direction.values()) {
				if (face.getAxis() == axis) continue;
				var pos = wheel.getBlockPos().relative(face);
				if (wheel.getLevel().isLoaded(pos)
					&& wheel.getLevel().getBlockEntity(pos) instanceof CrushingWheelControllerBlockEntity controller
					&& controller.isOccupied() && (controller.hasEntity() || controller.inventory.remainingTime > 0))
					return true;
			}
			return false;
		}
		if (be instanceof DeployerBlockEntity) {
			String phase = MachinePhase.of(be);
			return phase.equals("EXPANDING") || phase.equals("RETRACTING");
		}
		if (be instanceof MechanicalCrafterBlockEntity) {
			String phase = MachinePhase.of(be);
			return phase.equals("ASSEMBLING") || phase.equals("EXPORTING") || phase.equals("CRAFTING");
		}
		if (be instanceof ArmBlockEntity) {
			String phase = MachinePhase.of(be);
			return phase.equals("MOVE_TO_INPUT") || phase.equals("MOVE_TO_OUTPUT") || phase.equals("DANCING");
		}
		if (be instanceof EjectorBlockEntity ejector) return ejector.getState() != EjectorBlockEntity.State.CHARGED;
		return false;
	}

	/** NeoForge's slot limit can exceed a particular item's stack limit. */
	public static boolean millOutputBlocked(MillstoneBlockEntity mill) {
		for (int slot = 0; slot < mill.outputInv.getSlots(); slot++) {
			var stack = mill.outputInv.getStackInSlot(slot);
			if (!stack.isEmpty() && stack.getCount() >= Math.min(mill.outputInv.getSlotLimit(slot), stack.getMaxStackSize()))
				return true;
		}
		return false;
	}

	private static boolean breakingBlock(BlockBreakingKineticBlockEntity breaker) {
		var level = breaker.getLevel();
		var pos = breaker.getBlockPos().relative(breaker.getBlockState().getValue(BlockStateProperties.FACING));
		if (!level.isLoaded(pos)) return false;
		var target = level.getBlockState(pos);
		return breaker.canBreak(target, target.getDestroySpeed(level, pos));
	}
}
