package com.diliupengyun.differential_create.kinetics.portable;

import java.util.UUID;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/** World state changes happen only on accepted poses or game ticks, never in a solver trial. */
public final class DockingState {
    /** Supplies measured preview poses and real storage callbacks to the same state machine. */
    public interface Preview {
        boolean live();
        boolean valid();
        void open();
        void close();
    }
    private Preview preview;
    public void preview(Preview actor, double axial) {
        if (preview != null || context != null) return;
        preview = actor; managed = true; distance = (float) Math.clamp(axial - 1, 0, 2);
        ticks = stable = 0; phase = Phase.PREPARING; failure = "none";
    }
    public enum Phase { IDLE, PREPARING, BRAKING, LOCKING, TRANSFER, RETRACTING }
    public static final int ANIMATION = 4;
    public final PortableStorageInterfaceBlockEntity owner;
    public Phase phase = Phase.IDLE;
    public boolean managed, opening;
    public double torque, speed, pointSpeed, dissipated;
    public String failure = "none";
    public MovementContext context;
    private long generation, lastTick = Long.MIN_VALUE;
    private long deadline;
    private UUID storageEntity;
    private int ticks, remaining, stable;
    private float distance;
    private Vec3 point = Vec3.ZERO, previousPoint, lockedPoint;
    private boolean contact;
    private UUID restoreEntity;
    private long restoreActor;
    private Phase restorePhase;
    private int restoreRemaining;

    public DockingState(PortableStorageInterfaceBlockEntity owner) { this.owner = owner; }
    private PortableEndpoint bridge() { return (PortableEndpoint) owner; }
    public float distance() { return distance; }
    public boolean occupied() { return contact && phase != Phase.IDLE && phase != Phase.PREPARING; }
    public float extension() {
        return phase == Phase.IDLE ? 0 : phase == Phase.PREPARING ? Math.min(1, ticks / (float) ANIMATION)
            : phase == Phase.RETRACTING ? Math.max(0, 1 - ticks / (float) ANIMATION) : 1;
    }
    public int signal() {
        if (!managed) return permission() ? 15 : 0;
        if (owner.getLevel() != null && owner.getLevel().isClientSide) return phase == Phase.TRANSFER ? 15 : 0;
        return permission() ? 15 : 0;
    }
    private boolean loaded() {
        var level = owner.getLevel();
        return level != null && !owner.isRemoved()
            && (com.diliupengyun.differential_create.kinetics.PhysicsPreview.enabled(level)
                || level.getChunkSource().hasChunk(owner.getBlockPos().getX() >> 4, owner.getBlockPos().getZ() >> 4))
            && level.getBlockEntity(owner.getBlockPos()) == owner;
    }
    public boolean permission() {
        if (!loaded() || owner.isPowered() || owner.getLevel().hasNeighborSignal(owner.getBlockPos())) return false;
        if (!managed) return bridge().differentialcreate$nativePermission();
        return phase == Phase.TRANSFER && (context != null || preview != null) && live() && contact
            && PortableDocking.time(owner.getLevel()) < deadline
            && (preview != null ? preview.valid() : PortableDocking.validGeometry(this));
    }
    public DockingSession session() { return new DockingSession(this, generation, storageEntity); }
    public boolean valid(DockingSession session) {
        return session.generation() == generation && storageEntity != null && storageEntity.equals(session.entity()) && permission();
    }
    public boolean accepts(Contraption contraption) {
        return !managed || context == null || context.contraption == contraption;
    }
    public void nativeStarted(Contraption contraption) {
        generation++;
        storageEntity = contraption.entity == null ? null : contraption.entity.getUUID();
    }
    public void nativeClosed() {
        generation++; storageEntity = null;
        if (managed && phase != Phase.IDLE && phase != Phase.RETRACTING) retract();
    }
    public boolean claim(MovementContext actor, double axial) {
        if (context != null && context != actor || !loaded() || owner.isPowered()) return false;
        managed = true; context = actor;
        distance = (float) Math.clamp(axial - 1, 0, 2);
        ticks = stable = 0; phase = Phase.PREPARING; failure = "none";
        if (restoreEntity != null && actor.contraption.entity.getUUID().equals(restoreEntity) && actor.localPos.asLong() == restoreActor) {
            remaining = restoreRemaining;
            if (restorePhase == Phase.RETRACTING) { phase = Phase.RETRACTING; contact = true; }
        } else restoreEntity = null;
        changed(); return true;
    }
    private boolean live() {
        if (preview != null) return preview.live();
        var entity = context == null ? null : context.contraption.entity;
        return entity != null && entity.isAlive() && entity.getContraption() == context.contraption && !context.disabled;
    }
    public void observe(Vec3 position, double rpm, double velocity, boolean functional, double rating) {
        if (context == null && preview == null) return;
        point = position; speed = Math.abs(rpm); pointSpeed = Math.abs(velocity);
        if (!live()) { release("missing"); return; }
        if (phase == Phase.PREPARING && ticks >= ANIMATION && functional) {
            contact = true; phase = Phase.BRAKING; ticks = stable = 0; changed();
        }
        if (occupied() && !functional) { release("alignment"); return; }
        torque = occupied() ? rating : 0;
        if (contact && phase != Phase.BRAKING && lockedPoint != null
            && (speed > .05 || point.distanceTo(lockedPoint) > .002)) release("overload");
    }
    public void transferred() {
        if (permission()) owner.onContentTransferred();
    }
    public void renew() {
        if (managed && phase == Phase.TRANSFER) {
            remaining = bridge().differentialcreate$timeout(); deadline = PortableDocking.time(owner.getLevel()) + remaining; owner.setChanged();
        }
    }
    /** Client prediction only advances head animation between authoritative phase packets. */
    public void clientTick() {
        var level = owner.getLevel();
        if (level == null || !level.isClientSide || level.tickRateManager().isFrozen()) return;
        if (phase == Phase.PREPARING || phase == Phase.RETRACTING) ticks = Math.min(ANIMATION, ticks + 1);
    }
    public void tick() {
        var level = owner.getLevel();
        if (level == null || level.isClientSide && !com.diliupengyun.differential_create.kinetics.PhysicsPreview.enabled(level)
            || level.tickRateManager().isFrozen() || lastTick == PortableDocking.time(level)) return;
        lastTick = PortableDocking.time(level);
        if (!managed) return;
        if ((context != null || preview != null) && (!live() || !loaded())) { release("missing"); return; }
        if (owner.isPowered() && phase != Phase.IDLE && phase != Phase.RETRACTING) cancel();
        ticks++;
        switch (phase) {
            case BRAKING -> {
                boolean stopped = speed <= .05 && pointSpeed <= .001
                    && previousPoint != null && point.distanceTo(previousPoint) <= .002;
                stable = stopped ? stable + 1 : 0;
                if (stable >= 1) { phase = Phase.LOCKING; ticks = 0; lockedPoint = point; changed(); }
            }
            case LOCKING -> {
                if (ticks >= 1 && live()) {
                    phase = Phase.TRANSFER; ticks = 0;
                    boolean restoring = restoreEntity != null && restorePhase == Phase.TRANSFER;
                    if (!restoring) remaining = bridge().differentialcreate$timeout();
                    deadline = lastTick + remaining;
                    restoreEntity = null;
                    opening = true;
                    try { if (preview != null) preview.open(); else bridge().differentialcreate$openStorage(context.contraption, distance); }
                    finally { opening = false; }
                    changed();
                }
            }
            case TRANSFER -> { remaining = (int) Math.max(0, deadline - lastTick); if (remaining <= 0) cancel(); }
            case RETRACTING -> { if (ticks >= ANIMATION) release("none"); }
            default -> { }
        }
        previousPoint = point;
        bridge().differentialcreate$animate(extension(), distance);
        owner.setChanged();
    }
    public void cancel() {
        if (!managed || phase == Phase.IDLE || phase == Phase.RETRACTING) return;
        retract();
        bridge().differentialcreate$closeStorage();
        if (preview != null) preview.close();
    }
    private void retract() {
        generation++; storageEntity = null; phase = Phase.RETRACTING; ticks = 0;
        changed();
    }
    public void release(String reason) {
        if (preview != null) { preview.close(); preview = null; }
        if (context != null) {
            if (reason.equals("none")) {
                var drive = com.diliupengyun.differential_create.kinetics.ActorDrive.of(context);
                if (drive != null && drive.root().getVehicle() instanceof net.minecraft.world.entity.vehicle.AbstractMinecart cart)
                    com.diliupengyun.differential_create.kinetics.MinecartPhysics.state(cart).dockingStart = true;
            }
            if (reason.equals("missing")) context.data.remove("DCPortCooldown");
            else context.data.putLong("DCPortCooldown", owner.getBlockPos().asLong());
            context.data.remove("WorkingPos"); context.stall = false;
            PortableDocking.unbind(context, this);
        }
        boolean hadStorage = storageEntity != null;
        generation++; storageEntity = null; context = null; contact = false; torque = 0;
        previousPoint = lockedPoint = null; phase = Phase.IDLE; ticks = stable = 0; failure = reason;
        if (hadStorage) bridge().differentialcreate$closeStorage();
        bridge().differentialcreate$animate(0, distance);
        changed();
    }
    public void suspend() {
        if (context != null && context.contraption.entity != null) {
            restoreEntity = context.contraption.entity.getUUID(); restoreActor = context.localPos.asLong();
            restorePhase = phase; restoreRemaining = phase == Phase.TRANSFER
                ? (int) Math.max(0, deadline - PortableDocking.time(owner.getLevel())) : remaining;
        }
        release("missing");
    }
    public void invalidate() { suspend(); }
    public void nativeConnection() { if (context == null && preview == null) { managed = false; phase = Phase.IDLE; contact = false; torque = 0; } }
    public void changed() {
        var level = owner.getLevel();
        if (level == null || level.isClientSide || owner.isRemoved()) return;
        owner.setChanged(); owner.sendData();
        level.updateNeighbourForOutputSignal(owner.getBlockPos(), owner.getBlockState().getBlock());
    }
    public void write(CompoundTag tag) {
        tag.putBoolean("Managed", managed); tag.putString("Phase", phase.name()); tag.putInt("Ticks", ticks);
        tag.putInt("Remaining", phase == Phase.TRANSFER && owner.getLevel() != null && !owner.getLevel().isClientSide
            ? (int) Math.max(0, deadline - PortableDocking.time(owner.getLevel())) : remaining);
        tag.putBoolean("Contact", contact); tag.putFloat("Distance", distance);
        tag.putDouble("Speed", speed); tag.putDouble("Load", torque); tag.putDouble("Heat", dissipated);
        tag.putString("Failure", failure);
        if (context != null && context.contraption.entity != null) {
            tag.putUUID("Entity", context.contraption.entity.getUUID()); tag.putLong("Actor", context.localPos.asLong());
        } else if (restoreEntity != null) {
            tag.putUUID("Entity", restoreEntity); tag.putLong("Actor", restoreActor);
            tag.putString("Phase", restorePhase.name()); tag.putInt("Remaining", restoreRemaining);
        }
    }
    public void read(CompoundTag tag, boolean packet) {
        managed = tag.getBoolean("Managed");
        Phase saved;
        try { saved = Phase.valueOf(tag.getString("Phase")); } catch (IllegalArgumentException ignored) { saved = Phase.IDLE; }
        distance = Math.clamp(tag.getFloat("Distance"), 0, 2);
        remaining = Math.max(0, tag.getInt("Remaining")); ticks = Math.clamp(tag.getInt("Ticks"), 0, ANIMATION);
        speed = tag.getDouble("Speed"); torque = tag.getDouble("Load"); dissipated = tag.getDouble("Heat"); failure = tag.getString("Failure");
        if (packet) { phase = saved; contact = tag.getBoolean("Contact"); }
        else {
            generation++; storageEntity = null; phase = Phase.IDLE; contact = false;
            if (tag.hasUUID("Entity")) {
                restoreEntity = tag.getUUID("Entity"); restoreActor = tag.getLong("Actor");
                restorePhase = saved; restoreRemaining = remaining;
            }
        }
    }
}
