package com.diliupengyun.differential_create.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.MixerHeadAnimation;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Real server-built mixers and ordinary client ticks; the fixture never assigns an animation pose. */
public final class MixerClientSmokeTests {
    private static final BlockPos[] MIXERS = {new BlockPos(0, -56, 0), new BlockPos(4, -56, 0), new BlockPos(8, -56, 0)};
    private static final int[] COMMANDS = {8, 128, 0, -64, 256, 0};
    private static final Path REPORT = Path.of(System.getProperty("differentialcreate.mixerClientReport", "build/mixer-client"));
    private static final Map<BlockPos, Pose> previous = new HashMap<>();
    private static final Map<BlockPos, Pose> parked = new HashMap<>();
    private static final Set<BlockPos> stepped = new HashSet<>();
    private static final Set<Integer> stages = new HashSet<>();
    private static final java.util.List<String> rows = new java.util.ArrayList<>();
    private static final java.util.List<String> freezeRows = new java.util.ArrayList<>();
    private record Pose(MechanicalMixerBlockEntity owner, float angle, float height) {}
    private static volatile int age;
    private static volatile boolean finished;
    private static volatile String serverFailure;
    private static volatile double[] steppedInputs;
    private static ServerLevel world;
    private static int disconnectedAt;
    private static boolean requested, done, positioned, rebuilt, freezeObserved;
    private static int frames, sampledTick = -1, samples, heldSamples, movingSamples, captured = -1, frozenSample = -1;
    private static long deadline;
    private static CompletableFuture<?> fixture;

    public static void frame(RenderFrameEvent.Post event) {
        if (done) return;
        var mc = Minecraft.getInstance();
        try {
            if (!requested) {
                if (mc.getOverlay() != null || mc.screen == null || ++frames < 30) return;
                requested = true; deadline = System.nanoTime() + 180_000_000_000L;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(3); mc.options.simulationDistance().set(5);
                mc.options.enableVsync().set(false); mc.options.framerateLimit().set(90);
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                var rules = new GameRules();
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                var settings = new LevelSettings("Mixer client verification", GameType.CREATIVE, false,
                    Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("mixer_verification", settings, new WorldOptions(42, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(), mc.screen);
                return;
            }
            if (System.nanoTime() > deadline) throw new AssertionError("Mixer client fixture timed out");
            if (serverFailure != null) throw new AssertionError(serverFailure);
            if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
            if (fixture == null) {
                Files.createDirectories(REPORT);
                mc.setScreen(null); mc.options.hideGui = true;
                fixture = mc.getSingleplayerServer().submit(() -> {
                    world = mc.getSingleplayerServer().overworld(); world.setDayTime(6000);
                    world.getChunkAt(MIXERS[0]); world.getChunkAt(MIXERS[2]);
                    for (var pos : MIXERS) {
                        world.setBlockAndUpdate(pos.below(3), AllBlocks.ANDESITE_CASING.getDefaultState());
                        world.setBlockAndUpdate(pos.below(2), AllBlocks.BASIN.getDefaultState());
                        world.setBlockAndUpdate(pos, AllBlocks.MECHANICAL_MIXER.getDefaultState());
                        world.setBlockAndUpdate(pos.west(), AllBlocks.COGWHEEL.getDefaultState()
                            .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
                        world.setBlockAndUpdate(pos.west().above(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                            .setValue(BlockStateProperties.FACING, Direction.DOWN));
                        var basin = (BasinBlockEntity) world.getBlockEntity(pos.below(2));
                        basin.getInputInventory().setStackInSlot(0, new ItemStack(Items.ANDESITE, 32));
                        basin.getInputInventory().setStackInSlot(1, new ItemStack(Items.IRON_NUGGET, 32));
                        basin.setChanged(); basin.sendData();
                    }
                    var player = world.players().getFirst();
                    player.getAbilities().flying = true; player.onUpdateAbilities();
                    player.teleportTo(4, -54.7, 10);
                    player.setYRot(180); player.setXRot(17);
                    age = 1;
                    return null;
                });
                return;
            }
            if (!fixture.isDone()) return;
            fixture.join();
            if (!positioned) {
                mc.player.setPos(4, -54.7, 10); mc.player.setYRot(180); mc.player.setXRot(17);
                positioned = true;
            }
            int tick = mc.levelRenderer.getTicks();
            if (tick != sampledTick) {
                for (var pos : MIXERS) {
                    if (!(mc.level.getBlockEntity(pos) instanceof MechanicalMixerBlockEntity mixer)) continue;
                    var animation = ((MixerHeadAnimation.Owner) mixer).differentialcreate$headAnimation();
                    float start = animation.degrees(0), end = animation.degrees(1);
                    float height = mixer.getRenderedHeadOffset(0), heightEnd = mixer.getRenderedHeadOffset(1);
                    rows.add(age + "," + tick + "," + pos.getX() + "," + mixer.getSpeed() + "," + mixer.running
                        + "," + mixer.runningTicks + "," + start + "," + end + "," + height + "," + heightEnd);
                    var old = previous.put(pos, new Pose(mixer, end, heightEnd));
                    if (old != null && old.owner == mixer && sampledTick + 1 == tick && (age < 295 || age > 355)) {
                        require(Math.abs(Math.IEEEremainder(start - old.angle, 360)) < .002, "World mixer angle jumps between client ticks");
                        require(Math.abs(height - old.height) < 1e-5, "World mixer head jumps between client ticks");
                    }
                    stages.add(!mixer.running ? 0 : mixer.runningTicks < 15 ? 1 : mixer.runningTicks <= 20 ? 2 : 3);
                    if (Math.abs(Math.IEEEremainder(end - start, 360)) > .001) movingSamples++;
                    if (Math.abs(mixer.getSpeed()) < 1e-4 && Math.abs(end - start) < 1e-5) {
                        require(Math.abs(heightEnd - height) < 1e-5, "A stopped world mixer head still interpolates travel");
                        if (mixer.running) heldSamples++;
                    }
                    samples++;
                }
                sampledTick = tick;
            }
            if (age >= 314 && age <= 325 || age >= 340 && age <= 347) {
                require(!mc.level.tickRateManager().runsNormally(), "Client did not receive tick freeze");
                for (var pos : MIXERS) {
                    if (!(mc.level.getBlockEntity(pos) instanceof MechanicalMixerBlockEntity mixer)) continue;
                    var animation = ((MixerHeadAnimation.Owner) mixer).differentialcreate$headAnimation();
                    var pose = new Pose(mixer, animation.degrees(1), mixer.getRenderedHeadOffset(1));
                    var before = parked.putIfAbsent(pos, pose);
                    if (before == null) continue;
                    double travel = Math.abs(Math.IEEEremainder(pose.angle - before.angle, 360));
                    if (age <= 325) {
                        require(travel < .002 && Math.abs(pose.height - before.height) < 1e-5,
                            "Frozen world mixer continues moving");
                        freezeObserved = true;
                    } else if (travel > .002) {
                        var input = com.diliupengyun.differential_create.client.ClientNetworkSpeeds.rotationOf(mixer);
                        require(steppedInputs != null && input != null && Math.abs(input.angleAt(input.tick())
                            - steppedInputs[pos.getX() / 4]) < 1e-6, "Single step does not retain the full server shaft endpoint");
                        if (pos.equals(MIXERS[0])) require(Math.abs(pose.height - before.height) > .001,
                            "Single step does not move the descending mixer head");
                        stepped.add(pos);
                    }
                }
            }
            if (age >= 295 && age <= 355 && frozenSample != age) {
                frozenSample = age;
                for (var pos : MIXERS) {
                    var mixer = (MechanicalMixerBlockEntity) mc.level.getBlockEntity(pos);
                    var animation = ((MixerHeadAnimation.Owner) mixer).differentialcreate$headAnimation();
                    var input = com.diliupengyun.differential_create.client.ClientNetworkSpeeds.rotationOf(mixer);
                    freezeRows.add(age + "," + tick + "," + pos.getX() + "," + mc.level.tickRateManager().runsNormally()
                        + "," + animation.degrees(0) + "," + animation.degrees(1) + "," + input);
                }
            }
            if (!rebuilt && age >= 250) { mc.levelRenderer.allChanged(); rebuilt = true; }
            if (age / 25 > captured && age > 20) {
                captured = age / 25;
                try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    image.writeToFile(REPORT.resolve(String.format("world-%03d.png", age)));
                }
            }
            if (finished) {
                Files.write(REPORT.resolve("samples.csv"), rows);
                require(samples > 600 && movingSamples > 200 && heldSamples > 20 && stages.size() == 4 && rebuilt
                        && freezeObserved && stepped.size() == MIXERS.length,
                    "Incomplete mixer coverage: samples=" + samples + ", moving=" + movingSamples + ", held=" + heldSamples + ", stages=" + stages
                        + ", freeze=" + freezeObserved + ", stepped=" + stepped);
                DifferentialCreate.LOGGER.info("DC_MIXER_CLIENT_PASS: {} ordinary world samples, {} held mid-stroke samples, low speed, changes, reversal, stop, renderer rebuild, freeze and single step", samples, heldSamples);
                done = true;
                if (!Boolean.getBoolean("differentialcreate.mixerClientStayOpen")) mc.stop();
            }
        } catch (Throwable failure) {
            try { Files.write(REPORT.resolve("samples.csv"), rows); } catch (Exception ignored) {}
            DifferentialCreate.LOGGER.error("DC_MIXER_CLIENT_FAIL", failure); done = true; mc.stop();
        }
        if (done) try { Files.write(REPORT.resolve("freeze.csv"), freezeRows); } catch (Exception ignored) {}
    }

    public static void tick(ServerTickEvent.Post event) {
        if (done || world == null || age == 0 || finished) return;
        try {
            int time = ++age;
            if (time == 290) {
                world.setBlockAndUpdate(MIXERS[0], net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                world.setBlockAndUpdate(MIXERS[0], AllBlocks.MECHANICAL_MIXER.getDefaultState());
            }
            if (time == 300) world.getServer().tickRateManager().setFrozen(true);
            if (time == 330) world.getServer().tickRateManager().stepGameIfPaused(1);
            if (time == 350) world.getServer().tickRateManager().setFrozen(false);
            if (time == 332) {
                double[] endpoints = new double[MIXERS.length];
                for (int i = 0; i < MIXERS.length; i++) endpoints[i] =
                    com.diliupengyun.differential_create.kinetics.ElasticKinetics.state(
                        (MechanicalMixerBlockEntity) world.getBlockEntity(MIXERS[i])).angle * 360;
                steppedInputs = endpoints;
            }
            var variable = (MechanicalMixerBlockEntity) world.getBlockEntity(MIXERS[1]);
            if (disconnectedAt == 0 && time < 80 && variable.running && variable.runningTicks >= 6) {
                disconnectedAt = time;
                // Disconnect the source through an ordinary block edit. A zero motor command
                // alone leaves the input rotor coasting, which may finish the current stroke.
                world.setBlockAndUpdate(MIXERS[1].west(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            if (disconnectedAt > 0 && time == disconnectedAt + 80)
                world.setBlockAndUpdate(MIXERS[1].west(), AllBlocks.COGWHEEL.getDefaultState()
                    .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
            if (time == 10 || (time - 10) % 80 == 0 && time < 490) {
                int step = Math.min(COMMANDS.length - 1, Math.max(0, (time - 10) / 80));
                for (int i = 0; i < MIXERS.length; i++) {
                    var motor = (CreativeMotorBlockEntity) world.getBlockEntity(MIXERS[i].west().above());
                    motor.generatedSpeed.setValue(i == 0 ? 32 : i == 1 ? COMMANDS[step] : 128);
                    motor.updateGeneratedRotation(); motor.sendData();
                }
            }
            if (time > 510) finished = true;
        } catch (Throwable failure) { serverFailure = failure.toString(); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
