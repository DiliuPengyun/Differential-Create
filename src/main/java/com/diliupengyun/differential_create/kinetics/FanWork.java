package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Keep Create's types, recipes and batch scaling; replace elapsed ticks with airflow exposure. */
public final class FanWork {
	private FanWork() {}
	private static double duration(ItemStack stack) {
		return AllConfigs.server().kinetics.fanProcessingTime.get() * (((stack.getCount() - 1) / 16) + 1);
	}
	public static boolean apply(ItemEntity entity, FanProcessingType type, double rpm) {
		double rate = FanProcessingRate.at(rpm);
		if (rate == 0) return false;
		CompoundTag data = entity.getPersistentData().getCompound("CreateData");
		entity.getPersistentData().put("CreateData", data);
		CompoundTag progress = data.getCompound("Processing");
		data.put("Processing", progress);
		var key = CreateBuiltInRegistries.FAN_PROCESSING_TYPE.getKey(type);
		if (key == null) throw new IllegalArgumentException("Unregistered fan processing type: " + type);
		if (!progress.contains("DCRemaining") || !key.toString().equals(progress.getString("Type"))) {
			if (!type.canProcess(entity.getItem(), entity.level())) return false;
			progress.putString("Type", key.toString());
			progress.putDouble("DCRemaining", duration(entity.getItem()));
			progress.putInt("Time", (int) Math.ceil(duration(entity.getItem())));
		}
		if (progress.getInt("Time") < 0) return false;
		double remaining = progress.getDouble("DCRemaining");
		remaining = Math.max(0, remaining - rate);
		progress.putDouble("DCRemaining", remaining);
		progress.putInt("Time", (int) Math.ceil(remaining));
		if (remaining > 1e-9) return false;
		var stacks = type.process(entity.getItem(), entity.level());
		progress.putInt("Time", -1);
		if (stacks == null) return false;
		if (stacks.isEmpty()) { entity.discard(); return false; }
		entity.setItem(stacks.remove(0));
		for (var stack : stacks) {
			var output = new ItemEntity(entity.level(), entity.getX(), entity.getY(), entity.getZ(), stack);
			output.setDeltaMovement(entity.getDeltaMovement());
			entity.level().addFreshEntity(output);
		}
		return true;
	}
	public static TransportedResult apply(TransportedItemStack stack, Level level, FanProcessingType type, double rpm) {
		double rate = FanProcessingRate.at(rpm);
		if (rate == 0) return TransportedResult.doNothing();
		var progress = (IFanProcessingProgress) stack;
		if (stack.processedBy != type) {
			stack.processedBy = type;
			progress.differentialcreate$fanRemaining(type.canProcess(stack.stack, level) ? duration(stack.stack) : -1);
		}
		double remaining = progress.differentialcreate$fanRemaining();
		if (remaining < 0) return TransportedResult.doNothing();
		remaining = Math.max(0, remaining - rate);
		progress.differentialcreate$fanRemaining(remaining);
		if (remaining > 1e-9) return TransportedResult.doNothing();
		var outputs = type.process(stack.stack, level);
		progress.differentialcreate$fanRemaining(-1);
		if (outputs == null) return TransportedResult.doNothing();
		var transported = new ArrayList<TransportedItemStack>();
		for (var item : outputs) {
			var output = stack.getSimilar();
			output.stack = item.copy();
			transported.add(output);
		}
		return TransportedResult.convertTo(transported);
	}
}
