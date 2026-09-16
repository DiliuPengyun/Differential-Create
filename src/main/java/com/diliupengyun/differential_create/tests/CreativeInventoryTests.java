package com.diliupengyun.differential_create.tests;

import java.util.Collection;

import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.content.ModCreativeModeTabs;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllCreativeModeTabs;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Exercises the real category/search generation and mod events used when opening the inventory. */
final class CreativeInventoryTests {

	private CreativeInventoryTests() {}

	static void run(GameTestHelper helper) {
		var level = helper.getLevel();
		var features = level.enabledFeatures();
		var registries = level.registryAccess();
		for (boolean hasPermissions : new boolean[] {false, true, false}) {
			// Search aggregates category entries before firing its own event. Building
			// only Create's category misses duplicate direct registrations in search.
			CreativeModeTabs.tryRebuildTabContents(features, hasPermissions, registries);
			assertContents(helper);
			helper.assertTrue(!CreativeModeTabs.tryRebuildTabContents(features, hasPermissions, registries),
				"reopening the inventory with unchanged parameters uses the cached contents");
			assertContents(helper);
		}
		helper.succeed();
	}

	private static void assertContents(GameTestHelper helper) {
		var createTab = AllCreativeModeTabs.BASE_CREATIVE_TAB.get();
		var addonTab = ModCreativeModeTabs.MAIN.get();
		var searchTab = CreativeModeTabs.searchTab();
		for (Item item : new Item[] {
			AllBlocks.CLUTCH.asItem(), ModContent.BRASS_CLUTCH.asItem(), ModContent.COMPOSITE_FRICTION_PLATE.get()
		}) {
			boolean isCreateItem = item == AllBlocks.CLUTCH.asItem();
			assertCount(helper, createTab.getDisplayItems(), item, isCreateItem ? 1 : 0, "Create category");
			assertCount(helper, createTab.getSearchTabDisplayItems(), item, isCreateItem ? 1 : 0, "Create searchable entries");
			assertCount(helper, addonTab.getDisplayItems(), item, isCreateItem ? 0 : 1, "Differential Create category");
			assertCount(helper, addonTab.getSearchTabDisplayItems(), item, isCreateItem ? 0 : 1, "Differential Create searchable entries");
			assertCount(helper, searchTab.getDisplayItems(), item, 1, "global search");
			assertCount(helper, searchTab.getSearchTabDisplayItems(), item, 1, "global searchable entries");
		}
	}

	private static void assertCount(GameTestHelper helper, Collection<ItemStack> contents, Item item, long expected, String tab) {
		long count = contents.stream().filter(stack -> stack.is(item)).count();
		helper.assertTrue(count == expected, tab + " must contain " + expected + " " + item + ", found " + count);
	}
}
