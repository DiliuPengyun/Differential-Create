package com.diliupengyun.differential_create.content;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.simibubi.create.AllCreativeModeTabs;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The category name also supplies the blue attribution line in creative search tooltips. */
public final class ModCreativeModeTabs {

	private static final DeferredRegister<CreativeModeTab> TABS =
		DeferredRegister.create(Registries.CREATIVE_MODE_TAB, DifferentialCreate.MODID);

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
		() -> CreativeModeTab.builder()
			.title(Component.translatable("itemGroup.differentialcreate"))
			.icon(ModContent.BRASS_CLUTCH::asStack)
			.withTabsBefore(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
			.build());

	private ModCreativeModeTabs() {}

	public static void register(IEventBus bus) {
		TABS.register(bus);
	}
}
