package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.diliupengyun.differential_create.trains.SteamHoseItem;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import java.util.ArrayList;
import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Player input branches, including selection cancellation before native block/entity interactions. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainPlayerInteractionTests {
    private static final BlockPos BOILER = new BlockPos(2, 4, 4), WHEEL = new BlockPos(6, 4, 5);
    private static final BlockPos ORDINARY = WHEEL.south(3), UNLINKED = WHEEL.east(2), SECOND = BOILER.east(8);

    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (var hand : InteractionHand.values()) {
            for (String target : new String[]{"plain", "ordinary_bogey", "tank", "linked_bogey", "unlinked_bogey",
                "native_engine", "container", "lever", "air", "entity", "entity_specific"}) {
                add(tests, "cancel_" + target, hand, h -> cancel(h, hand, target, false));
                add(tests, "reverse_cancel_" + target, hand, h -> cancel(h, hand, target, true));
            }
            for (String action : new String[]{"success", "engine_first", "ordinary_bogey", "reselect", "reconnect",
                "repeated_unlink", "missing_tank", "wrong_dimension", "out_of_range", "exact_range",
                "adventure_cancel", "separate_hands", "no_hose"})
                add(tests, action, hand, h -> operation(h, hand, action));
            for (String action : new String[]{"connect", "native_engine", "reselect_engine", "same_endpoint", "removed_engine",
                "uninstalled_engine", "wrong_dimension", "out_of_range", "exact_range", "serialized_selection"})
                add(tests, "reverse_" + action, hand, h -> reverse(h, hand, action));
        }
        for (String action : new String[]{"empty_main_cancel", "empty_main_unlink"})
            add(tests, action, InteractionHand.OFF_HAND, h -> emptyMain(h, action));
        return tests;
    }
    private static void add(ArrayList<TestFunction> tests, String action, InteractionHand hand,
        java.util.function.Consumer<GameTestHelper> test) {
        String name = "train_player_hose_" + action + "_" + hand.name().toLowerCase(java.util.Locale.ROOT);
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            tests.add(new TestFunction("train_players", name, "differentialcreate_trains:kineticphysicstests.physics_test", 100, 0, true, test));
    }
    private record Fixture(GameTestHelper h, Player player, InteractionHand hand, ItemStack hose, AbstractBogeyBlockEntity engine) {
        InteractionResult click(BlockPos local) {
            BlockPos pos = h.absolutePos(local);
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            var event = new PlayerInteractEvent.RightClickBlock(player, hand, pos, hit);
            NeoForge.EVENT_BUS.post(event);
            h.assertTrue(event.isCanceled(), "Hose input must be consumed before the block's own interaction");
            return event.getCancellationResult();
        }
        void select(BlockPos local) {
            player.setShiftKeyDown(false);
            h.assertTrue(click(local).consumesAction() && SteamHoseItem.hasSelection(hose), "Right-click selects the first endpoint");
            h.assertTrue(SteamHoseItem.selectedEndpoint(h.getLevel(), hose) == h.getBlockEntity(local), "Selected endpoint matches the clicked block");
            h.assertTrue(SteamHoseItem.selectsEngine(hose) == SteamHoseItem.isEngine(h.getBlockEntity(local)), "Selection records the correct endpoint kind");
        }
        CompoundTag data() { return hose.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(); }
        void cancelled() {
            h.assertTrue(!SteamHoseItem.hasSelection(hose) && !data().contains("Dimension") && data().getString("OtherMod").equals("keep"),
                "Cancellation clears either endpoint kind and dimension, preserving unrelated item data");
            h.assertTrue(hose.getCount() == 1, "The hose remains reusable");
        }
    }
    private static void fixture(GameTestHelper h, InteractionHand hand, java.util.function.Consumer<Fixture> test) {
        TrainIntegrationTests.structure(h, 0, false);
        h.setBlock(ORDINARY, AllBlocks.SMALL_BOGEY.getDefaultState());
        h.setBlock(UNLINKED, AllBlocks.LARGE_BOGEY.getDefaultState());
        SteamBogey.install(h.getBlockEntity(UNLINKED));
        h.setBlock(SECOND, AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(new BlockPos(5, 4, 4).below(), Blocks.STONE);
        h.setBlock(new BlockPos(5, 4, 4), AllBlocks.STEAM_ENGINE.getDefaultState());
        h.setBlock(new BlockPos(7, 4, 9), Blocks.STONE);
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(Vec3.atCenterOf(h.absolutePos(WHEEL)));
        var hose = ModContent.STEAM_HOSE.asStack();
        var data = new CompoundTag(); data.putString("OtherMod", "keep");
        hose.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        player.setItemInHand(hand, hose);
        h.runAtTickTime(25, () -> {
            AbstractBogeyBlockEntity engine = h.getBlockEntity(WHEEL);
            SteamHoseLinks.connect(engine, h.getBlockEntity(BOILER));
            test.accept(new Fixture(h, player, hand, hose, engine));
            h.succeed();
        });
    }
    private static void cancel(GameTestHelper h, InteractionHand hand, String target, boolean engineFirst) {
        fixture(h, hand, f -> {
            f.select(engineFirst ? WHEEL : BOILER);
            // Reproduce the reported sequence before every cancellation target.
            f.click(ORDINARY);
            h.assertTrue(SteamHoseItem.hasSelection(f.hose) && !SteamBogey.powered(h.getBlockEntity(ORDINARY)),
                "Clicking an ordinary bogey keeps the pending selection and cannot install an engine");
            BlockPos original = ((SteamHoseLinks.EngineLink) f.engine).differentialcreate$hose();
            f.player.setShiftKeyDown(true);
            switch (target) {
                case "air" -> h.assertTrue(ModContent.STEAM_HOSE.get().use(h.getLevel(), f.player, hand).getResult().consumesAction(), "Air cancellation consumes input");
                case "entity", "entity_specific" -> {
                    var stand = EntityType.ARMOR_STAND.create(h.getLevel());
                    h.assertTrue(stand != null, "Entity interaction fixture available");
                    stand.setPos(Vec3.atCenterOf(h.absolutePos(WHEEL)));
                    if (target.equals("entity")) {
                        var event = new PlayerInteractEvent.EntityInteract(f.player, hand, stand);
                        NeoForge.EVENT_BUS.post(event);
                        h.assertTrue(event.isCanceled() && event.getCancellationResult().consumesAction(), "Cancel before a general entity interaction");
                    } else {
                        var event = new PlayerInteractEvent.EntityInteractSpecific(f.player, hand, stand, Vec3.ZERO);
                        NeoForge.EVENT_BUS.post(event);
                        h.assertTrue(event.isCanceled() && event.getCancellationResult().consumesAction(), "Cancel before a precise entity interaction");
                    }
                }
                default -> f.click(switch (target) {
                    case "ordinary_bogey" -> ORDINARY;
                    case "tank" -> BOILER;
                    case "linked_bogey" -> WHEEL;
                    case "unlinked_bogey" -> UNLINKED;
                    case "native_engine" -> new BlockPos(5, 4, 4);
                    case "container" -> new BlockPos(9, 4, 4);
                    case "lever" -> new BlockPos(8, 4, 5);
                    default -> new BlockPos(7, 4, 9);
                });
            }
            f.cancelled();
            h.assertTrue(original.equals(((SteamHoseLinks.EngineLink) f.engine).differentialcreate$hose())
                && SteamHoseLinks.tank(f.engine) != null && SteamBogey.powered(f.engine),
                "Cancelling on any target preserves existing connections and installed engines");
            h.assertTrue(f.player.containerMenu == f.player.inventoryMenu, "Cancellation does not open the container GUI");
        });
    }
    private static void operation(GameTestHelper h, InteractionHand hand, String action) {
        fixture(h, hand, f -> {
            BlockPos original = ((SteamHoseLinks.EngineLink) f.engine).differentialcreate$hose();
            if (action.equals("repeated_unlink")) {
                f.player.setShiftKeyDown(true); f.click(WHEEL); f.click(WHEEL);
                h.assertTrue(((SteamHoseLinks.EngineLink) f.engine).differentialcreate$hose() == null
                    && SteamHoseLinks.tank(f.engine) == null && ((FluidTankBlockEntity) h.getBlockEntity(BOILER)).boiler.attachedEngines == 0,
                    "Disconnect removes the endpoint and boiler index exactly once");
                h.assertTrue(SteamBogey.powered(f.engine) && f.hose.getCount() == 1, "Disconnect keeps the engine and hose");
                return;
            }
            if (action.equals("no_hose")) {
                f.player.setItemInHand(hand, new ItemStack(Items.STONE)); f.player.setShiftKeyDown(true);
                BlockPos pos = h.absolutePos(new BlockPos(9, 4, 4));
                var event = new PlayerInteractEvent.RightClickBlock(f.player, hand, pos, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                NeoForge.EVENT_BUS.post(event);
                h.assertTrue(!event.isCanceled(), "Hose handlers do not intercept other items");
                return;
            }
            if (action.equals("engine_first")) {
                f.select(WHEEL);
                h.assertTrue(SteamHoseItem.selectsEngine(f.hose) && !f.data().contains("Tank"), "An engine can start a connection without a boiler selection");
            } else {
                if (action.equals("ordinary_bogey")) {
                    f.click(ORDINARY);
                    h.assertTrue(!SteamHoseItem.hasSelection(f.hose), "An unpowered bogey cannot be the first endpoint");
                }
                f.select(BOILER);
                switch (action) {
                    case "ordinary_bogey" -> {
                        f.click(ORDINARY);
                        h.assertTrue(SteamHoseItem.hasSelection(f.hose) && !SteamBogey.powered(h.getBlockEntity(ORDINARY)), "Ordinary bogey cannot receive a hose");
                    }
                    case "adventure_cancel" -> {
                        f.player.getAbilities().mayBuild = false; f.player.setShiftKeyDown(true); f.click(WHEEL); f.cancelled();
                    }
                    case "separate_hands" -> {
                        var other = ModContent.STEAM_HOSE.asStack(); other.set(DataComponents.CUSTOM_DATA, CustomData.of(f.data()));
                        var otherHand = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                        f.player.setItemInHand(otherHand, other); f.player.setShiftKeyDown(true); f.click(ORDINARY); f.cancelled();
                        h.assertTrue(SteamHoseItem.hasSelection(other), "Cancel only the hose in the interacting hand");
                    }
                    case "missing_tank" -> {
                        h.getLevel().removeBlock(h.absolutePos(BOILER), false);
                        h.assertTrue(f.click(WHEEL) == InteractionResult.FAIL && SteamHoseItem.hasSelection(f.hose), "Removed source cannot replace a connection");
                    }
                    case "wrong_dimension" -> {
                        var data = f.data(); data.putString("Dimension", "minecraft:the_nether"); f.hose.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                        h.assertTrue(f.click(WHEEL) == InteractionResult.FAIL, "A source in another dimension cannot replace the hose");
                    }
                    case "out_of_range", "exact_range" -> {
                        BlockPos far = BOILER.east(SteamHoseLinks.MAX_LENGTH + (action.equals("out_of_range") ? 1 : 0));
                        h.setBlock(far, AllBlocks.SMALL_BOGEY.getDefaultState()); SteamBogey.install(h.getBlockEntity(far));
                        var result = f.click(far);
                        var endpoint = (SteamHoseLinks.EngineLink) h.getBlockEntity(far);
                        h.assertTrue(action.equals("exact_range") ? result.consumesAction() && endpoint.differentialcreate$hose() != null && !SteamHoseItem.hasSelection(f.hose)
                            : result == InteractionResult.FAIL && endpoint.differentialcreate$hose() == null && SteamHoseItem.hasSelection(f.hose),
                            "The inclusive 48-block distance boundary is enforced");
                    }
                    default -> {
                        if (action.equals("reselect") || action.equals("reconnect")) f.select(SECOND);
                        BlockPos destination = action.equals("reconnect") ? WHEEL : UNLINKED;
                        h.assertTrue(f.click(destination).consumesAction(), "Selected valid boiler connects");
                        AbstractBogeyBlockEntity bogey = h.getBlockEntity(destination);
                        FluidTankBlockEntity expected = h.getBlockEntity(action.equals("success") ? BOILER : SECOND);
                        h.assertTrue(SteamHoseLinks.tank(bogey) == expected.getControllerBE(), "Most recent selection is the connection source");
                        f.cancelled();
                        if (action.equals("reconnect")) h.assertTrue(((FluidTankBlockEntity) h.getBlockEntity(BOILER)).boiler.attachedEngines == 0,
                            "Replacing a connection removes the old boiler's backlink");
                        return;
                    }
                }
            }
            h.assertTrue(original.equals(((SteamHoseLinks.EngineLink) f.engine).differentialcreate$hose()), "Non-connection actions preserve the previous hose");
        });
    }
    private static void reverse(GameTestHelper h, InteractionHand hand, String action) {
        fixture(h, hand, f -> {
            BlockPos original = ((SteamHoseLinks.EngineLink) f.engine).differentialcreate$hose();
            BlockPos selected = switch (action) {
                case "native_engine" -> new BlockPos(5, 4, 4);
                case "removed_engine", "uninstalled_engine" -> UNLINKED;
                default -> WHEEL;
            };
            f.select(selected);
            h.assertTrue(f.data().contains("Engine") && !f.data().contains("Tank"), "Reverse selection does not retain a boiler key");
            switch (action) {
                case "reselect_engine" -> {
                    f.select(UNLINKED); f.click(BOILER); f.cancelled();
                    h.assertTrue(SteamHoseLinks.tank(h.getBlockEntity(UNLINKED)) == ((FluidTankBlockEntity) h.getBlockEntity(BOILER)).getControllerBE(),
                        "A second engine replaces the first selection rather than connecting two engines");
                }
                case "same_endpoint" -> {
                    f.select(WHEEL);
                    h.assertTrue(original.equals(((SteamHoseLinks.EngineLink) f.engine).differentialcreate$hose()), "Selecting the same endpoint twice leaves its connection untouched");
                    f.click(SECOND); f.cancelled();
                    h.assertTrue(SteamHoseLinks.tank(f.engine) == h.getBlockEntity(SECOND), "Repeated selection can still connect to a boiler");
                    return;
                }
                case "removed_engine", "uninstalled_engine", "wrong_dimension" -> {
                    if (action.equals("removed_engine")) h.getLevel().removeBlock(h.absolutePos(selected), false);
                    else if (action.equals("uninstalled_engine")) {
                        f.player.setItemInHand(hand, ItemStack.EMPTY); f.player.setShiftKeyDown(true);
                        f.click(selected);
                        h.assertTrue(!SteamBogey.powered(h.getBlockEntity(selected)), "Native player interaction removes the selected engine");
                        f.player.setItemInHand(hand, f.hose); f.player.setShiftKeyDown(false);
                    } else {
                        var data = f.data(); data.putString("Dimension", "minecraft:the_nether");
                        f.hose.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                    }
                    h.assertTrue(SteamHoseItem.selectedEndpoint(h.getLevel(), f.hose) == null && f.click(SECOND) == InteractionResult.FAIL,
                        "Missing, uninstalled or cross-dimension engine cannot receive a new hose");
                    f.player.setShiftKeyDown(true); f.click(BOILER); f.cancelled();
                }
                case "out_of_range", "exact_range" -> {
                    BlockPos far = WHEEL.east(SteamHoseLinks.MAX_LENGTH + (action.equals("out_of_range") ? 1 : 0));
                    h.setBlock(far, AllBlocks.FLUID_TANK.getDefaultState());
                    var result = f.click(far);
                    if (action.equals("exact_range")) {
                        h.assertTrue(result.consumesAction() && SteamHoseLinks.tank(f.engine) == h.getBlockEntity(far), "Reverse order accepts exactly 48 blocks");
                        f.cancelled(); return;
                    }
                    h.assertTrue(result == InteractionResult.FAIL && SteamHoseItem.hasSelection(f.hose), "Reverse order rejects beyond 48 blocks and keeps its first endpoint");
                }
                case "serialized_selection" -> {
                    var restored = ItemStack.parseOptional(h.getLevel().registryAccess(), (CompoundTag) f.hose.save(h.getLevel().registryAccess()));
                    f.player.setItemInHand(hand, restored);
                    var reloaded = new Fixture(h, f.player, hand, restored, f.engine);
                    h.assertTrue(SteamHoseItem.selectedEndpoint(h.getLevel(), restored) == f.engine && SteamHoseItem.selectsEngine(restored),
                        "Engine-first selection survives actual item component serialization");
                    reloaded.click(SECOND); reloaded.cancelled();
                    h.assertTrue(SteamHoseLinks.tank(f.engine) == h.getBlockEntity(SECOND), "Reloaded selection completes its reverse connection");
                    return;
                }
                default -> {
                    var target = action.equals("native_engine") ? BOILER : SECOND;
                    h.assertTrue(f.click(target).consumesAction(), "Engine-first connection succeeds"); f.cancelled();
                    h.assertTrue(SteamHoseLinks.tank(h.getBlockEntity(selected)) == ((FluidTankBlockEntity) h.getBlockEntity(target)).getControllerBE(),
                        "The selected engine is connected to the clicked boiler");
                    if (action.equals("connect")) {
                        h.assertTrue(((FluidTankBlockEntity) h.getBlockEntity(BOILER)).boiler.attachedEngines == 0, "Reverse relink removes the old boiler index");
                        return;
                    }
                }
            }
            h.assertTrue(original.equals(((SteamHoseLinks.EngineLink) f.engine).differentialcreate$hose()), "Invalid selection or editing another engine preserves the existing connection");
        });
    }
    private static void emptyMain(GameTestHelper h, String action) {
        fixture(h, InteractionHand.OFF_HAND, f -> {
            if (action.equals("empty_main_cancel")) f.select(BOILER);
            f.player.setShiftKeyDown(true);
            BlockPos pos = h.absolutePos(WHEEL);
            var event = new PlayerInteractEvent.RightClickBlock(f.player, InteractionHand.MAIN_HAND, pos,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            NeoForge.EVENT_BUS.post(event);
            h.assertTrue(event.isCanceled() && event.getCancellationResult().consumesAction() && SteamBogey.powered(f.engine),
                "An empty main hand must use the offhand hose rather than remove the engine");
            f.cancelled();
            h.assertTrue((((SteamHoseLinks.EngineLink) f.engine).differentialcreate$hose() != null) == action.equals("empty_main_cancel"),
                "Offhand pending selection cancels first; idle offhand hose can disconnect");
        });
    }
}
