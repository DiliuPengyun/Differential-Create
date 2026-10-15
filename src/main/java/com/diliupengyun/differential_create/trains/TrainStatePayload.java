package com.diliupengyun.differential_create.trains;

import java.util.*;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.BoilerAdmission;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.diliupengyun.differential_create.kinetics.SteamGovernor;
import com.diliupengyun.differential_create.client.ClientTrainKinetics;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record TrainStatePayload(ResourceLocation dimension, int entity, UUID train, int gear, boolean lever,
                                double speed, double throttle, double engineRpm, String failure, List<Rotor> rotors,
                                List<Boiler> boilers, double admission, boolean braking) implements CustomPacketPayload {
    public record Rotor(BlockPos pos, float rpm, double turns, float steamSupply) {}
    public record Boiler(BlockPos pos, float supply, int heat, boolean passive, int engines, int whistles,
                         double admission, double demand, double waterFraction) {
        public Boiler(BlockPos pos, float supply, int heat, boolean passive, int engines, int whistles) {
            this(pos, supply, heat, passive, engines, whistles, 1, supply, supply > 0 ? 1 : 0);
        }
        public CompoundTag data() {
            var tag = new CompoundTag(); tag.putFloat("Supply", supply); tag.putInt("ActiveHeat", heat);
            tag.putBoolean("PassiveHeat", passive); tag.putInt("Engines", engines); tag.putInt("Whistles", whistles);
            tag.putDouble(BoilerAdmission.KEY, admission);
            tag.putDouble(BoilerAdmission.DEMAND_KEY, demand);
            tag.putDouble(BoilerAdmission.FRACTION_KEY, waterFraction); return tag;
        }
    }
    public TrainStatePayload(ResourceLocation dimension, int entity, UUID train, int gear, boolean lever,
        double speed, double throttle, double engineRpm, String failure, List<Rotor> rotors) {
        this(dimension, entity, train, gear, lever, speed, throttle, engineRpm, failure, rotors, List.of());
    }
    public TrainStatePayload(ResourceLocation dimension, int entity, UUID train, int gear, boolean lever,
        double speed, double throttle, double engineRpm, String failure, List<Rotor> rotors, List<Boiler> boilers) {
        this(dimension, entity, train, gear, lever, speed, throttle, engineRpm, failure, rotors, boilers, 0, false);
    }
    public static final Type<TrainStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "train_state"));
    public static final StreamCodec<FriendlyByteBuf, TrainStatePayload> STREAM_CODEC = StreamCodec.of((buf, p) -> {
        buf.writeResourceLocation(p.dimension); buf.writeVarInt(p.entity); buf.writeUUID(p.train); buf.writeVarInt(p.gear); buf.writeBoolean(p.lever);
        buf.writeDouble(p.speed); buf.writeDouble(p.throttle); buf.writeDouble(p.engineRpm); buf.writeUtf(p.failure, 64); buf.writeVarInt(p.rotors.size());
        for (var r : p.rotors) { buf.writeBlockPos(r.pos); buf.writeFloat(r.rpm); buf.writeDouble(r.turns); buf.writeFloat(r.steamSupply); }
        buf.writeVarInt(p.boilers.size());
        for (var b : p.boilers) {
            buf.writeBlockPos(b.pos); buf.writeFloat(b.supply); buf.writeVarInt(b.heat); buf.writeBoolean(b.passive);
            buf.writeVarInt(b.engines); buf.writeVarInt(b.whistles);
            buf.writeDouble(b.admission);
            buf.writeDouble(b.demand); buf.writeDouble(b.waterFraction);
        }
        buf.writeDouble(p.admission); buf.writeBoolean(p.braking);
    }, buf -> {
        var dimension = buf.readResourceLocation(); int entity = buf.readVarInt(); var train = buf.readUUID(); int gear = buf.readVarInt(); boolean lever = buf.readBoolean();
        double speed = buf.readDouble(), throttle = buf.readDouble(), rpm = buf.readDouble(); String failure = buf.readUtf(64); int count = buf.readVarInt();
        if (entity < 0 || gear < 0 || gear > TrainMechanics.REVERSE_GEAR || count < 0 || count > 32768 || !Double.isFinite(speed) || !Double.isFinite(throttle) || !Double.isFinite(rpm)) throw new IllegalArgumentException("Invalid train state");
        var rotors = new ArrayList<Rotor>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos(); float r = buf.readFloat(); double angle = buf.readDouble(); float steam = buf.readFloat();
            if (!Float.isFinite(r) || !Double.isFinite(angle) || !Float.isFinite(steam) || steam < 0 || steam > 1)
                throw new IllegalArgumentException("Invalid train rotor");
            rotors.add(new Rotor(pos, r, angle, steam));
        }
        int boilersCount = buf.readVarInt();
        if (boilersCount < 0 || boilersCount > 32768) throw new IllegalArgumentException("Invalid train boilers");
        var boilers = new ArrayList<Boiler>(boilersCount);
        for (int i = 0; i < boilersCount; i++) {
            BlockPos pos = buf.readBlockPos(); float supply = buf.readFloat(); int heat = buf.readVarInt(); boolean passive = buf.readBoolean();
            int engines = buf.readVarInt(), whistles = buf.readVarInt();
            double admission = buf.readDouble();
            double demand = buf.readDouble(), fraction = buf.readDouble();
            if (!Float.isFinite(supply) || supply < 0 || heat < 0 || engines < 0 || whistles < 0
                || !Double.isFinite(admission) || admission < 0 || admission > 1
                || !Double.isFinite(demand) || demand < 0 || !Double.isFinite(fraction) || fraction < 0 || fraction > 1)
                throw new IllegalArgumentException("Invalid train boiler");
            boilers.add(new Boiler(pos, supply, heat, passive, engines, whistles, admission, demand, fraction));
        }
        double admission = buf.readDouble(); boolean braking = buf.readBoolean();
        if (!Double.isFinite(admission) || admission < 0 || admission > 1) throw new IllegalArgumentException("Invalid cab admission");
        return new TrainStatePayload(dimension, entity, train, gear, lever, speed, throttle, rpm, failure, rotors, boilers, admission, braking);
    });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(TrainStatePayload payload, IPayloadContext context) { context.enqueueWork(() -> ClientTrainKinetics.apply(payload)); }

    /** Actual regulator intake, including water scarcity and governor closure. */
    public static List<Rotor> rotors(CarriageSteamState state) {
        if (state.layout == null) return List.of();
        var result = new ArrayList<Rotor>();
        for (var motor : state.layout.motors) {
            var engine = state.engines.get(motor.pos());
            double rated = SourcePhysics.steamCurve(motor.capacity(), 1, 1, 1).maximumPower()
                * TrainMechanics.TORQUE_SCALE * (2 * Math.PI / 60);
            float steam = rated > 0 && Double.isFinite(rated) && Double.isFinite(engine.power)
                ? (float) Math.clamp(engine.power / rated * SteamGovernor.opening(engine.stallTorque, engine.rpm(),
                    engine.governor, engine.governorRange), 0, 1) : 0;
            result.add(new Rotor(motor.pos(), (float) engine.rpm(), engine.turns, steam));
        }
        return List.copyOf(result);
    }

    public static void send(Train train) {
        var s = TrainPhysics.state(train); if (s.ticks % 2 != 0) return;
        boolean lever = TrainPhysics.hasLever(train);
        for (var carriage : train.carriages) {
            var cs = TrainPhysics.carriage(carriage); if (cs.layout == null) continue;
            var rotors = rotors(cs);
            var boilers = new ArrayList<Boiler>();
            for (var entry : cs.boilers.entrySet()) {
                var b = entry.getValue();
                boilers.add(new Boiler(entry.getKey(), b.waterSupply, b.activeHeat, b.passiveHeat, b.attachedEngines, b.attachedWhistles,
                    BoilerAdmission.opening(b), BoilerAdmission.demand(b), BoilerAdmission.fraction(b)));
            }
            carriage.forEachPresentEntity(entity -> PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity,
                new TrainStatePayload(entity.level().dimension().location(), entity.getId(), train.id, s.gear, lever,
                    train.speed, train.throttle, s.engineRpm, s.failure, rotors, List.copyOf(boilers), s.controlsAdmission, s.controlsBraking)));
        }
    }
}
