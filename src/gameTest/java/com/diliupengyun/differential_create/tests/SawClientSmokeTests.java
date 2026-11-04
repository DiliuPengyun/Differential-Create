package com.diliupengyun.differential_create.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Real server-built saws, recipe inventory and ordinary renderer; no pose is prescribed. */
public final class SawClientSmokeTests {
    private static final BlockPos[] SAWS = {new BlockPos(0, -58, 0), new BlockPos(4, -58, 0), new BlockPos(8, -58, 0)};
    private static final BlockPos[] TREES = {new BlockPos(-4, -58, -7), new BlockPos(3, -58, -7), new BlockPos(10, -58, -7)};
    private static final Path REPORT = Path.of(System.getProperty("differentialcreate.sawClientReport", "build/saw-client/frames"));
    private record Sample(SawBlockEntity owner, SawItemRenderingAudit.Pose pose) {}
    private static final Map<BlockPos, Sample> previous = new HashMap<>();
    private static final java.util.List<String> rows = new ArrayList<>();
    private static volatile int age;
    private static volatile boolean finished;
    private static volatile String serverFailure;
    private static ServerLevel world;
    private static CompletableFuture<?> fixture;
    private static boolean requested, positioned, done, rebuilt, sawFrozen, sawStepped;
    private static int frames, sampledTick = -1, samples, heldSamples, captured = -1, disconnectedAt;
    private static long deadline;
    private static Vec3 frozenPose;

    public static void frame(RenderFrameEvent.Post event) {
        if (done) return;
        var mc = Minecraft.getInstance();
        try {
            if (!requested) {
                if (mc.getOverlay() != null || mc.screen == null || ++frames < 30) return;
                requested = true; deadline = System.nanoTime() + 180_000_000_000L;
                mc.options.pauseOnLostFocus = false; mc.options.renderDistance().set(3);
                mc.options.simulationDistance().set(5); mc.options.framerateLimit().set(90); mc.options.enableVsync().set(false);
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                var rules = new GameRules(); rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                mc.createWorldOpenFlows().createFreshLevel("saw_verification", new LevelSettings("Saw client verification",
                    GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(44, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
                return;
            }
            require(System.nanoTime() < deadline, "Saw fixture timed out");
            require(serverFailure == null, serverFailure);
            if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
            if (fixture == null) {
                Files.createDirectories(REPORT); mc.setScreen(null); mc.options.hideGui = true;
                fixture = mc.getSingleplayerServer().submit(() -> {
                    world = mc.getSingleplayerServer().overworld(); world.setDayTime(6000);
                    for (var pos : SAWS) {
                        world.setBlockAndUpdate(pos, sawState());
                        world.setBlockAndUpdate(pos.south(), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
                        world.setBlockAndUpdate(pos.south(2), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH));
                    }
                    for (int i = 0; i < TREES.length; i++) {
                        var root = TREES[i]; var saw = root.east();
                        world.setBlockAndUpdate(saw, AllBlocks.MECHANICAL_SAW.getDefaultState().setValue(SawBlock.FACING, Direction.WEST));
                        // Keep the last-root fixture disconnected from birth;
                        // setting a previously running source to zero does not brake it.
                        if (i != 2) world.setBlockAndUpdate(saw.east(), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
                        world.setBlockAndUpdate(saw.east(2), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
                        for (int x = 0; x < (i == 0 ? 1 : 2); x++) for (int z = 0; z < (i == 0 ? 1 : 2); z++) {
                            var base = root.west(x).south(z);
                            world.setBlockAndUpdate(base.below(), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
                            for (int y = 0; y < 5; y++) world.setBlockAndUpdate(base.above(y),
                                (i == 0 ? net.minecraft.world.level.block.Blocks.OAK_LOG : net.minecraft.world.level.block.Blocks.JUNGLE_LOG).defaultBlockState());
                        }
                    }
                    var player = world.players().getFirst(); player.getAbilities().flying = true; player.onUpdateAbilities();
                    player.teleportTo(4, -55.7, 8); player.setYRot(180); player.setXRot(20); age = 1;
                    return null;
                });
                return;
            }
            if (!fixture.isDone()) return; fixture.join();
            if (!positioned) { mc.player.setPos(4, -55.7, 8); mc.player.setYRot(180); mc.player.setXRot(20); positioned = true; }
            int tick = mc.levelRenderer.getTicks();
            if (tick != sampledTick) {
                for (var pos : SAWS) {
                    if (!(mc.level.getBlockEntity(pos) instanceof SawBlockEntity saw) || saw.inventory.isEmpty()) { previous.remove(pos); continue; }
                    var pose = SawItemRenderingAudit.pose(saw); var old = previous.put(pos, new Sample(saw, pose));
                    if (old != null && old.owner() == saw && sampledTick + 1 == tick && (age < 295 || age > 355)
                        && old.pose().stock().equals(pose.stock()) && old.pose().applied() == pose.applied()
                        && old.pose().duration() == pose.duration())
                        require(old.pose().end().distanceToSqr(pose.start()) < 1e-10, "World saw item jumps at tick boundary " + age);
                    if (Math.abs(saw.getSpeed()) < 1e-5) {
                        require(pose.start().distanceToSqr(pose.end()) < 1e-10, "A stalled saw item still moves within a tick"); heldSamples++;
                    }
                    rows.add(age + "," + tick + "," + pos.getX() + "," + saw.getSpeed() + "," + saw.inventory.remainingTime
                        + "," + saw.inventory.appliedRecipe + "," + pose.start() + "," + pose.end() + "," + pose.stock()); samples++;
                }
                sampledTick = tick;
            }
            if (age >= 314 && age <= 325 || age >= 340 && age <= 347) {
                var saw = (SawBlockEntity) mc.level.getBlockEntity(SAWS[0]);
                require(!saw.inventory.isEmpty(), "The frozen-step saw lost its workpiece");
                var pose = SawItemRenderingAudit.center(saw, 1);
                if (frozenPose == null) frozenPose = pose;
                if (age <= 325) { require(frozenPose.distanceToSqr(pose) < 1e-10, "Frozen saw item drifts"); sawFrozen = true; }
                else if (frozenPose.distanceToSqr(pose) > 1e-8) {
                    require(SawItemRenderingAudit.center(saw, 0).distanceToSqr(pose) < 1e-10, "Single-step saw item loses its endpoint on refreeze");
                    sawStepped = true;
                }
            }
            if (!rebuilt && age >= 250) { mc.levelRenderer.allChanged(); rebuilt = true; }
            if (age > 20 && age / 50 > captured) {
                captured = age / 50;
                try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(REPORT.resolve(String.format("world-%03d.png", age))); }
            }
            if (finished) {
                require(samples > 500 && heldSamples > 20 && rebuilt && sawFrozen && sawStepped,
                    "Incomplete saw coverage: samples=" + samples + ", held=" + heldSamples + ", freeze=" + sawFrozen + ", stepped=" + sawStepped);
                Files.write(REPORT.resolve("samples.csv"), rows);
                DifferentialCreate.LOGGER.info("DC_SAW_CLIENT_PASS: {} actual item mesh samples, {} stalled samples, speed changes, reversal, rebuild, freeze and single step; single trunk, supported thick trunk and last-root cutting", samples, heldSamples);
                done = true; mc.stop();
            }
        } catch (Throwable failure) {
            try { Files.write(REPORT.resolve("samples.csv"), rows); } catch (Exception ignored) {}
            DifferentialCreate.LOGGER.error("DC_SAW_CLIENT_FAIL", failure); done = true; mc.stop();
        }
    }
    public static void tick(ServerTickEvent.Post event) {
        if (done || world == null || age == 0 || finished) return;
        try {
            int time = ++age;
            if (time == 290) { world.setBlockAndUpdate(SAWS[0], net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()); world.setBlockAndUpdate(SAWS[0], sawState()); }
            if (time == 300) world.getServer().tickRateManager().setFrozen(true);
            if (time == 330) world.getServer().tickRateManager().stepGameIfPaused(1);
            if (time == 350) world.getServer().tickRateManager().setFrozen(false);
            if (time == 10) {
                for (int i = 0; i < TREES.length; i++) {
                    var motor = (CreativeMotorBlockEntity) world.getBlockEntity(TREES[i].east(3));
                    motor.generatedSpeed.setValue(64);
                    motor.updateGeneratedRotation(); motor.sendData();
                }
            }
            if (time == 140) {
                require(world.getBlockState(TREES[0]).isAir() && world.getBlockState(TREES[0].above()).isAir(), "A single trunk was not actually felled");
                require(world.getBlockState(TREES[1]).isAir() && world.getBlockState(TREES[1].above()).is(net.minecraft.world.level.block.Blocks.JUNGLE_LOG), "Cutting one root should leave the supported thick trunk standing");
                require(world.getBlockState(TREES[2]).is(net.minecraft.world.level.block.Blocks.JUNGLE_LOG), "The last-root fixture already cut its target before removing the other roots");
                for (var pos : java.util.List.of(TREES[2].west(), TREES[2].south(), TREES[2].west().south())) world.destroyBlock(pos, true);
                world.setBlockAndUpdate(TREES[2].east(2), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            }
            for (int i = 0; i < SAWS.length; i++) {
                var pos = SAWS[i]; var saw = (SawBlockEntity) world.getBlockEntity(pos);
                if (time == 10 || (time - 10) % 80 == 0 && time < 490) {
                    int[] speeds = {8, 128, 0, -64, 256, 0};
                    var motor = (CreativeMotorBlockEntity) world.getBlockEntity(pos.south(2));
                    motor.generatedSpeed.setValue(i == 0 ? 8 : i == 2 ? 64 : speeds[Math.min(5, Math.max(0, (time - 10) / 80))]);
                    motor.updateGeneratedRotation(); motor.sendData();
                }
                if (time > 12 && saw.inventory.isEmpty()) {
                    var input = saw.getBehaviour(DirectBeltInputBehaviour.TYPE);
                    var movement = saw.getItemMovementVec();
                    if (input != null) input.handleInsertion(new ItemStack(Items.OAK_LOG), Direction.getNearest(movement.x, movement.y, movement.z), false);
                }
            }
            var variable = (SawBlockEntity) world.getBlockEntity(SAWS[1]);
            if (disconnectedAt == 0 && time < 80 && variable.inventory.remainingTime > 10 && variable.inventory.remainingTime < 32) {
                disconnectedAt = time; world.setBlockAndUpdate(SAWS[1].south(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            if (disconnectedAt > 0 && time == disconnectedAt + 80)
                world.setBlockAndUpdate(SAWS[1].south(), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
            if (time > 510) {
                require(world.getBlockState(TREES[1].above()).is(net.minecraft.world.level.block.Blocks.JUNGLE_LOG), "The thick tree fell while other roots still supported it");
                for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) for (int y = 0; y < 5; y++) {
                    var pos = TREES[2].west(x).south(z).above(y);
                    require(world.getBlockState(pos).isAir(), "The last-root cut left " + world.getBlockState(pos) + " at " + pos
                        + "; roots=" + world.getBlockState(TREES[2]) + "," + world.getBlockState(TREES[2].west())
                        + "," + world.getBlockState(TREES[2].south()) + "," + world.getBlockState(TREES[2].west().south())
                        + "; saw=" + ((SawBlockEntity) world.getBlockEntity(TREES[2].east())).getSpeed());
                }
                finished = true;
            }
        } catch (Throwable failure) { serverFailure = failure.toString(); }
    }
    private static net.minecraft.world.level.block.state.BlockState sawState() {
        return AllBlocks.MECHANICAL_SAW.getDefaultState().setValue(SawBlock.FACING, Direction.UP).setValue(SawBlock.AXIS_ALONG_FIRST_COORDINATE, false);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
