package com.diliupengyun.differential_create.content;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.simibubi.create.content.kinetics.transmission.SplitShaftRenderer;
import com.simibubi.create.content.kinetics.transmission.SplitShaftVisual;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.data.SharedProperties;
import com.tterrag.registrate.util.entry.BlockEntityEntry;
import com.tterrag.registrate.util.entry.BlockEntry;
import com.tterrag.registrate.util.entry.ItemEntry;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.MapColor;

/** The addon owns its upgrade block and material; Create's clutch remains the starter tier. */
public final class ModContent {

	private static final CreateRegistrate REGISTRATE = DifferentialCreate.registrate();

	public static final BlockEntry<BrassClutchBlock> BRASS_CLUTCH = REGISTRATE
		.block("brass_clutch", BrassClutchBlock::new)
		.initialProperties(SharedProperties::stone)
		.properties(p -> p.noOcclusion().mapColor(MapColor.TERRACOTTA_BROWN))
		.simpleItem()
		.register();

	public static final ItemEntry<Item> COMPOSITE_FRICTION_PLATE = REGISTRATE
		.item("composite_friction_plate", Item::new)
		.register();

	public static final BlockEntityEntry<BrassClutchBlockEntity> BRASS_CLUTCH_ENTITY = REGISTRATE
		.blockEntity("brass_clutch", BrassClutchBlockEntity::new)
		.visual(() -> SplitShaftVisual::new, false)
		.validBlocks(BRASS_CLUTCH)
		.renderer(() -> SplitShaftRenderer::new)
		.register();

	private ModContent() {}

	/** Initializes the entries after the mod's Registrate has its event bus. */
	public static void register() {}
}
