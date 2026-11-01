package com.diliupengyun.differential_create.kinetics;

import java.util.HashMap;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Publish the actual placed blocks before entity tracking removes their former
 * owner. Vanilla's deferred chunk broadcast can otherwise arrive a tick later.
 * Updates are grouped by section, and only go to players tracking that chunk. */
public final class ContraptionBlockUpdates {
    private ContraptionBlockUpdates() {}

    public static void placed(ServerLevel world, Contraption contraption, StructureTransform transform) {
        var sections = new HashMap<SectionPos, ShortOpenHashSet>();
        for (var local : contraption.getBlocks().keySet()) {
            var pos = transform.apply(local);
            if (world.isOutsideBuildHeight(pos)) continue;
            sections.computeIfAbsent(SectionPos.of(pos), key -> new ShortOpenHashSet()).add(SectionPos.sectionRelativePos(pos));
        }
        for (var entry : sections.entrySet()) {
            var section = entry.getKey();
            var chunkPos = new ChunkPos(section.x(), section.z());
            var players = world.getChunkSource().chunkMap.getPlayers(chunkPos, false);
            if (players.isEmpty()) continue;
            var chunk = world.getChunk(section.x(), section.z());
            var packet = new ClientboundSectionBlocksUpdatePacket(section, entry.getValue(),
                chunk.getSection(world.getSectionIndexFromSectionY(section.y())));
            players.forEach(player -> player.connection.send(packet));
            for (short local : entry.getValue()) {
                var be = chunk.getBlockEntity(section.relativeToBlockPos(local));
                var data = be == null ? null : be.getUpdatePacket();
                if (data != null) players.forEach(player -> player.connection.send(data));
            }
        }
    }
}
