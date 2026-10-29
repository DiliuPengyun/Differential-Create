package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Actual local rotor states; clients never reconstruct an elastic network from one average speed. */
public record RotorStatePayload(ResourceLocation dimension, long networkId, double energy, double elasticEnergy,
    LoadFactor.Reading loadFactor, List<Rotor> rotors)
    implements CustomPacketPayload {
    public record BearingPose(int entityId, double angle) {}
    /** Angle is the rendered hand angle; speed and inertia use its fixed input-equivalent coordinate. */
    public record ClockPose(int entityId, double angle, double speed, double inertia) {
        public double kineticEnergy() { return .5 * inertia * speed * speed; }
    }
    public record OutputPose(double angle) {}
    public record FeedPose(float speed, double inertia, double brake, double heat) {}
    public record LinearPose(int entityId, double offset, boolean arrived) {}
    public record Rotor(BlockPos pos, float speed, double angle, float torque, float steamIntake, double elasticEnergy,
        BearingPose bearing, OutputPose output, FeedPose feed, List<ClockPose> clock, LinearPose linear) {
        public Rotor(BlockPos pos, float speed, double angle, float torque, float steamIntake, double elasticEnergy,
                     BearingPose bearing, OutputPose output, FeedPose feed) {
            this(pos, speed, angle, torque, steamIntake, elasticEnergy, bearing, output, feed, List.of(), null);
        }
        public Rotor(BlockPos pos, float speed, double angle, float torque, float steamIntake, double elasticEnergy, BearingPose bearing, OutputPose output) {
            this(pos, speed, angle, torque, steamIntake, elasticEnergy, bearing, output, null);
        }
        public Rotor(BlockPos pos, float speed, double angle, float torque, float steamIntake, double elasticEnergy) {
            this(pos, speed, angle, torque, steamIntake, elasticEnergy, null, null);
        }
        public Rotor(BlockPos pos, float speed, double angle, float torque, float steamIntake, double elasticEnergy, BearingPose bearing) {
            this(pos, speed, angle, torque, steamIntake, elasticEnergy, bearing, null);
        }
    }
    public static final Type<RotorStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "rotor_state"));
    public static final StreamCodec<FriendlyByteBuf, RotorStatePayload> STREAM_CODEC = StreamCodec.of((buf, p) -> {
        buf.writeResourceLocation(p.dimension); buf.writeVarLong(p.networkId); buf.writeDouble(p.energy);
        buf.writeDouble(p.elasticEnergy);
        buf.writeEnum(p.loadFactor.status()); buf.writeDouble(p.loadFactor.fraction());
        buf.writeVarInt(p.rotors.size());
        for (Rotor r : p.rotors) {
            buf.writeBlockPos(r.pos); buf.writeFloat(r.speed); buf.writeDouble(r.angle); buf.writeFloat(r.torque);
            buf.writeFloat(r.steamIntake);
            buf.writeDouble(r.elasticEnergy);
            buf.writeVarInt(r.bearing == null ? 0 : r.bearing.entityId + 1);
            if (r.bearing != null) buf.writeDouble(r.bearing.angle);
            buf.writeBoolean(r.output != null);
            if (r.output != null) buf.writeDouble(r.output.angle);
            buf.writeBoolean(r.feed != null);
            if (r.feed != null) { buf.writeFloat(r.feed.speed); buf.writeDouble(r.feed.inertia); buf.writeDouble(r.feed.brake); buf.writeDouble(r.feed.heat); }
            buf.writeVarInt(r.clock.size());
            for (var hand : r.clock) {
                buf.writeVarInt(hand.entityId()); buf.writeDouble(hand.angle());
                buf.writeDouble(hand.speed()); buf.writeDouble(hand.inertia());
            }
            buf.writeBoolean(r.linear != null);
            if (r.linear != null) { buf.writeVarInt(r.linear.entityId()); buf.writeDouble(r.linear.offset()); buf.writeBoolean(r.linear.arrived()); }
        }
    }, buf -> {
        var dimension = buf.readResourceLocation(); long id = buf.readVarLong(); double energy = buf.readDouble();
        double elasticEnergy = buf.readDouble();
        var load = new LoadFactor.Reading(buf.readEnum(LoadFactor.Status.class), buf.readDouble());
        int size = buf.readVarInt();
        if (size < 0 || size > 32768) throw new IllegalArgumentException("Invalid rotor packet size");
        List<Rotor> rotors = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BlockPos pos = buf.readBlockPos(); float speed = buf.readFloat(); double angle = buf.readDouble();
            float torque = buf.readFloat(), steamIntake = buf.readFloat(); double localEnergy = buf.readDouble();
            if (!Float.isFinite(steamIntake) || steamIntake < 0 || steamIntake > 1)
                throw new IllegalArgumentException("Invalid steam intake");
            int entity = buf.readVarInt();
            if (entity < 0) throw new IllegalArgumentException("Invalid bearing entity id");
            BearingPose bearing = entity == 0 ? null : new BearingPose(entity - 1, buf.readDouble());
            OutputPose output = buf.readBoolean() ? new OutputPose(buf.readDouble()) : null;
            FeedPose feed = buf.readBoolean() ? new FeedPose(buf.readFloat(), buf.readDouble(), buf.readDouble(), buf.readDouble()) : null;
            if (feed != null && (!Float.isFinite(feed.speed) || !Double.isFinite(feed.inertia) || !Double.isFinite(feed.brake) || !Double.isFinite(feed.heat)))
                throw new IllegalArgumentException("Invalid feed rotor state");
            int hands = buf.readVarInt();
            if (hands < 0 || hands > 2) throw new IllegalArgumentException("Invalid clock hand count");
            var clock = new ArrayList<ClockPose>(hands);
            for (int h = 0; h < hands; h++) {
                int handId = buf.readVarInt(); double handAngle = buf.readDouble();
                double handSpeed = buf.readDouble(), handInertia = buf.readDouble();
                if (handId < 0 || !Double.isFinite(handAngle) || !Double.isFinite(handSpeed)
                    || !Double.isFinite(handInertia) || handInertia < 0) throw new IllegalArgumentException("Invalid clock pose");
                clock.add(new ClockPose(handId, handAngle, handSpeed, handInertia));
            }
            LinearPose linear = buf.readBoolean() ? new LinearPose(buf.readVarInt(), buf.readDouble(), buf.readBoolean()) : null;
            if (linear != null && (linear.entityId() < 0 || !Double.isFinite(linear.offset()))) throw new IllegalArgumentException("Invalid linear pose");
            rotors.add(new Rotor(pos, speed, angle, torque, steamIntake, localEnergy, bearing, output, feed, List.copyOf(clock), linear));
        }
        return new RotorStatePayload(dimension, id, energy, elasticEnergy, load, rotors);
    });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(RotorStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientNetworkSpeeds.applyRotors(ResourceKey.create(Registries.DIMENSION, payload.dimension), payload));
    }
}
