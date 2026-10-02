package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.kinetics.ElasticKinetics;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.KineticGoggleInfo;
import com.diliupengyun.differential_create.kinetics.LoadFactor;
import com.diliupengyun.differential_create.kinetics.NetworkLoad;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.diliupengyun.differential_create.kinetics.RotorStatePayload;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlock;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Real machine activity, source aggregation, goggles and wire format in one small network. */
public final class LoadFactorTests {
    public static void run(GameTestHelper h) {
        BlockPos a = new BlockPos(2, 2, 2), b = a.east(4), p = b.east(2);
        var wheelState = AllBlocks.WATER_WHEEL.getDefaultState().setValue(WaterWheelBlock.FACING, Direction.EAST);
        h.setBlock(a, wheelState); h.setBlock(b, wheelState);
        for (int x : new int[]{3, 4, 5, 7}) h.setBlock(new BlockPos(x, 2, 2),
            AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(p, AllBlocks.MECHANICAL_PRESS.getDefaultState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        WaterWheelBlockEntity first = h.getBlockEntity(a), second = h.getBlockEntity(b);
        MechanicalPressBlockEntity press = h.getBlockEntity(p);
        first.setFlowScoreAndUpdate(1);
        h.onEachTick(() -> {
            if (first.flowScore != 1) first.setFlowScoreAndUpdate(1);
        });
        h.runAfterDelay(12, () -> {
            NetworkTopology.flush();
            h.assertTrue(first.hasNetwork() && first.network.equals(second.network)
                && first.network.equals(press.network), "readout includes the entire connected network");
            setSpeed(first, 8);
            fraction(h, first, 0, "idle machinery and accelerating inertia are not load");
            tooltip(h, first, "0%", ChatFormatting.GREEN);
            press.getPressingBehaviour().start(PressingBehaviour.Mode.WORLD);
            setSpeed(first, 12);
            fraction(h, first, .5, "active press needs eight of sixteen available torque");
            tooltip(h, first, "50%", ChatFormatting.GREEN);
            setSpeed(first, 14);
            fraction(h, first, 1, "balanced machine demand");
            tooltip(h, first, "100%", ChatFormatting.YELLOW);
            setSpeed(first, 15);
            fraction(h, first, 2, "demand may exceed current drive");
            tooltip(h, first, "200%", ChatFormatting.RED);
            setSpeed(first, 16);
            h.assertTrue(NetworkLoad.of(first).fraction() == Double.POSITIVE_INFINITY, "finite demand with zero current drive");
            tooltip(h, first, "∞%", ChatFormatting.RED);
            setSpeed(first, 0);
            fraction(h, first, .125, "stopped active press still requests startup torque");

            second.setFlowScoreAndUpdate(1);
            NetworkTopology.flush();
            setSpeed(first, 12);
            fraction(h, first, .25, "parallel wheels sum their available drive");
            fraction(h, second, .25, "every source sees the same aggregate fraction");
            first.setFlowScoreAndUpdate(0); second.setFlowScoreAndUpdate(0);
            NetworkTopology.flush();
            setSpeed(first, 12);
            h.assertTrue(NetworkLoad.of(first).status() == LoadFactor.Status.NO_POWER,
                "a still-spinning disconnected network has no active drive");
            tooltip(h, first, "gui.differentialcreate.goggles.load_no_power", ChatFormatting.GRAY);
            packetRoundTrip(h, first);
            h.succeed();
        });
    }

    private static void setSpeed(KineticBlockEntity source, double speed) {
        var ratios = ((INetworkDynamics) source.getOrCreateNetwork()).differentialcreate$getRatios();
        double reference = ratios.get(source);
        ratios.forEach((be, ratio) -> ElasticKinetics.state(be).physicalSpeed = speed * ratio / reference);
    }
    public static void fraction(GameTestHelper h, KineticBlockEntity source, double expected, String message) {
        var actual = NetworkLoad.of(source);
        h.assertTrue(actual.status() == LoadFactor.Status.AVAILABLE && Math.abs(expected - actual.fraction()) < 1e-5,
            message + ": expected " + expected + ", got " + actual);
    }
    public static void tooltip(GameTestHelper h, KineticBlockEntity source, String expected, ChatFormatting color) {
        var lines = new ArrayList<Component>();
        KineticGoggleInfo.append(source, lines, false);
        Component line = lines.stream().filter(c -> c.getContents() instanceof TranslatableContents text
            && text.getKey().equals("gui.differentialcreate.goggles.load_factor")).findFirst().orElseThrow();
        var text = (TranslatableContents) line.getContents();
        h.assertTrue(text.getArgs().length == 1 && text.getArgs()[0] instanceof Component,
            "one load-factor value uses the existing label");
        Component value = (Component) text.getArgs()[0];
        String actual = value.getContents() instanceof TranslatableContents status ? status.getKey() : value.getString();
        h.assertTrue(expected.equals(actual), "load-factor value: expected " + expected + ", got " + actual);
        h.assertTrue(line.getStyle().getColor() != null && line.getStyle().getColor().getValue() == color.getColor(),
            "load-factor color matches its displayed value");
    }
    private static void packetRoundTrip(GameTestHelper h, KineticBlockEntity source) {
        var readings = List.of(new LoadFactor.Reading(LoadFactor.Status.AVAILABLE, .25),
            new LoadFactor.Reading(LoadFactor.Status.AVAILABLE, 2),
            new LoadFactor.Reading(LoadFactor.Status.AVAILABLE, Double.POSITIVE_INFINITY),
            LoadFactor.Reading.NO_POWER, new LoadFactor.Reading(LoadFactor.Status.BRAKING, 0), LoadFactor.Reading.UNKNOWN);
        for (var reading : readings) {
            var payload = new RotorStatePayload(h.getLevel().dimension().location(), source.network, 123.5, 57.125, reading,
                List.of(new RotorStatePayload.Rotor(source.getBlockPos(), -12, 45.25, -16, 0, 12.25),
                    new RotorStatePayload.Rotor(source.getBlockPos().above(), 512, 90.5, 0, .125f, 44.875),
                    new RotorStatePayload.Rotor(source.getBlockPos().below(), 8192, -180.25, 0, 1, 0)));
            var buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                RotorStatePayload.STREAM_CODEC.encode(buf, payload);
                h.assertTrue(payload.equals(RotorStatePayload.STREAM_CODEC.decode(buf)) && !buf.isReadable(),
                    "server load, rotor state and local/network elastic energies survive the network codec: " + reading);
            } finally { buf.release(); }
        }
    }
}
