package com.diliupengyun.differential_create.tests;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.BeltScrollPhase;
import com.diliupengyun.differential_create.client.ChainScrollPhase;
import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltVisual;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.processing.burner.ScrollInstance;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Verifies the world belt surface is re-anchored to the member's accumulated angle. */
public final class BeltClientSmokeTests {
    private static final BlockPos MOTOR = new BlockPos(0, -59, 2);
    private static final BlockPos START = new BlockPos(0, -59, 3);
    private static final BlockPos END = new BlockPos(4, -59, 3);
    private static final BlockPos FLYWHEEL = new BlockPos(0, -59, 4);
    private static final BlockPos CHAIN = new BlockPos(2, -59, 10);
    private static final double PERIOD = 31.5 * 16;
    private static final Field BELTS = beltsField();

    private static boolean requested, done, changed, visualSeen, oldDiverged, chainMathChecked;
    private static int frames, lastTick = -1, samples, largeJumps, phaseChecks;
    private static long deadline;
    private static double previousPhase = Double.NaN, previousOld = Double.NaN;
    private static float minimumSpeed = Float.POSITIVE_INFINITY, maximumSpeed = Float.NEGATIVE_INFINITY;
    private static CompletableFuture<?> fixture;
    private BeltClientSmokeTests() {}

    public static void frame(RenderFrameEvent.Post event) {
        if (done) return;
        var mc = Minecraft.getInstance();
        try {
            if (!requested) {
                if (mc.getOverlay() != null || mc.screen == null || ++frames < 30) return;
                requested = true;
                deadline = System.nanoTime() + 180_000_000_000L;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(2);
                mc.options.simulationDistance().set(5);
                var rules = new GameRules();
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                var settings = new LevelSettings("Belt client smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                    true, rules, WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("belt_verification_" + System.nanoTime(), settings,
                    new WorldOptions(42, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
                return;
            }
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for the belt fixture");
            if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
            if (fixture == null) {
                mc.setScreen(null);
                fixture = mc.getSingleplayerServer().submit(() -> {
                    var level = mc.getSingleplayerServer().overworld();
                    level.setBlockAndUpdate(MOTOR, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                        .setValue(CreativeMotorBlock.FACING, Direction.SOUTH));
                    for (int x = START.getX(); x <= END.getX(); x++)
                        level.setBlockAndUpdate(new BlockPos(x, START.getY(), START.getZ()),
                            AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
                    level.setBlockAndUpdate(FLYWHEEL, flywheelState());
                    // Load ChainConveyorRenderer so its chain-phase redirect is
                    // applied at class load; the isolated block has no connections.
                    level.setBlockAndUpdate(CHAIN, AllBlocks.CHAIN_CONVEYOR.getDefaultState());
                    BeltConnectorItem.createBelts(level, START, END);
                    level.getServer().getPlayerList().getPlayers().getFirst()
                        .teleportTo(2.5, -57, 7.5);
                    var player = level.getServer().getPlayerList().getPlayers().getFirst();
                    player.setYRot(180);
                    player.setXRot(25);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    return null;
                });
                return;
            }
            if (!fixture.isDone()) return;
            fixture.join();
            if (!chainMathChecked) {
                chainMathChecked = true;
                verifyChainPhaseMath();
            }
            if (!(mc.level.getBlockEntity(START) instanceof BeltBlockEntity belt)) return;
            if (mc.getBlockEntityRenderDispatcher().getRenderer(belt) == null)
                throw new AssertionError("Belt fallback renderer is unavailable");
            if (ClientNetworkSpeeds.thetaOf(belt) == null) return;

            int tick = mc.levelRenderer.getTicks();
            if (tick == lastTick) return;
            lastTick = tick;
            if (samples++ < 4) return;

            if (!changed) {
                changed = true;
                mc.getSingleplayerServer().submit(() -> {
                    if (mc.getSingleplayerServer().overworld().getBlockEntity(MOTOR) instanceof CreativeMotorBlockEntity motor)
                        motor.generatedSpeed.setValue(128);
                    return null;
                });
            }

            double phase = BeltScrollPhase.visualRepeats(belt);
            if (Double.isNaN(phase)) return;
            // This east-facing horizontal belt flips the fallback renderer's sign;
            // the helper must still reproduce the same accumulated phase.
            double rendererTicks = BeltScrollPhase.rendererTicks(belt);
            if (Double.isNaN(rendererTicks) || Math.abs(Math.IEEEremainder(
                fract(-rendererTicks / PERIOD) - phase, 1.0)) > 1e-6)
                throw new AssertionError("Fallback belt phase does not match the accumulated angle");
            double old = (double) belt.getSpeed() * ClientKineticAngles.renderTick() / PERIOD;
            float speed = belt.getSpeed();
            minimumSpeed = Math.min(minimumSpeed, speed);
            maximumSpeed = Math.max(maximumSpeed, speed);
            if (!Double.isNaN(previousPhase)) {
                double delta = Math.IEEEremainder(phase - previousPhase, 1.0);
                double expected = Math.abs(speed) / PERIOD;
                if (Math.abs(delta) > Math.max(.05, expected * 1.5 + .01))
                    throw new AssertionError("Belt phase jumped by " + delta + " at " + speed + " RPM");
                double oldDelta = old - previousOld;
                if (Math.abs(oldDelta) > .2 && Math.abs(delta) < .08) largeJumps++;
            }
            previousPhase = phase;
            previousOld = old;

            var visual = beltVisual(mc.level);
            if (visual != null) {
                visualSeen = true;
                var belts = (ScrollInstance[]) BELTS.get(visual);
                if (belts.length >= 2) {
                    double renderTick = ClientKineticAngles.renderTick();
                    for (int i = 0; i < 2; i++) {
                        var instance = belts[i];
                        if (instance == null) throw new AssertionError("Belt visual surface " + i + " is missing");
                        double shader = fract(instance.speedV * renderTick + instance.offsetV);
                        double expected = fract(phase + (i == 0 ? .5 : 0));
                        double error = Math.abs(Math.IEEEremainder(shader - expected, 1.0));
                        if (error > .03)
                            throw new AssertionError("Belt visual phase drifted by " + error + " on surface " + i);
                        double oldError = Math.abs(Math.IEEEremainder(
                            fract(instance.speedV * renderTick + (i == 0 ? .5 : 0)) - expected, 1.0));
                        if (oldError > .1) oldDiverged = true;
                        phaseChecks++;
                    }
                }
            }

            if (samples >= 70) {
                if (!visualSeen) throw new AssertionError("Flywheel belt visual was never created");
                if (maximumSpeed - minimumSpeed < 1) throw new AssertionError("Belt speed never changed");
                if (largeJumps == 0) throw new AssertionError("Time x speed phase never jumped during the ramp");
                if (!oldDiverged) throw new AssertionError("Visual phase still matched the time x speed formula");
                if (phaseChecks == 0) throw new AssertionError("No belt visual surface was checked");
                DifferentialCreate.LOGGER.info(
                    "DC_BELT_CLIENT_PASS: {} samples, speed {}..{} RPM, {} old phase jumps, {} visual checks",
                    samples, minimumSpeed, maximumSpeed, largeJumps, phaseChecks);
                done = true;
                mc.stop();
            }
        } catch (Throwable failure) {
            DifferentialCreate.LOGGER.error("DC_BELT_CLIENT_FAIL", failure);
            done = true;
            mc.stop();
        }
    }

    private static BlockState flywheelState() {
        return AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
    }

    /** The chain phase must be one repeat per 108 degrees and wrap continuously. */
    private static void verifyChainPhaseMath() {
        nearPhase(0, 0, "chain phase at 0 degrees");
        nearPhase(54, .5, "chain phase at 54 degrees");
        nearPhase(108, 0, "chain phase at 108 degrees");
        nearPhase(-54, .5, "chain phase at -54 degrees");
        double before = ChainScrollPhase.phaseFromDegrees(107.9);
        double after = ChainScrollPhase.phaseFromDegrees(108.1);
        if (Math.abs(Math.IEEEremainder(after - before, 1.0)) > .01)
            throw new AssertionError("chain phase does not wrap continuously at a repeat");
    }

    private static void nearPhase(double degrees, double expected, String label) {
        double actual = ChainScrollPhase.phaseFromDegrees(degrees);
        if (Math.abs(Math.IEEEremainder(actual - expected, 1.0)) > 1e-9)
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    private static BeltVisual beltVisual(Level level) {
        var manager = VisualizationManager.get(level);
        if (manager == null) return null;
        try {
            Object blockEntities = manager.blockEntities();
            Object storage = blockEntities.getClass().getMethod("getStorage").invoke(blockEntities);
            Object visual = storage.getClass().getMethod("visualAtPos", long.class).invoke(storage, START.asLong());
            return visual instanceof BeltVisual belt ? belt : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static double fract(double value) {
        return value - Math.floor(value);
    }

    private static Field beltsField() {
        try {
            Field field = BeltVisual.class.getDeclaredField("belts");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
