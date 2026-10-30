package com.diliupengyun.differential_create.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.MachiningClock;
import com.google.gson.GsonBuilder;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Opt-in timing of an ordinary loaded world. Does not change its machinery,
 * game rules, tick rate, view distance or entity population. Development only. */
@EventBusSubscriber(modid = DifferentialCreate.MODID)
public final class PhysicsPerformanceAudit {
    private static final String destination = System.getProperty("differentialcreate.performanceReport", "");
    private static final int warmup = Integer.getInteger("differentialcreate.performanceWarmup", 200);
    private static final int count = Integer.getInteger("differentialcreate.performanceSamples", 2400);
    private static final ArrayList<Double> durations = new ArrayList<>();
    private static final ArrayList<Long> starts = new ArrayList<>();
    private static final ArrayList<String> rows = new ArrayList<>();
    private static int age;
    private static long started, firstStart, previousStart, lastStart;
    private static boolean done;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void before(ServerTickEvent.Pre event) {
        started = 0;
        if (destination.isEmpty() || done || event.getServer().getPlayerCount() == 0
            || !event.getServer().tickRateManager().runsNormally() || ++age <= warmup) return;
        started = System.nanoTime();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void after(ServerTickEvent.Post event) {
        if (started == 0 || done) return;
        double ms = (System.nanoTime() - started) / 1e6;
        if (durations.isEmpty()) {
            firstStart = started;
            rows.add("sample,tick_ms,start_interval_ms");
        }
        durations.add(ms);
        starts.add(started);
        rows.add(String.format(Locale.ROOT, "%d,%.6f,%.6f", durations.size(), ms,
            previousStart == 0 ? 0 : (started - previousStart) / 1e6));
        previousStart = lastStart = started;
        if (durations.size() % 200 == 0)
            System.out.printf(Locale.ROOT, "[DC performance] %d/%d ticks, last %.3f ms%n", durations.size(), count, ms);
        if (durations.size() < count) return;
        done = true;
        try {
            double[] sorted = durations.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            var report = new LinkedHashMap<String, Object>();
            report.put("samples", sorted.length);
            report.put("warmupTicks", warmup);
            report.put("wallSeconds", (lastStart - firstStart) / 1e9);
            report.put("tps", Math.min(20, (sorted.length - 1) * 1e9 / (lastStart - firstStart)));
            report.put("meanMs", java.util.Arrays.stream(sorted).average().orElse(0));
            report.put("medianMs", percentile(sorted, .5));
            report.put("p95Ms", percentile(sorted, .95));
            report.put("p99Ms", percentile(sorted, .99));
            report.put("maxMs", sorted[sorted.length - 1]);
            report.put("ticksOver50Ms", durations.stream().filter(value -> value > 50).count());
            report.put("minimum10SecondTps", minimumTps(200));
            report.put("minimum60SecondTps", minimumTps(1200));
            var machines = new LinkedHashMap<String, Integer>();
            int networks = 0;
            for (var level : event.getServer().getAllLevels()) for (var network : MachiningClock.networks(level)) {
                networks++;
                for (var be : network.members.keySet()) machines.merge(be.getClass().getSimpleName(), 1, Integer::sum);
            }
            report.put("networks", networks);
            report.put("loadedKineticMembers", machines);
            var folder = Path.of(destination);
            Files.createDirectories(folder);
            Files.write(folder.resolve("ticks.csv"), rows);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(report);
            Files.writeString(folder.resolve("summary.json"), json);
            System.out.println("[DC performance] complete " + json);
            if (Boolean.getBoolean("differentialcreate.performanceExit") && net.neoforged.fml.loading.FMLEnvironment.dist.isClient())
                net.minecraft.client.Minecraft.getInstance().execute(() -> net.minecraft.client.Minecraft.getInstance().stop());
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot save physics performance audit", failure);
        }
    }
    private static double percentile(double[] sorted, double fraction) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)];
    }
    private static Double minimumTps(int ticks) {
        if (starts.size() <= ticks) return null;
        long longest = 0;
        for (int end = ticks; end < starts.size(); end++)
            longest = Math.max(longest, starts.get(end) - starts.get(end - ticks));
        return Math.min(20, ticks * 1e9 / longest);
    }
}
