package com.diliupengyun.differential_create.client;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/** Terrain and contraption meshes must exchange ownership in the same frame.
 * Block update packets do not preserve UPDATE_IMMEDIATE, so distant or
 * player-priority sections otherwise rebuild after the entity has disappeared.
 * Only dirty, visible sections touched by a recent handoff are built here. */
public final class ContraptionRenderHandoff {
    private static final Map<Long, Pending> sections = new HashMap<>();
    private static ClientLevel level;
    private static final class Pending {
        final long expires;
        boolean first = true;
        Pending(long expires) { this.expires = expires; }
    }
    private ContraptionRenderHandoff() {}

    public static void reset(ClientLevel next) { sections.clear(); level = next; }

    public static void assembled(AbstractContraptionEntity entity) {
        if (!(entity.level() instanceof ClientLevel world) || entity.getContraption() == null) return;
        if (level != world) reset(world);
        var contraption = entity.getContraption();
        long expires = System.nanoTime() + 2_000_000_000L;
        for (var pos : contraption.getBlocks().keySet()) mark(pos.offset(contraption.anchor), expires);
    }

    public static void disassembled(AbstractContraptionEntity entity, StructureTransform transform) {
        if (!(entity.level() instanceof ClientLevel world) || entity.getContraption() == null) return;
        if (level != world) reset(world);
        long expires = System.nanoTime() + 2_000_000_000L;
        for (var pos : entity.getContraption().getBlocks().keySet()) mark(transform.apply(pos), expires);
    }

    private static void mark(BlockPos pos, long expires) {
        // Faces at a section boundary also change the neighbouring mesh.
        for (int x = (pos.getX() - 1) >> 4; x <= ((pos.getX() + 1) >> 4); x++)
            for (int y = (pos.getY() - 1) >> 4; y <= ((pos.getY() + 1) >> 4); y++)
                for (int z = (pos.getZ() - 1) >> 4; z <= ((pos.getZ() + 1) >> 4); z++)
                    sections.put(SectionPos.asLong(x, y, z), new Pending(expires));
    }

    public static void beforeTerrain(ClientLevel world,
            Collection<SectionRenderDispatcher.RenderSection> visible, SectionRenderDispatcher dispatcher) {
        if (world != level) reset(world);
        if (world == null || sections.isEmpty()) return;
        long now = System.nanoTime();
        sections.values().removeIf(p -> p.expires < now);
        RenderRegionCache cache = null;
        for (var section : visible) {
            var pos = SectionPos.of(section.getOrigin());
            var pending = sections.get(pos.asLong());
            if (pending == null || (!pending.first && !section.isDirty()) || !section.hasAllNeighbors()
                || !world.getLightEngine().lightOnInSection(pos)) continue;
            if (cache == null) cache = new RenderRegionCache();
            // This cancels an older asynchronous build. The normal upload pass
            // below installs the replacement before drawing any terrain.
            dispatcher.rebuildSectionSync(section, cache);
            section.setNotDirty();
            pending.first = false;
        }
    }
}
