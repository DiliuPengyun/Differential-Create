package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Bridges Create's item/recipe callbacks to the network's physical clock. */
public final class MachineWork {
	public static final double EPSILON = 1e-8;
	private static final ThreadLocal<List<Runnable>> events = new ThreadLocal<>();
	private MachineWork() {}
	/** Advance all counters before callbacks can change another machine's phase. */
	public static void batch(Runnable advance) {
		if (events.get() != null) throw new IllegalStateException("Nested machine advance");
		events.set(new ArrayList<>());
		List<Runnable> pending;
		try { advance.run(); pending = events.get(); }
		finally { events.remove(); }
		pending.forEach(callback -> {
			KineticReadCache.clear();
			try { callback.run(); } finally { KineticReadCache.clear(); }
		});
	}
	public static void event(Runnable callback) {
		var pending = events.get();
		if (pending == null) {
			KineticReadCache.clear();
			try { callback.run(); } finally { KineticReadCache.clear(); }
		}
		else pending.add(callback);
	}
	public record Binding(ShaftWork work, KineticBlockEntity shaft, CrushingKinetics.Pair pair) {
		public Binding(ShaftWork work, KineticBlockEntity shaft) { this(work,shaft,null); }
		public INetworkDynamics dynamics() { return (INetworkDynamics) shaft.getOrCreateNetwork(); }
		public double ratio() { return Math.abs(dynamics().differentialcreate$getRatios().getOrDefault(shaft, 0f)); }
	}
	public static boolean deferred(KineticBlockEntity be) {
		return be.getLevel() != null && !be.getLevel().isClientSide && be.hasNetwork()
			&& be.getLevel().tickRateManager().runsNormally();
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
		var cache = KineticReadCache.group(networks);
		if (cache != null && cache.work != null) return cache.work;
		var bindings = collectUncached(networks);
		if (cache != null) cache.work = bindings;
		return bindings;
	}
	private static List<Binding> collectUncached(List<KineticNetwork> networks) {
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
			}
		for (var pair : CrushingKinetics.pairs(networks)) {
			var work = (ShaftWork) pair.controller();
			result.put(work, new Binding(work, pair.a(), pair));
		}
		return new ArrayList<>(result.values());
	}
}
