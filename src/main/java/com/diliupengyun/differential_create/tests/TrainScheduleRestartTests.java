package com.diliupengyun.differential_create.tests;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import com.diliupengyun.differential_create.trains.TrainPhysics;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime.State;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static com.diliupengyun.differential_create.tests.TrainScheduleScenario.*;

/** Native SavedData + region files across separate JVMs; resume never calls Train.read. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainScheduleRestartTests {
    private TrainScheduleRestartTests() {}
    @GameTestGenerator public static Collection<TestFunction> cases() {
        String phase = System.getProperty("differentialcreate.trains.restart", "");
        if (!phase.matches("schedule_(prepare|resume)_(pre|transit|waiting|signal)")) return List.of();
        String[] parts = phase.split("_"); String mode = parts[2]; boolean resume = parts[1].equals("resume");
        return List.of(new TestFunction("train_" + phase, "train_" + phase, "differentialcreate_trains:kineticphysicstests.physics_test", 2600, 0, true,
            h -> { if (resume) resume(h, mode); else create(h, "flat", 2, "both", s -> prepare(s, mode)); }));
    }
    private static Path path(GameTestHelper h) { return h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("schedule-restart.nbt"); }
    private static void force(GameTestHelper h, BlockPos position) {
        var chunk = new ChunkPos(position);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) h.getLevel().setChunkForced(chunk.x + x, chunk.z + z, true);
        h.getLevel().getChunkAt(position);
    }
    private static void prepare(TrainScheduleScenario s, String mode) {
        // Keep persisted carriage entities outside the next GameTest's reset volume.
        s.train().forEachTravellingPoint(p -> p.position += 900);
        for (var c : s.train().carriages) {
            c.updateContraptionAnchors(); force(s.h, BlockPos.containing(c.getLeadingPoint().getPosition(s.train().graph))); c.manageEntities(s.h.getLevel());
        }
        s.after(25, () -> {
            s.h.assertTrue(s.train().carriages.stream().allMatch(c -> c.anyAvailableEntity() != null), "disk checkpoint has real loaded carriages outside the reset volume");
            var origin = s.park(1); var target = s.station(1, 12, "Restart destination");
            if (mode.equals("pre") || mode.equals("transit")) for (var c : s.train().carriages) {
                c.storage.getFluids().drain(Integer.MAX_VALUE, FluidAction.EXECUTE); TrainPhysics.carriage(c).water.replaceAll((pos, amount) -> 0d);
            }
            if (mode.equals("signal")) {
                var point = s.train().carriages.getFirst().getLeadingPoint(); var net = new TrainScheduleNetwork(s);
                net.signal(point.node1, point.node2, point.position + 6, net.group(), net.group(), false, true);
            }
            s.schedule(false, entry(mode.equals("waiting") ? origin : target, delay(80)));
            Runnable save = () -> {
                s.train().runtime.paused = true;
                var tag = new CompoundTag(); tag.putUUID("Train", s.train().id); tag.putUUID("Target", mode.equals("waiting") ? origin.id : target.id);
                tag.put("Runtime", s.train().runtime.write(s.h.getLevel().registryAccess()));
                tag.putInt("Water", s.f.water()); tag.putInt("Coal", s.f.items(Items.COAL));
                tag.putLong("PreviousJvm", ProcessHandle.current().pid());
                tag.putLong("Centre", BlockPos.containing(s.train().carriages.getFirst().getLeadingPoint().getPosition(s.train().graph)).asLong());
                if (s.train().navigation.waitingForSignal != null) tag.putUUID("Signal", s.train().navigation.waitingForSignal.getFirst());
                try { NbtIo.writeCompressed(tag, path(s.h)); } catch (IOException e) { throw new IllegalStateException(e); }
                Create.RAILWAYS.markTracksDirty(); s.h.getLevel().getServer().saveEverything(true, true, false);
                System.out.println("DC_SCHEDULE_RESTART_PREPARE_PASS mode=" + mode + " jvm=" + ProcessHandle.current().pid());
                s.h.succeed(); // Retain the actual manager, groups, cargo and region data.
            };
            switch (mode) {
                case "pre" -> { s.train().runtime.paused = true; s.after(3, save); }
                case "transit" -> s.until(() -> s.train().runtime.state == State.IN_TRANSIT && s.train().navigation.destination == target,
                    10, "in-transit disk checkpoint is ready", save);
                case "waiting" -> s.until(() -> s.train().runtime.state == State.POST_TRANSIT && s.train().runtime.conditionContext.getFirst().getInt("Time") >= 15,
                    40, "partially completed delay is ready", save);
                case "signal" -> s.until(() -> s.train().navigation.waitingForSignal != null && s.train().speed == 0 && s.train().navigation.distanceToSignal < .1,
                    1000, "actual red-signal stop is ready", save);
            }
        });
    }
    private static void resume(GameTestHelper h, String mode) {
        final CompoundTag tag;
        try { tag = NbtIo.readCompressed(path(h), NbtAccounter.unlimitedHeap()); } catch (IOException e) { throw new IllegalStateException("Resume requires this scenario's saved trainRunId", e); }
        h.assertTrue(ProcessHandle.current().pid() != tag.getLong("PreviousJvm"), "schedule resume runs in a new server JVM");
        var train = Create.RAILWAYS.trains.get(tag.getUUID("Train")); h.assertTrue(train != null, "native manager loads the timetable train from disk");
        var s = TrainScheduleScenario.restored(h, train); force(h, BlockPos.of(tag.getLong("Centre")));
        s.after(25, () -> {
            var target = train.graph.getPoint(EdgePointType.STATION, tag.getUUID("Target"));
            h.assertTrue(target != null && train.carriages.size() == 2 && train.hasForwardConductor() && train.hasBackwardConductor(), "native disk load restores the station, both carriages and captured conductors");
            h.assertTrue(tag.getCompound("Runtime").equals(train.runtime.write(h.getLevel().registryAccess()))
                && s.f.water() == tag.getInt("Water") && s.f.items(Items.COAL) == tag.getInt("Coal"),
                "disk restart preserves exact entry, phase, wait contexts, pause state and paid inventory");
            if (mode.equals("signal")) {
                h.assertTrue(train.navigation.waitingForSignal != null && train.navigation.waitingForSignal.getFirst().equals(tag.getUUID("Signal")), "disk restart preserves the actual blocking signal");
                train.graph.getPoint(EdgePointType.SIGNAL, tag.getUUID("Signal")).blockEntities.getFirst().replaceAll((pos, power) -> false);
            }
            train.carriages.getFirst().storage.getFluids().fill(new FluidStack(Fluids.WATER, 4000), FluidAction.EXECUTE);
            train.runtime.paused = false;
            Runnable finish = () -> { System.out.println("DC_SCHEDULE_RESTART_RESUME_PASS mode=" + mode + " jvm=" + ProcessHandle.current().pid() + " previous=" + tag.getLong("PreviousJvm")); s.succeed(); };
            if (mode.equals("waiting")) {
                int remaining = 80 - train.runtime.conditionContext.getFirst().getInt("Time");
                s.after(remaining - 2, () -> {
                    h.assertTrue(!train.runtime.completed, "restarted partial delay cannot finish early");
                    s.until(() -> train.runtime.completed, 8, "restarted delay finishes its saved remainder", finish);
                });
            } else s.arrived(target, finish);
        });
    }
}
