package com.diliupengyun.differential_create.tests;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.simibubi.create.AllFluids;
import com.simibubi.create.content.fluids.particle.FluidParticleData;
import com.simibubi.create.content.fluids.spout.SpoutBlockEntity;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.registration.PonderLocalization;
import net.createmod.ponder.foundation.registration.PonderSceneRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;

/** Capture the actual particle calls, including script emitters, from native storyboards. */
final class PonderSpoutAudit {
    private static final Field FLUID = field(FluidParticleData.class, "fluid");
    private record Emission(int tick, int phase, Vec3 position, Fluid fluid) {}

    static void verify() {
        check("fluid_tank", "fluid_tank_storage", new BlockPos(4, 3, 0), new Vec3(3.5, 3, 1.5), AllFluids.CHOCOLATE.get(), 1);
        check("spout", "spout_filling", new BlockPos(2, 3, 2), new Vec3(2.5, 3, 2.5), AllFluids.HONEY.get(), 3);
        DifferentialCreate.LOGGER.info("DC_PONDER_SPOUT_PARTICLES: moved nozzle, receiving item, actual fluid, completion timing and replay passed");
    }

    private static void check(String component, String id, BlockPos source, Vec3 nozzle, Fluid fluid, int batches) {
        for (var pair : PonderIndex.getSceneAccess().getRegisteredEntries()) {
            var entry = pair.getValue();
            if (!entry.getNamespace().equals("create") || !entry.getComponent().equals(ResourceLocation.fromNamespaceAndPath("create", component))) continue;
            var world = new ParticleWorld(source, nozzle, fluid);
            PonderSceneRegistry.loadSchematic(entry.getSchematicLocation()).placeInWorld(world, BlockPos.ZERO, BlockPos.ZERO,
                new StructurePlaceSettings(), world.random, Block.UPDATE_CLIENTS);
            world.createBackup();
            var scene = PonderSceneRegistry.compileScene(new PonderLocalization(), entry, world);
            if (!scene.getId().getPath().equals(id)) continue;
            List<Emission> first = null;
            for (boolean skipping : new boolean[] {false, true}) {
                scene.begin(); world.emissions.clear();
                for (int tick = 0; tick < 1200; tick++) {
                    if (skipping) scene.forEach(element -> element.whileSkipping(scene));
                    scene.tick();
                }
                long streams = world.emissions.stream().filter(e -> e.phase() >= 8).count();
                long splashes = world.emissions.stream().filter(e -> e.phase() == 5).count();
                require(streams > 0 && splashes == 20L * batches,
                    id + " must have a real stream and exactly one completion splash per filled item: " + streams + "/" + splashes);
                if (first == null) first = List.copyOf(world.emissions);
                else require(first.equals(world.emissions), id + " particle positions, fluid and timing changed on replay");
            }
            return;
        }
        throw new AssertionError("Missing spout particle fixture: " + id);
    }

    private static final class ParticleWorld extends PonderLevel {
        private final BlockPos source;
        private final Vec3 nozzle;
        private final Fluid fluid;
        private final List<Emission> emissions = new ArrayList<>();

        private ParticleWorld(BlockPos source, Vec3 nozzle, Fluid fluid) {
            super(BlockPos.ZERO, Minecraft.getInstance().level);
            this.source = source; this.nozzle = nozzle; this.fluid = fluid;
        }

        @Override public void addParticle(ParticleOptions data, double x, double y, double z, double dx, double dy, double dz) {
            require(!(data instanceof FluidParticleData), "A script emitted fluid particles independently of actual filling");
        }

        @Override public void addAlwaysVisibleParticle(ParticleOptions data, double x, double y, double z, double dx, double dy, double dz) {
            if (!(data instanceof FluidParticleData)) return;
            var spout = (SpoutBlockEntity) getBlockEntity(source);
            int phase = spout.processingTicks;
            require(phase >= 8 && phase < SpoutBlockEntity.FILLING_TIME || phase == 5,
                "Fluid particles appeared outside the real filling stroke: " + phase);
            var expected = phase == 5 ? nozzle.add(0, -19 / 16d, 0) : nozzle;
            var position = new Vec3(x, y, z);
            require(position.distanceToSqr(expected) < 1e-10,
                "Spout particle missed the displayed nozzle/receiving item: " + position + " expected " + expected);
            if (phase >= 8) require(Math.abs(dx) < 1e-8 && Math.abs(dz) < 1e-8 && Math.abs(dy + .1) < 1e-8,
                "The filling stream must fall toward the item");
            try {
                var contents = (FluidStack) FLUID.get(data);
                require(!contents.isEmpty() && contents.getFluid().isSame(fluid), "Particle fluid differs from the filled item");
                emissions.add(new Emission(scene.getCurrentTime(), phase, position, contents.getFluid()));
            } catch (IllegalAccessException failure) { throw new AssertionError(failure); }
        }
    }

    private static Field field(Class<?> type, String name) {
        try { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
