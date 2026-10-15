package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.SteamHosePreview;
import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.diliupengyun.differential_create.trains.SteamHoseItem;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.simibubi.create.AllBlocks;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Real client game-mode calls and packets; server-side tests alone cannot catch a retained overlay. */
public final class TrainClientInteractionTests {
    private static final BlockPos SOURCE = new BlockPos(2, -59, -7), ORDINARY = SOURCE.east(2), POWERED = SOURCE.east(4);
    private static final BlockPos CONTAINER = SOURCE.east(), NATIVE = SOURCE.east(3), PLAIN = SOURCE.north();
    private static final String[] ACTIONS = {"ordinary", "tank", "container", "plain", "powered", "native", "air", "entity",
        "entity_specific", "connect", "unlink", "switch_slot", "screen"};
    private static final int SCENARIOS = ACTIONS.length * 4;
    private static int scenario, stage, age, completed;
    private static CompletableFuture<?> setup;
    private static CompletableFuture<Boolean> verification;
    private static boolean initialized;
    private static int coreStage, coreFrames;
    private static CompletableFuture<?> coreSetup;
    private TrainClientInteractionTests() {}

    public static boolean tick(Minecraft mc) throws Exception {
        if (scenario >= SCENARIOS) return corePreview(mc);
        if (++age > 1200) throw new AssertionError("Client hose operation timed out: " + action() + " / " + hand() + " stage " + stage);
        switch (stage) {
            case 0 -> {
                shift(mc, false);
                setup = mc.getSingleplayerServer().submit(() -> {
                    var level = mc.getSingleplayerServer().overworld();
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    if (!initialized) {
                        for (var pos : new BlockPos[]{SOURCE, ORDINARY, POWERED, CONTAINER, NATIVE, PLAIN})
                            level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(SOURCE, AllBlocks.FLUID_TANK.getDefaultState());
                        level.setBlockAndUpdate(ORDINARY, AllBlocks.SMALL_BOGEY.getDefaultState());
                        level.setBlockAndUpdate(POWERED, AllBlocks.LARGE_BOGEY.getDefaultState());
                        level.setBlockAndUpdate(CONTAINER, Blocks.BARREL.defaultBlockState());
                        level.setBlockAndUpdate(NATIVE, AllBlocks.STEAM_ENGINE.getDefaultState());
                        level.setBlockAndUpdate(PLAIN, Blocks.STONE.defaultBlockState());
                        SteamBogey.install((com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity) level.getBlockEntity(POWERED));
                        var stand = EntityType.ARMOR_STAND.create(level);
                        if (stand == null) throw new AssertionError("Cannot create interaction target");
                        stand.setPos(3.5, -59, -9); stand.setNoGravity(true); level.addFreshEntity(stand);
                        initialized = true;
                    }
                    var bogey = level.getBlockEntity(POWERED);
                    if (action().equals("connect")) SteamHoseLinks.unlink(bogey);
                    else SteamHoseLinks.connect(bogey, (com.simibubi.create.content.fluids.tank.FluidTankBlockEntity) level.getBlockEntity(SOURCE));
                    player.getInventory().selected = 0;
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                    var hose = ModContent.STEAM_HOSE.asStack(); var tag = new CompoundTag();
                    tag.putInt("SmokeScenario", scenario); tag.putString("OtherMod", "keep");
                    hose.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); player.setItemInHand(hand(), hose);
                    player.teleportTo(4.5, -59, -10.5);
                    player.getAbilities().flying = true; player.onUpdateAbilities();
                    player.inventoryMenu.broadcastChanges();
                    return null;
                });
                advance(1);
            }
            case 1 -> {
                if (!setup.isDone()) return false;
                setup.join();
                var stack = mc.player.getItemInHand(hand());
                if (!(stack.getItem() instanceof SteamHoseItem) || data(stack).getInt("SmokeScenario") != scenario
                    || SteamHoseItem.hasSelection(stack) || mc.level.getBlockEntity(POWERED) == null
                    || mc.player.position().distanceToSqr(new Vec3(4.5, -59, -10.5)) > 1) return false;
                mc.player.getInventory().selected = 0; mc.player.connection.send(new ServerboundSetCarriedItemPacket(0));
                if (!click(mc, ORDINARY).consumesAction() || SteamHoseItem.hasSelection(stack))
                    throw new AssertionError("An ordinary bogey cannot start a connection");
                if (!click(mc, engineFirst() ? POWERED : SOURCE).consumesAction()) throw new AssertionError("Client cannot select first endpoint");
                advance(2);
            }
            case 2 -> {
                if (!SteamHoseItem.hasSelection(mc.player.getItemInHand(hand()))
                    || SteamHoseItem.selectedEndpoint(mc.level, mc.player.getItemInHand(hand())) == null
                    || !serverMatches(mc, true, !action().equals("connect"))) return false;
                if (!click(mc, ORDINARY).consumesAction()) throw new AssertionError("Ordinary bogey input was not consumed");
                SteamHosePreview.tick();
                if (!SteamHosePreview.active() || SteamHosePreview.connection() != null || SteamHosePreview.valid())
                    throw new AssertionError("Ordinary bogey must show an installation hint without a valid hose preview");
                advance(3);
            }
            case 3 -> {
                if (age < 4) return false;
                if (scenario == 0) capture(mc, "hose-before-cancel.png");
                if (scenario == ACTIONS.length * 2) capture(mc, "hose-engine-first-before-cancel.png");
                switch (action()) {
                    case "connect" -> {
                        BlockPos end = engineFirst() ? SOURCE : POWERED;
                        mc.hitResult = new BlockHitResult(Vec3.atCenterOf(end), Direction.NORTH, end, false);
                        SteamHosePreview.tick();
                        if (!SteamHosePreview.active() || !SteamHosePreview.valid() || SteamHosePreview.connection() == null)
                            throw new AssertionError("Both endpoint orders must preview a valid hose before connecting");
                        click(mc, end);
                    }
                    case "switch_slot" -> {
                        // Main-hand selection is put away; offhand selection is put away by the inventory screen.
                        if (hand() == InteractionHand.MAIN_HAND) {
                            mc.player.getInventory().selected = 1; mc.player.connection.send(new ServerboundSetCarriedItemPacket(1));
                        } else mc.setScreen(new InventoryScreen(mc.player));
                        SteamHosePreview.tick(); assertNoPreview();
                        if (mc.screen != null) mc.screen.onClose(); mc.player.getInventory().selected = 0;
                        mc.player.connection.send(new ServerboundSetCarriedItemPacket(0));
                        shift(mc, true); mc.gameMode.useItem(mc.player, hand());
                    }
                    case "screen" -> {
                        mc.setScreen(new InventoryScreen(mc.player)); SteamHosePreview.tick(); assertNoPreview();
                        mc.screen.onClose(); shift(mc, true); mc.gameMode.useItem(mc.player, hand());
                    }
                    default -> {
                        shift(mc, true);
                        switch (action()) {
                            case "air" -> mc.gameMode.useItem(mc.player, hand());
                            case "entity", "entity_specific" -> {
                                var entities = mc.level.getEntitiesOfClass(ArmorStand.class, new AABB(SOURCE).inflate(5));
                                if (entities.isEmpty()) throw new AssertionError("Client entity has not loaded");
                                var stand = entities.getFirst();
                                var result = entityClick(mc, stand);
                                if (!result.consumesAction()) throw new AssertionError("Entity cancellation did not consume input");
                            }
                            default -> click(mc, switch (action()) {
                                case "ordinary" -> ORDINARY;
                                case "tank" -> SOURCE;
                                case "container" -> CONTAINER;
                                case "powered", "unlink" -> POWERED;
                                case "native" -> NATIVE;
                                default -> PLAIN;
                            });
                        }
                    }
                }
                if (!action().equals("connect")) assertNoPreview(); // Check the actual event's immediate cleanup, before another tick.
                advance(4);
            }
            case 4 -> {
                if (SteamHoseItem.hasSelection(mc.player.getItemInHand(hand())) || !serverMatches(mc, false, true)) return false;
                SteamHosePreview.tick(); assertNoPreview();
                if (mc.screen != null || mc.player.containerMenu != mc.player.inventoryMenu)
                    throw new AssertionError("Cancellation triggered a block/entity GUI");
                if (scenario == 0) capture(mc, "hose-after-cancel.png");
                if (scenario == ACTIONS.length * 2) capture(mc, "hose-engine-first-after-cancel.png");
                if (action().equals("unlink")) {
                    shift(mc, true); click(mc, POWERED); advance(5);
                } else finish(mc);
            }
            case 5 -> {
                if (!serverMatches(mc, false, false)) return false;
                click(mc, POWERED); // A second unlink is harmless.
                advance(6);
            }
            case 6 -> {
                if (age < 10 || !serverMatches(mc, false, false)) return false;
                finish(mc);
            }
            default -> throw new AssertionError("Unknown client operation stage");
        }
        return false;
    }
    private static boolean engineFirst() { return scenario >= ACTIONS.length * 2; }
    private static InteractionHand hand() { return (scenario / ACTIONS.length) % 2 == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND; }
    private static String action() { return ACTIONS[scenario % ACTIONS.length]; }
    private static CompoundTag data(ItemStack stack) { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(); }
    private static void shift(Minecraft mc, boolean shift) {
        mc.options.keyShift.setDown(shift); mc.player.input.shiftKeyDown = shift;
        mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player,
            shift ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
    }
    private static net.minecraft.world.InteractionResult click(Minecraft mc, BlockPos pos) {
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
        mc.hitResult = hit;
        // Follow Minecraft's actual main-hand-first input order, including an empty main hand.
        var first = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        if (first.consumesAction() || first == net.minecraft.world.InteractionResult.FAIL) return first;
        return mc.gameMode.useItemOn(mc.player, InteractionHand.OFF_HAND, hit);
    }
    private static net.minecraft.world.InteractionResult entityClick(Minecraft mc, ArmorStand stand) {
        for (var candidate : InteractionHand.values()) {
            var result = action().equals("entity") ? mc.gameMode.interact(mc.player, stand, candidate)
                : mc.gameMode.interactAt(mc.player, stand, new EntityHitResult(stand, stand.position().add(0, .8, 0)), candidate);
            if (result.consumesAction()) return result;
        }
        return net.minecraft.world.InteractionResult.PASS;
    }
    private static boolean serverMatches(Minecraft mc, boolean selected, boolean linked) {
        if (verification == null) {
            verification = mc.getSingleplayerServer().submit(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                var stack = player.getItemInHand(hand());
                var engine = mc.getSingleplayerServer().overworld().getBlockEntity(POWERED);
                return stack.getItem() instanceof SteamHoseItem && stack.getCount() == 1 && data(stack).getInt("SmokeScenario") == scenario
                    && data(stack).getString("OtherMod").equals("keep") && SteamHoseItem.hasSelection(stack) == selected
                    && (!selected || SteamHoseItem.selectsEngine(stack) == engineFirst())
                    && (!selected || SteamHoseItem.selectedEndpoint(player.level(), stack) == player.level().getBlockEntity(engineFirst() ? POWERED : SOURCE))
                    && (selected || !data(stack).contains("Dimension")) && SteamBogey.powered(engine)
                    && (((SteamHoseLinks.EngineLink) engine).differentialcreate$hose() != null) == linked
                    && player.containerMenu == player.inventoryMenu;
            });
            return false;
        }
        if (!verification.isDone()) return false;
        boolean matched = verification.join(); verification = null;
        return matched;
    }
    private static void assertNoPreview() {
        if (SteamHosePreview.active() || SteamHosePreview.connection() != null || SteamHosePreview.valid()
            || Outliner.getInstance().getOutlines().containsKey("differentialcreate:hose_source")
            || Outliner.getInstance().getOutlines().containsKey("differentialcreate:hose_target"))
            throw new AssertionError("Cancelled/hidden hose retained its overlay, preview path or selection outlines");
    }
    private static void capture(Minecraft mc, String name) throws Exception {
        Path path = Path.of("../..", "..", "train-interaction", name).toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(path.getParent());
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(path); }
    }
    private static void advance(int next) { stage = next; age = 0; verification = null; }
    private static void finish(Minecraft mc) {
        DifferentialCreate.LOGGER.info("DC_TRAIN_CLIENT_OPERATION_PASS: {} / {} / {} first", action(), hand(), engineFirst() ? "engine" : "boiler");
        shift(mc, false); completed++; scenario++; advance(0);
        if (scenario == SCENARIOS) DifferentialCreate.LOGGER.info("DC_TRAIN_CLIENT_INTERACTIONS_PASS: {} client/server operations", completed);
    }
    private static boolean corePreview(Minecraft mc) throws Exception {
        if (coreStage == 0) {
            coreSetup = mc.getSingleplayerServer().submit(() -> {
                var level = mc.getSingleplayerServer().overworld();
                for (int index = 0; index < 4; index++) {
                    var pos = new BlockPos(index < 2 ? index * 3 : 8 + (index - 2) * 3, -58, -20);
                    level.setBlockAndUpdate(pos.below(), AllBlocks.TRACK.getDefaultState());
                    level.setBlockAndUpdate(pos, (index < 2 ? AllBlocks.SMALL_BOGEY : AllBlocks.LARGE_BOGEY).getDefaultState());
                    if ((index & 1) != 0) SteamBogey.install((com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity) level.getBlockEntity(pos));
                }
                var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                player.teleportTo(2, -58, -24.5); level.setDayTime(6000);
                return null;
            });
            coreStage = 1;
        }
        if (coreStage == 1) {
            if (!coreSetup.isDone()) return false;
            coreSetup.join();
            if (!mc.player.getMainHandItem().isEmpty() || !mc.player.getOffhandItem().isEmpty()
                || !SteamBogey.powered(mc.level.getBlockEntity(new BlockPos(3, -58, -20)))
                || !SteamBogey.powered(mc.level.getBlockEntity(new BlockPos(11, -58, -20)))) return false;
            mc.options.hideGui = true; coreStage = 2; coreFrames = 0;
        }
        mc.player.setYRot(coreStage < 4 ? 0 : 45); mc.player.setXRot(coreStage < 4 ? 15 : 22);
        if (coreStage == 2 && ++coreFrames > 30) {
            capture(mc, "steam-bogey-small.png");
            coreSetup = mc.getSingleplayerServer().submit(() -> {
                mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst().teleportTo(10, -58, -24.5);
                return null;
            });
            coreStage = 3; coreFrames = 0;
        }
        if (coreStage == 3 && coreSetup.isDone() && Math.abs(mc.player.getX() - 10) < .1 && ++coreFrames > 30) {
            coreSetup.join(); capture(mc, "steam-bogey-large.png");
            coreSetup = mc.getSingleplayerServer().submit(() -> {
                mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst().teleportTo(5.3, -58, -21.3);
                return null;
            });
            coreStage = 4; coreFrames = 0;
        }
        if (coreStage == 4 && coreSetup.isDone() && Math.abs(mc.player.getX() - 5.3) < .1 && ++coreFrames > 30) {
            coreSetup.join(); capture(mc, "steam-bogey-small-detail.png");
            coreSetup = mc.getSingleplayerServer().submit(() -> {
                mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst().teleportTo(13.3, -58, -21.3);
                return null;
            });
            coreStage = 5; coreFrames = 0;
        }
        if (coreStage == 5 && coreSetup.isDone() && Math.abs(mc.player.getX() - 13.3) < .1 && ++coreFrames > 30) {
            coreSetup.join(); capture(mc, "steam-bogey-large-detail.png"); coreStage = 6;
            DifferentialCreate.LOGGER.info("DC_TRAIN_BOGEY_CORE_PREVIEW_PASS: small/large native and powered bogeys rendered on tracks");
        }
        return coreStage == 6;
    }
}
