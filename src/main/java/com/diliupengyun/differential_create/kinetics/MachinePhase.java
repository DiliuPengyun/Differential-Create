package com.diliupengyun.differential_create.kinetics;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;

/** Read-only bridges for Create's inaccessible operation enums; resolve each field once. */
public final class MachinePhase {
	private static final MethodHandle DEPLOYER = getter(DeployerBlockEntity.class, "state");
	private static final MethodHandle CRAFTER = getter(MechanicalCrafterBlockEntity.class, "phase");
	private static final MethodHandle ARM = getter(ArmBlockEntity.class, "phase");

	private MachinePhase() {}

	public static String of(KineticBlockEntity be) {
		MethodHandle getter = be instanceof DeployerBlockEntity ? DEPLOYER
			: be instanceof MechanicalCrafterBlockEntity ? CRAFTER : ARM;
		try {
			return ((Enum<?>) getter.invokeExact(be)).name();
		} catch (Throwable failure) {
			throw new IllegalStateException("Cannot read Create machine phase at " + be.getBlockPos(), failure);
		}
	}

	private static MethodHandle getter(Class<?> owner, String name) {
		try {
			var field = owner.getDeclaredField(name);
			return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).unreflectGetter(field)
				.asType(MethodType.methodType(Enum.class, KineticBlockEntity.class));
		} catch (ReflectiveOperationException failure) {
			throw new ExceptionInInitializerError(failure);
		}
	}
}
