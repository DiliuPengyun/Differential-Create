package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Structure packet: the full member table of one kinetic network
 * (BlockPos → speed ratio), sent on topology/propagation events. The client
 * replaces its member→network mapping for this network. An empty member
 * list means the network was dissolved.
 */
public record NetworkStructurePayload(ResourceLocation dimension, long networkId,
	List<Entry> entries) implements CustomPacketPayload {

	public record Entry(BlockPos pos, float ratio) {}

	public static final Type<NetworkStructurePayload> TYPE = new Type<>(
		ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "network_structure"));

	private static final StreamCodec<FriendlyByteBuf, Entry> ENTRY_CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, Entry::pos,
		ByteBufCodecs.FLOAT, Entry::ratio,
		Entry::new);

	public static final StreamCodec<FriendlyByteBuf, NetworkStructurePayload> STREAM_CODEC = StreamCodec.composite(
		ResourceLocation.STREAM_CODEC, NetworkStructurePayload::dimension,
		ByteBufCodecs.VAR_LONG, NetworkStructurePayload::networkId,
		ByteBufCodecs.collection(ArrayList::new, ENTRY_CODEC), NetworkStructurePayload::entries,
		NetworkStructurePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** Applied on the client thread. */
	public static void handle(NetworkStructurePayload payload, IPayloadContext context) {
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, payload.dimension);
		context.enqueueWork(() -> ClientNetworkSpeeds.applyStructure(dimension, payload.networkId, payload.entries));
	}

}
