package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Bridges Create's item/recipe callbacks to the network's physical clock. */
public final class MachineWork {
	public static final double EPSILON = 1e-8;
	private static List<Runnable> events;
	private MachineWork() {}
	/** Advance all counters before callbacks can change another machine's phase. */
	public static void batch(Runnable advance) {
		if (events != null) throw new IllegalStateException("Nested machine advance");
		events = new ArrayList<>();
		List<Runnable> pending;
		try { advance.run(); pending = events; }
		finally { events = null; }
		pending.forEach(Runnable::run);
	}
	public static void event(Runnable callback) {
		if (events == null) callback.run();
		else events.add(callback);
	}
	public record Binding(ShaftWork work, KineticBlockEntity shaft) {
		public INetworkDynamics dynamics() { return (INetworkDynamics) shaft.getOrCreateNetwork(); }
		public double ratio() { return Math.abs(dynamics().differentialcreate$getRatios().getOrDefault(shaft, 0f)); }
	}
	public static boolean deferred(KineticBlockEntity be) {
		return be.getLevel() != null && !be.getLevel().isClientSide && be.hasNetwork()
			&& !be.getLevel().tickRateManager().isFrozen();
	}
	public static ShaftWork of(KineticBlockEntity be) {
		if (be instanceof com.simibubi.create.content.kinetics.saw.SawBlockEntity
			&& be.getBlockState().getValue(BlockStateProperties.FACING) != Direction.UP)
			return be instanceof IBlockBreakingWork breaker ? breaker.differentialcreate$breakingWork() : null;
		if (be instanceof com.simibubi.create.content.kinetics.drill.DrillBlockEntity
			&& be instanceof IBlockBreakingWork breaker) return breaker.differentialcreate$breakingWork();
		if (be instanceof com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity
			&& !MachinePhase.of(be).startsWith("MOVE_TO_")) return null;
		if (be instanceof MechanicalPressBlockEntity press)
			return (ShaftWork) press.getPressingBehaviour();
		return be instanceof ShaftWork work ? work : null;
	}
	public static List<Binding> collect(List<KineticNetwork> networks) {
		var result = new LinkedHashMap<ShaftWork, Binding>();
		for (var network : networks)
			for (var be : network.members.keySet()) {
				if (be.isRemoved()) continue;
				ShaftWork work = of(be);
				if (work != null) result.put(work, new Binding(work, work.differentialcreate$workShaft(be)));
				ContraptionPhysics.checkAttachments(be);
				for (var drive : ContraptionPhysics.drives(be))
					for (var actor : drive.entity().getContraption().getActors())
						if (actor.getRight() != null) {
							var actorWork = ContraptionActorWork.of(actor.getRight());
							result.put(actorWork, new Binding(actorWork, be));
						}
				if (be instanceof CrushingWheelBlockEntity wheel)
					for (Direction face : Direction.values()) {
						if (face.getAxis() == wheel.getBlockState().getValue(BlockStateProperties.AXIS)) continue;
						var pos = wheel.getBlockPos().relative(face);
						if (wheel.getLevel().isLoaded(pos) && wheel.getLevel().getBlockEntity(pos)
							instanceof CrushingWheelControllerBlockEntity controller) {
							work = (ShaftWork) controller;
							// A controller advances once, even when both wheels belong to this network.
							var old = result.get(work);
							if (old == null || be.getBlockPos().compareTo(old.shaft().getBlockPos()) < 0)
								result.put(work, new Binding(work, be));
						}
					}
			}
		return new ArrayList<>(result.values());
	}
}
