package com.diliupengyun.differential_create.trains;

import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

/** A boiler attachment on the clicked tank block, independent of its controller and world position. */
public record SteamHosePort(Direction face, Vec3 point) {
    public static final String KEY = "DCSteamHosePort";

    public static SteamHosePort fromHit(BlockPos block, Direction face, Vec3 hit) {
        return normalized(face, hit.subtract(Vec3.atLowerCornerOf(block)));
    }

    private static SteamHosePort normalized(Direction face, Vec3 point) {
        if (face == null || !Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) return null;
        double x = Math.clamp(point.x, 0, 1), y = Math.clamp(point.y, 0, 1), z = Math.clamp(point.z, 0, 1);
        switch (face.getAxis()) {
            case X -> x = face.getStepX() > 0 ? 1 : 0;
            case Y -> y = face.getStepY() > 0 ? 1 : 0;
            case Z -> z = face.getStepZ() > 0 ? 1 : 0;
        }
        return new SteamHosePort(face, new Vec3(x, y, z));
    }

    public Vec3 normal() { return Vec3.atLowerCornerOf(face.getNormal()); }
    public Vec3 position(BlockPos block) { return Vec3.atLowerCornerOf(block).add(point); }
    public SteamHosePort transform(StructureTransform transform) {
        var normal = transform.applyWithoutOffsetUncentered(normal());
        return normalized(Direction.getNearest((float) normal.x, (float) normal.y, (float) normal.z),
            transform.applyWithoutOffset(point));
    }

    public static void write(CompoundTag owner, SteamHosePort port) {
        if (port == null) { owner.remove(KEY); return; }
        var data = new CompoundTag();
        data.putString("Face", port.face.getName());
        data.putDouble("X", port.point.x); data.putDouble("Y", port.point.y); data.putDouble("Z", port.point.z);
        owner.put(KEY, data);
    }

    /** A missing or malformed attachment is not a valid hose endpoint. */
    public static SteamHosePort read(CompoundTag owner) {
        if (!owner.contains(KEY, Tag.TAG_COMPOUND)) return null;
        var data = owner.getCompound(KEY);
        if (!data.contains("X", Tag.TAG_ANY_NUMERIC) || !data.contains("Y", Tag.TAG_ANY_NUMERIC)
            || !data.contains("Z", Tag.TAG_ANY_NUMERIC)) return null;
        double x = data.getDouble("X"), y = data.getDouble("Y"), z = data.getDouble("Z");
        if (x < 0 || x > 1 || y < 0 || y > 1 || z < 0 || z > 1) return null;
        return normalized(Direction.byName(data.getString("Face")), new Vec3(x, y, z));
    }
}
