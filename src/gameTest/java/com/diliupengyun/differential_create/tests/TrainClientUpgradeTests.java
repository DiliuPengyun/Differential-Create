package com.diliupengyun.differential_create.tests;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Actual survival click packets must sync upgrades and play Create's manual-application feedback once. */
public final class TrainClientUpgradeTests {
    private static final BlockPos WHEEL = new BlockPos(12, -59, -45);
    private static int scenario, stage, age, sounds;
    private static final List<SoundInstance> feedback = new ArrayList<>();
    private static long waitUntil;
    private static CompletableFuture<?> setup;
    private static CompletableFuture<Boolean> verification;
    private static Consumer<PlaySoundEvent> listener;
    private TrainClientUpgradeTests() {}

    public static boolean tick(Minecraft mc) throws Exception {
        if (scenario == 4) return true;
        if (++age > 1200) throw new AssertionError("Client bogey upgrade timed out: " + scenario + " / " + stage);
        if (listener == null) {
            listener = event -> {
                var sound = event.getOriginalSound();
                if (sound.getLocation().equals(SoundEvents.COPPER_BREAK.getLocation()) && Math.abs(sound.getX() - WHEEL.getX() - .5) < 1) {
                    feedback.add(sound);
                    sounds++;
                }
            };
            NeoForge.EVENT_BUS.addListener(PlaySoundEvent.class, listener);
        }
        switch (stage) {
            case 0 -> {
                sounds = 0; feedback.clear();
                setup = mc.getSingleplayerServer().submit(() -> {
                    var level = mc.getSingleplayerServer().overworld();
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    player.setGameMode(GameType.SURVIVAL);
                    level.setBlockAndUpdate(WHEEL.below(), AllBlocks.TRACK.getDefaultState());
                    level.removeBlock(WHEEL, false);
                    level.setBlockAndUpdate(WHEEL, (large() ? AllBlocks.LARGE_BOGEY : AllBlocks.SMALL_BOGEY).getDefaultState().setValue(AbstractBogeyBlock.AXIS, Direction.Axis.Z));
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                    var engine = AllBlocks.STEAM_ENGINE.asStack(); engine.setCount(2); player.setItemInHand(hand(), engine);
                    player.teleportTo(14.5, -60, -48.5);
                    player.onUpdateAbilities(); player.inventoryMenu.broadcastChanges(); return null;
                });
                advance(1);
            }
            case 1 -> {
                if (!setup.isDone()) return false;
                setup.join();
                if (mc.level.getBlockEntity(WHEEL) == null || !AllBlocks.STEAM_ENGINE.isIn(mc.player.getItemInHand(hand()))
                    || mc.player.getItemInHand(hand()).getCount() != 2 || SteamBogey.powered(mc.level.getBlockEntity(WHEEL))) return false;
                click(mc); advance(2);
            }
            case 2 -> {
                if (!matches(mc, false) || sounds < 1 || !SteamBogey.powered(mc.level.getBlockEntity(WHEEL))
                    || mc.player.getItemInHand(hand()).getCount() != 1 || Integer.parseInt(mc.particleEngine.countParticles()) == 0) return false;
                verifyFeedback();
                click(mc); waitUntil = mc.level.getGameTime() + 3; advance(3);
            }
            case 3 -> {
                if (mc.level.getGameTime() < waitUntil || !matches(mc, false)) return false;
                if (sounds != 1) throw new AssertionError("Repeated engine installation replayed its effect");
                setup = mc.getSingleplayerServer().submit(() -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    var pipe = ModContent.CONDENSER_PIPE.asStack(); pipe.setCount(2); player.setItemInHand(hand(), pipe);
                    player.inventoryMenu.broadcastChanges(); return null;
                });
                advance(4);
            }
            case 4 -> {
                if (!setup.isDone()) return false;
                setup.join();
                if (!ModContent.CONDENSER_PIPE.isIn(mc.player.getItemInHand(hand())) || mc.player.getItemInHand(hand()).getCount() != 2) return false;
                click(mc); advance(5);
            }
            case 5 -> {
                if (!matches(mc, true) || sounds < 2 || !SteamBogey.condensing(mc.level.getBlockEntity(WHEEL))
                    || mc.player.getItemInHand(hand()).getCount() != 1) return false;
                verifyFeedback();
                click(mc); waitUntil = mc.level.getGameTime() + 3; advance(6);
            }
            case 6 -> {
                if (mc.level.getGameTime() < waitUntil || !matches(mc, true)) return false;
                if (sounds != 2 || mc.screen != null) throw new AssertionError("Repeated condenser installation replayed feedback or opened a screen");
                if (scenario == 0) {
                    var path = Path.of("../../..", "train-interaction", "condenser-installed.png").toAbsolutePath().normalize();
                    try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(path); }
                }
                scenario++;
                if (scenario == 4) {
                    NeoForge.EVENT_BUS.unregister(listener);
                    setup = mc.getSingleplayerServer().submit(() -> {
                        var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst(); player.setGameMode(GameType.CREATIVE); return null;
                    });
                    DifferentialCreate.LOGGER.info("DC_TRAIN_CLIENT_UPGRADES_PASS: 4 size/hand scenarios, 8 survival upgrades with native sounds, particles, sync and duplicate protection");
                }
                advance(0);
            }
            default -> throw new AssertionError("Unknown bogey upgrade stage");
        }
        return scenario == 4;
    }
    private static boolean large() { return scenario >= 2; }
    private static InteractionHand hand() { return scenario % 2 == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND; }
    private static void verifyFeedback() {
        // PlaySoundEvent runs before SoundEngine resolves the sound; inspect it on the following frame.
        for (var sound : feedback)
            if (Math.abs(sound.getPitch() - 1.45f) > 1e-5 || sound.getVolume() != 1)
                throw new AssertionError("Upgrade sound differs from Create's manual casing application");
    }
    private static void click(Minecraft mc) {
        mc.player.setYRot(30); mc.player.setXRot(5);
        var hit = new BlockHitResult(Vec3.atCenterOf(WHEEL).add(0, .5, 0), Direction.UP, WHEEL, false);
        if (!mc.gameMode.useItemOn(mc.player, hand(), hit).consumesAction()) throw new AssertionError("Bogey upgrade click did not swing the held item");
    }
    private static boolean matches(Minecraft mc, boolean condenser) {
        if (verification == null) {
            verification = mc.getSingleplayerServer().submit(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                var bogey = player.level().getBlockEntity(WHEEL);
                return SteamBogey.powered(bogey) && SteamBogey.condensing(bogey) == condenser && player.getItemInHand(hand()).getCount() == 1;
            });
            return false;
        }
        if (!verification.isDone()) return false;
        boolean result = verification.join(); verification = null; return result;
    }
    private static void advance(int next) { stage = next; age = 0; verification = null; }
}
