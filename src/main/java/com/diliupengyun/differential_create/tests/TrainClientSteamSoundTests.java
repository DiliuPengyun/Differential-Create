package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.diliupengyun.differential_create.client.ClientTrainKinetics;
import com.diliupengyun.differential_create.client.SteamSoundAnimation;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.diliupengyun.differential_create.trains.TrainStatePayload;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageSounds;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Observe actual native sound requests, including the transformed CarriageSounds.tick. */
public final class TrainClientSteamSoundTests {
    private TrainClientSteamSoundTests() {}

    public static void verify(Minecraft mc, CarriageContraptionEntity entity) {
        var original = ClientTrainKinetics.state(entity.trainId);
        var tag = entity.getContraption().getBlocks().get(BlockPos.ZERO).nbt().getCompound("BogeyData");
        var saved = tag.copy(); var position = entity.position(); var previousSounds = entity.sounds;
        var train = entity.getCarriage().train;
        int carriageIndex = entity.carriageIndex, honkTicks = train.honkTicks;
        Boolean lowHonk = train.lowHonk;
        float accumulatedRelease = train.accumulatedSteamRelease;
        var heard = new ArrayList<SoundInstance>();
        Consumer<PlaySoundEvent> capture = event -> {
            heard.add(event.getOriginalSound());
            // Verify requests without piling test exhaust or stopping bursts into the speakers.
            if (event.getOriginalSound().getLocation().equals(AllSoundEvents.STEAM.getMainEvent().getLocation())
                || event.getOriginalSound().getLocation().equals(SoundEvents.LAVA_EXTINGUISH.getLocation())
                || event.getOriginalSound().getLocation().equals(SoundEvents.WOODEN_TRAPDOOR_CLOSE.getLocation())) {
                event.getOriginalSound().resolve(mc.getSoundManager());
                event.setSound(null);
            }
        };
        NeoForge.EVENT_BUS.addListener(PlaySoundEvent.class, capture);
        int cases = 0;
        try {
            train.honkTicks = 0;
            train.accumulatedSteamRelease = 0;
            var dce = entity.getCarriage().getDimensional(mc.level);
            if (dce.leadingAnchor() == null || dce.trailingAnchor() == null) throw new AssertionError("Sound fixture has no native wheel anchors");
            for (int rpm : new int[]{30, 120, 512, -30, -120, -512}) for (float supply : new float[]{0, .1f, 1})
                for (boolean moving : new boolean[]{false, true}) {
                    tag.putBoolean(SteamBogey.ENGINE, true);
                    entity.setPos(moving ? entity.getPrevPositionVec().add(0, 0, .25) : entity.getPrevPositionVec());
                    var sounds = new CarriageSounds(entity); entity.sounds = sounds; heard.clear();
                    try {
                        for (int tick = 0; tick <= 20; tick++) {
                            sample(mc, entity, rpm, tick * rpm / 1200d, supply);
                            sounds.tick(dce);
                        }
                        var steam = steam(heard);
                        int expected = supply == 0 ? 0 : (int) Math.floor(Math.abs(rpm) / 30d);
                        if (steam.size() != expected) throw new AssertionError("Exhaust cadence: rpm=" + rpm + " supply=" + supply
                            + " moving=" + moving + " expected=" + expected + " heard=" + steam.size());
                        for (var sound : steam) {
                            var at = entity.toGlobalVector(net.minecraft.world.phys.Vec3.atCenterOf(BlockPos.ZERO).add(0, .5, 0), 1);
                            if (Math.abs(sound.getX() - at.x) > 1e-5 || Math.abs(sound.getY() - at.y) > 1e-5 || Math.abs(sound.getZ() - at.z) > 1e-5
                                || Math.abs(sound.getVolume() - .35f * Math.sqrt(supply)) > 1e-5)
                                throw new AssertionError("Exhaust comes from its supplied engine at its admission volume");
                        }
                        if (heard.stream().noneMatch(s -> s.getLocation().equals(AllSoundEvents.TRAIN.getMainEvent().getLocation())))
                            throw new AssertionError("Replacing steam also removed the native rolling loop");
                        cases++;
                    } finally { sounds.stop(); }
                }
            // The powered carriage need not be first; ordinary bogeys and stalled inputs have no exhaust beats.
            for (int index : new int[]{0, 1}) for (boolean installed : new boolean[]{false, true}) for (int rpm : new int[]{0, 120}) {
                entity.carriageIndex = index; tag.putBoolean(SteamBogey.ENGINE, installed);
                var sounds = new CarriageSounds(entity); entity.sounds = sounds; heard.clear();
                try {
                    for (int tick = 0; tick <= 20; tick++) { sample(mc, entity, rpm, tick * rpm / 1200d, 1); sounds.tick(dce); }
                    if (steam(heard).size() != (installed && rpm != 0 ? 4 : 0)) throw new AssertionError("Steam used carriage order or an ordinary bogey");
                    cases++;
                } finally { sounds.stop(); }
            }
            entity.carriageIndex = 0; tag.putBoolean(SteamBogey.ENGINE, true);
            entity.setPos(entity.getPrevPositionVec());
            var sounds = new CarriageSounds(entity); entity.sounds = sounds;
            try {
                sample(mc, entity, 120, 0, 1); sounds.tick(dce);
                stale(entity); heard.clear(); sounds.tick(dce);
                if (!steam(heard).isEmpty()) throw new AssertionError("Missing network updates kept steam playing");
                cases++;
                cases += stops(mc, entity, dce, heard);
                train.lowHonk = false; train.honkTicks = 20; heard.clear(); sounds.tick(dce);
                if (heard.stream().noneMatch(s -> s.getLocation().equals(AllSoundEvents.WHISTLE_TRAIN_MANUAL_END.getMainEvent().getLocation())))
                    throw new AssertionError("Replacing steam suppressed the native whistle");
                cases++;
            } finally { sounds.stop(); }
            var animation = new SteamSoundAnimation();
            if (animation.advance(0, 120, 1) || !animation.advance(.5, 120, 1) || animation.advance(.4, 120, 1)
                || animation.advance(.5, 120, 1) || !animation.advance(1, 120, 1)
                || animation.advance(1.5, 120, 0) || animation.advance(10, 120, 1) || !animation.advance(10.5, 120, 1)
                || animation.advance(10.4, -120, 1) || !animation.advance(9.9, -120, 1)
                || animation.advance(Double.NaN, 120, 1) || animation.advance(20, 120, 1))
                throw new AssertionError("Corrections, cutoff, reconnect or direction changes replayed old exhaust beats");
            cases++;
            DifferentialCreate.LOGGER.info("DC_TRAIN_STEAM_SOUND_PASS: {} native cadence, coast/neutral, reverse, admission, bogey/carriage, stall, stale, stop and horn checks", cases);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        finally {
            NeoForge.EVENT_BUS.unregister(capture);
            tag.getAllKeys().stream().toList().forEach(tag::remove); tag.merge(saved);
            entity.setPos(position); entity.carriageIndex = carriageIndex; entity.sounds = previousSounds;
            train.honkTicks = honkTicks; train.lowHonk = lowHonk;
            train.accumulatedSteamRelease = accumulatedRelease;
            ClientTrainKinetics.apply(original);
        }
    }

    private static List<SoundInstance> steam(List<SoundInstance> heard) {
        return heard.stream().filter(s -> s.getLocation().equals(AllSoundEvents.STEAM.getMainEvent().getLocation())).toList();
    }
    private static int stops(Minecraft mc, CarriageContraptionEntity entity,
        com.simibubi.create.content.trains.entity.Carriage.DimensionalCarriageEntity dce, List<SoundInstance> heard) {
        int cases = 0; var train = entity.getCarriage().train; var previous = entity.sounds;
        for (int direction : new int[]{1, -1}) {
            var sounds = new CarriageSounds(entity); entity.sounds = sounds; train.accumulatedSteamRelease = 0;
            try {
                heard.clear(); motion(mc, entity, dce, sounds, 0, 40);
                if (!steam(heard).isEmpty()) throw new AssertionError("A newly loaded stationary train emitted a stopping hiss");
                cases++;
                heard.clear(); motion(mc, entity, dce, sounds, direction * .25, 5); motion(mc, entity, dce, sounds, 0, 60);
                if (!steam(heard).isEmpty()) throw new AssertionError("A tiny movement bypassed the native stopping-release threshold");
                cases++;
                heard.clear(); motion(mc, entity, dce, sounds, direction * .5, 40);
                if (!steam(heard).isEmpty()) throw new AssertionError("Native wheel-timed steam leaked into coasting without engine admission");
                float release = train.accumulatedSteamRelease;
                if (release <= 1) throw new AssertionError("Actual native carriage motion did not prepare its stopping release");
                motion(mc, entity, dce, sounds, 0, 60); stoppingBurst(heard, release);
                cases++;
                heard.clear(); motion(mc, entity, dce, sounds, 0, 100);
                if (!steam(heard).isEmpty() || heard.stream().anyMatch(sound -> sound.getLocation().equals(SoundEvents.LAVA_EXTINGUISH.getLocation())
                    || sound.getLocation().equals(SoundEvents.WOODEN_TRAPDOOR_CLOSE.getLocation())))
                    throw new AssertionError("Remaining parked repeated the stopping release");
                cases++;
                heard.clear(); motion(mc, entity, dce, sounds, direction * .5, 40);
                release = train.accumulatedSteamRelease;
                motion(mc, entity, dce, sounds, 0, 60); stoppingBurst(heard, release);
                cases++;
            } finally { sounds.stop(); entity.sounds = previous; }
        }
        return cases;
    }
    private static void motion(Minecraft mc, CarriageContraptionEntity entity,
        com.simibubi.create.content.trains.entity.Carriage.DimensionalCarriageEntity dce, CarriageSounds sounds, double speed, int ticks) {
        entity.setPos(entity.getPrevPositionVec().add(0, 0, speed));
        for (int tick = 0; tick < ticks; tick++) {
            sample(mc, entity, 0, 0, 0); sounds.tick(dce);
        }
    }
    private static void stoppingBurst(List<SoundInstance> heard, float release) {
        var steam = steam(heard);
        if (steam.size() != 1 || Math.abs(steam.getFirst().getPitch() - .5f) > 1e-6
            || Math.abs(steam.getFirst().getVolume() - .75f * release / 10) > 1e-6
            || heard.stream().filter(sound -> sound.getLocation().equals(SoundEvents.LAVA_EXTINGUISH.getLocation())).count() != 1
            || heard.stream().filter(sound -> sound.getLocation().equals(SoundEvents.WOODEN_TRAPDOOR_CLOSE.getLocation())).count() != 1)
            throw new AssertionError("A forward/reverse stop did not play exactly one native pressure-release sound composite: steam=" + steam.size());
    }
    private static void sample(Minecraft mc, CarriageContraptionEntity entity, int rpm, double turns, float supply) {
        var payload = new TrainStatePayload(mc.level.dimension().location(), entity.getId(), entity.trainId, 0, true,
            0, supply, rpm, "", List.of(new TrainStatePayload.Rotor(BlockPos.ZERO, rpm, turns, supply)));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            TrainStatePayload.STREAM_CODEC.encode(buffer, payload);
            var decoded = TrainStatePayload.STREAM_CODEC.decode(buffer);
            if (!payload.equals(decoded)) throw new AssertionError("Rotor supply or phase lost in native network codec");
            ClientTrainKinetics.apply(decoded);
        } finally { buffer.release(); }
    }
    @SuppressWarnings("unchecked")
    private static void stale(CarriageContraptionEntity entity) throws ReflectiveOperationException {
        var field = ClientTrainKinetics.class.getDeclaredField("CARRIAGES"); field.setAccessible(true);
        var cache = (Map<CarriageContraptionEntity, ClientTrainKinetics.CarriageSample>) field.get(null);
        cache.put(entity, new ClientTrainKinetics.CarriageSample(cache.get(entity).rotors(), ClientKineticAngles.renderTick() - 11));
    }
}
