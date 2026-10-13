package com.diliupengyun.differential_create.trains;

import java.util.*;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ClientTrainKinetics;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record TrainStatePayload(ResourceLocation dimension, int entity, UUID train, int gear, boolean lever,
                                double speed, double throttle, double engineRpm, String failure, List<Rotor> rotors) implements CustomPacketPayload {
    public record Rotor(BlockPos pos, float rpm, double turns) {}
    public static final Type<TrainStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "train_state"));
    public static final StreamCodec<FriendlyByteBuf, TrainStatePayload> STREAM_CODEC = StreamCodec.of((buf, p) -> {
        buf.writeResourceLocation(p.dimension); buf.writeVarInt(p.entity); buf.writeUUID(p.train); buf.writeVarInt(p.gear); buf.writeBoolean(p.lever);
        buf.writeDouble(p.speed); buf.writeDouble(p.throttle); buf.writeDouble(p.engineRpm); buf.writeUtf(p.failure, 64); buf.writeVarInt(p.rotors.size());
        for (var r : p.rotors) { buf.writeBlockPos(r.pos); buf.writeFloat(r.rpm); buf.writeDouble(r.turns); }
    }, buf -> {
        var dimension = buf.readResourceLocation(); int entity = buf.readVarInt(); var train = buf.readUUID(); int gear = buf.readVarInt(); boolean lever = buf.readBoolean();
        double speed = buf.readDouble(), throttle = buf.readDouble(), rpm = buf.readDouble(); String failure = buf.readUtf(64); int count = buf.readVarInt();
        if (entity < 0 || gear < 0 || gear > 3 || count < 0 || count > 32768 || !Double.isFinite(speed) || !Double.isFinite(throttle) || !Double.isFinite(rpm)) throw new IllegalArgumentException("Invalid train state");
        var rotors = new ArrayList<Rotor>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos(); float r = buf.readFloat(); double angle = buf.readDouble();
            if (!Float.isFinite(r) || !Double.isFinite(angle)) throw new IllegalArgumentException("Invalid train rotor");
            rotors.add(new Rotor(pos, r, angle));
        }
        return new TrainStatePayload(dimension, entity, train, gear, lever, speed, throttle, rpm, failure, rotors);
    });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(TrainStatePayload payload, IPayloadContext context) { context.enqueueWork(() -> ClientTrainKinetics.apply(payload)); }

    public static void send(Train train) {
        var s = TrainPhysics.state(train); if (s.ticks % 2 != 0) return;
        boolean lever = TrainPhysics.hasLever(train);
        for (var carriage : train.carriages) {
            var cs = TrainPhysics.carriage(carriage); if (cs.layout == null) continue;
            var rotors = new LinkedHashMap<BlockPos, Rotor>();
            for (var motor : cs.layout.motors) {
                var engine = cs.engines.get(motor.pos());
                rotors.put(motor.pos(), new Rotor(motor.pos(), (float) engine.rpm(), engine.turns));
            }
            carriage.forEachPresentEntity(entity -> PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity,
                new TrainStatePayload(entity.level().dimension().location(), entity.getId(), train.id, s.gear, lever,
                    train.speed, train.throttle, s.engineRpm, s.failure, List.copyOf(rotors.values()))));
        }
    }
}
