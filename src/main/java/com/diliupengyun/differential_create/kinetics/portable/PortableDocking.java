package com.diliupengyun.differential_create.kinetics.portable;

import java.lang.ref.WeakReference;
import java.util.*;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceMovement;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.KineticNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Content-independent docking: compatibility, pose sweeps, reservation and finite passive reaction. */
public final class PortableDocking {
    public static final double LINEAR_FORCE = 1024 / MinecartMotion.BLOCKS_PER_TURN;
    public static final double TWIST_TORQUE = 1024;
    private static final Map<Block, Adapter> adapters = new IdentityHashMap<>();
    private static final Set<Block> endpointBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<Level, NavigableMap<BlockPos, WeakReference<DockingState>>> stations = new WeakHashMap<>();
    private static final Map<MovementContext, DockingState> bindings = new WeakHashMap<>();
    private static final Map<Level, Clock> clocks = new WeakHashMap<>();
    private static final class Clock { long serverTick = Long.MIN_VALUE, tick; }
    private PortableDocking() {}

    /** Inheriting Create's portable movement and BE base requires no registration. Other actors may opt in. */
    public interface Adapter {
        default Direction facing(BlockState state) { return state.hasProperty(BlockStateProperties.FACING) ? state.getValue(BlockStateProperties.FACING) : null; }
        default boolean compatible(BlockState moving, BlockState stationary) { return moving.getBlock() == stationary.getBlock(); }
    }
    private static final Adapter DEFAULT = new Adapter() {};
    public static void register(Block block, Adapter adapter) { adapters.put(Objects.requireNonNull(block), Objects.requireNonNull(adapter)); }
    public static void unregister(Block block) { adapters.remove(block); }
    public static boolean endpointBlock(Block block) { return endpointBlocks.contains(block); }
    public static Adapter adapter(BlockState state) {
        var registered = adapters.get(state.getBlock());
        return registered != null ? registered : MovementBehaviour.REGISTRY.get(state) instanceof PortableStorageInterfaceMovement ? DEFAULT : null;
    }
    public static Adapter adapter(MovementContext context) { return adapter(context.state); }
    public static boolean isPortable(MovementContext context) { return adapter(context) != null; }
    public static boolean managed(MovementContext context) {
        return isPortable(context) && adapter(context).facing(context.state) != null
            && ActorDrive.of(context) != null;
    }
    public static void register(DockingState state) {
        if (state.owner.getLevel() == null || state.owner.isVirtual()) return;
        endpointBlocks.add(state.owner.getBlockState().getBlock());
        var registry = stations.computeIfAbsent(state.owner.getLevel(), ignored -> new TreeMap<>());
        var old = registry.get(state.owner.getBlockPos());
        if (old == null || old.get() != state) registry.put(state.owner.getBlockPos(), new WeakReference<>(state));
    }
    public static void remove(DockingState state) {
        var index = stations.get(state.owner.getLevel());
        if (index != null) { var ref = index.get(state.owner.getBlockPos()); if (ref != null && ref.get() == state) index.remove(state.owner.getBlockPos()); }
        state.invalidate();
    }
    public static void clear() { stations.clear(); bindings.clear(); clocks.clear(); }
    /** One active game tick, independent of BE order, solver substeps and time spent globally frozen. */
    public static long time(Level level) {
        if (level.getServer() == null) return level.getGameTime();
        var clock = clocks.computeIfAbsent(level, ignored -> new Clock());
        long now = level.getServer().getTickCount();
        if (clock.serverTick != now) {
            clock.serverTick = now;
            if (!level.tickRateManager().isFrozen()) clock.tick++;
        }
        return clock.tick;
    }
    private static List<DockingState> stations(Level level) {
        var index = stations.get(level);
        if (index == null) return List.of();
        var result = new ArrayList<DockingState>();
        index.entrySet().removeIf(entry -> {
            var state = entry.getValue().get();
            if (state == null || state.owner.isRemoved()
                || !level.getChunkSource().hasChunk(entry.getKey().getX() >> 4, entry.getKey().getZ() >> 4)) return true;
            if (level.getBlockEntity(entry.getKey()) != state.owner) return true;
            result.add(state); return false;
        });
        return result;
    }
    public static void unbind(MovementContext context, DockingState state) { if (bindings.get(context) == state) bindings.remove(context); }
    public static void forget(AbstractContraptionEntity entity) {
        forget(entity, false);
    }
    public static void forget(AbstractContraptionEntity entity, boolean unloading) {
        for (var state : new ArrayList<>(bindings.values()))
            if (state.context != null && state.context.contraption.entity == entity) { if (unloading) state.suspend(); else state.release("missing"); }
    }
    public static void reenable(DockingState state) {
        var level = state.owner.getLevel();
        if (level == null) return;
        for (var entity : level.getEntitiesOfClass(AbstractContraptionEntity.class, new net.minecraft.world.phys.AABB(state.owner.getBlockPos()).inflate(5))) {
            if (entity.getContraption() == null) continue;
            for (var actor : entity.getContraption().getActors()) if (actor.getRight() != null) {
                var context = actor.getRight();
                if (context.data.contains("DCPortCooldown") && context.data.getLong("DCPortCooldown") == state.owner.getBlockPos().asLong()) context.data.remove("DCPortCooldown");
            }
        }
    }
    public record Binding(MovementContext context, ActorDrive drive, List<DockingState> stations) {
        public ActorContactPath path() { return drive.path(context); }
        public ActorContactPath facingPath() { return drive.directionPath(context, Vec3.atLowerCornerOf(adapter(context).facing(context.state).getNormal())); }
        public Vec3 position() {
            var path = path();
            if (path != null) return ContraptionMotion.vector(path.at(drive.travel()));
            var point = drive.entity().toGlobalVector(Vec3.atCenterOf(context.localPos), 1);
            return drive instanceof ContraptionPhysics.Drive shaft ? ContraptionMotion.pendingPosition(shaft, point) : point;
        }
        public Vec3 facing() {
            if (!drive.motionActive() && drive instanceof ContraptionPhysics.Drive shaft)
                return ContraptionMotion.pendingDirection(shaft, drive.entity().applyRotation(Vec3.atLowerCornerOf(adapter(context).facing(context.state).getNormal()), 1));
            var path = facingPath(); return path == null ? drive.entity().applyRotation(Vec3.atLowerCornerOf(adapter(context).facing(context.state).getNormal()), 1)
                : ContraptionMotion.vector(path.at(drive.travel()));
        }
        public double boundary(double direction) {
            var path = path(); var normal = facingPath();
            if (path == null) return Double.POSITIVE_INFINITY;
            double next = Double.POSITIVE_INFINITY, travel = drive.travel();
            for (var station : stations) {
                var state = station.owner.getBlockState();
                if (!adapter(context).compatible(context.state, state) || adapter(context).facing(state) == null) continue;
                Direction facing = adapter(context).facing(state).getOpposite();
                int axis = facing.getAxis().ordinal(); var centre = Vec3.atCenterOf(station.owner.getBlockPos());
                for (int dim = 0; dim < 3; dim++) {
                    double origin = dim == 0 ? centre.x : dim == 1 ? centre.y : centre.z;
                    double sign = facing.getAxisDirection().getStep();
                    double[] planes = dim == axis ? new double[]{origin - sign * 1.5, origin - sign * 3}
                        : new double[]{origin - .5, origin + .5, origin - 1.5, origin + 1.5};
                    for (double plane : planes) next = Math.min(next, path.crossing(travel, direction, dim, plane));
                }
                if (normal != null) next = Math.min(next, normal.crossing(travel, direction, axis, .875 * facing.getAxisDirection().getStep()));
            }
            return Math.min(next, drive.allowance(direction));
        }
        public double torque(DockingState station) {
            if (!station.occupied()) return 0;
            var path = path(); if (path == null) return station.torque;
            Vec3 v = ContraptionMotion.vector(path.derivative(drive.travel()));
            var normal = facingPath();
            double axial = Vec3.atCenterOf(station.owner.getBlockPos()).subtract(position()).dot(facing());
            if (normal != null) v = v.add(ContraptionMotion.vector(normal.derivative(drive.travel())).scale(axial / 2));
            double twist = drive.rotation() && !drive.stabilized() ? Math.abs(drive.angularRate()) / .3 : 0;
            return LINEAR_FORCE * v.length() * 1200 + TWIST_TORQUE * twist;
        }
        public void observe() {
            Vec3 point = position(), facing = facing();
            if (context.disabled || context.contraption.entity == null || context.contraption.entity.isRemoved()) {
                var old = bindings.get(context); if (old != null) old.release("missing"); return;
            }
            context.stall = false;
            DockingState selected = bindings.get(context);
            if (selected == null) {
                for (var candidate : stations) {
                    var geometry = geometry(context, point, facing, candidate, 1.5);
                    if (context.data.contains("DCPortCooldown") && context.data.getLong("DCPortCooldown") == candidate.owner.getBlockPos().asLong()) {
                        // Leave the preparation area with a margin: native and
                        // accepted solver poses can straddle its edge in one tick.
                        if (!geometry(context, point, facing, candidate, 1.75, .75, .25).valid)
                            context.data.remove("DCPortCooldown");
                        continue;
                    }
                    if (!geometry.valid || candidate.owner.isPowered() || candidate.owner.getLevel().hasNeighborSignal(candidate.owner.getBlockPos())) continue;
                    if (!candidate.claim(context, geometry.axial)) continue;
                    selected = candidate; bindings.put(context, selected);
                    context.data.putLong("DCPortManaged", 1);
                    context.data.put("WorkingPos", net.minecraft.nbt.NbtUtils.writeBlockPos(candidate.owner.getBlockPos()));
                    break;
                }
            }
            if (selected == null) return;
            // Preparing heads have no mechanical contact. A small exit margin
            // avoids cancelling an arrival at an exact solver boundary crossing.
            var broad = selected.phase == DockingState.Phase.PREPARING
                ? geometry(context, point, facing, selected, 1.501, .874, .001)
                : geometry(context, point, facing, selected, 1.5);
            if (!broad.valid) { selected.release("alignment"); return; }
            if (drive.root().isStalled() || drive.root().getContraption().stalled) { selected.release("paused"); return; }
            var path = path();
            double velocity = path == null ? 0 : ContraptionMotion.vector(path.derivative(drive.travel())).length() * Math.abs(drive.speed());
            selected.observe(point, drive.speed(), velocity, geometry(context, point, facing, selected, .5).valid, torqueAfterContact(selected));
            if (selected.owner.isPowered()) selected.cancel();
        }
        private double torqueAfterContact(DockingState state) {
            // A just-established contact must use this same accepted pose's Jacobian.
            boolean prior = state.occupied();
            if (prior) return torque(state);
            var path = path(); if (path == null) return state.torque;
            Vec3 v = ContraptionMotion.vector(path.derivative(drive.travel()));
            var normal = facingPath();
            double axial = Vec3.atCenterOf(state.owner.getBlockPos()).subtract(position()).dot(facing());
            if (normal != null) v = v.add(ContraptionMotion.vector(normal.derivative(drive.travel())).scale(axial / 2));
            return LINEAR_FORCE * v.length() * 1200 + (drive.rotation() && !drive.stabilized() ? TWIST_TORQUE * Math.abs(drive.angularRate()) / .3 : 0);
        }
    }
    private record Geometry(boolean valid, double axial) {}
    private static Geometry geometry(MovementContext context, Vec3 position, Vec3 facing, DockingState station, double width) {
        return geometry(context, position, facing, station, width, .875, 0);
    }
    private static Geometry geometry(MovementContext context, Vec3 position, Vec3 facing, DockingState station,
            double width, double alignment, double margin) {
        var state = station.owner.getBlockState();
        if (!adapter(context).compatible(context.state, state) || adapter(context).facing(state) == null) return new Geometry(false, 0);
        Direction target = adapter(context).facing(state).getOpposite();
        Vec3 normal = Vec3.atLowerCornerOf(target.getNormal());
        if (facing.dot(normal) < alignment - 1e-8) return new Geometry(false, 0);
        Vec3 delta = Vec3.atCenterOf(station.owner.getBlockPos()).subtract(position);
        double axial = delta.dot(normal);
        if (axial < 1.5 - margin - 1e-7 || axial > 3 + margin + 1e-7) return new Geometry(false, axial);
        Vec3 lateral = delta.subtract(normal.scale(axial));
        return new Geometry(Math.abs(lateral.x) <= width + 1e-7 && Math.abs(lateral.y) <= width + 1e-7 && Math.abs(lateral.z) <= width + 1e-7, axial);
    }
    public static boolean validGeometry(DockingState state) {
        var context = state.context;
        if (context == null || context.contraption.entity == null || adapter(context) == null) return false;
        var drive = ActorDrive.of(context);
        if (drive == null) return false;
        var binding = new Binding(context, drive, List.of(state));
        return geometry(context, binding.position(), binding.facing(), state, .5).valid;
    }
    public static List<Binding> prepare(List<KineticNetwork> networks) {
        var result = new ArrayList<Binding>(); var seen = Collections.newSetFromMap(new IdentityHashMap<MovementContext, Boolean>());
        for (var network : networks) for (var shaft : network.members.keySet()) for (var drive : ContraptionPhysics.drives(shaft))
            for (var actor : drive.entity().getContraption().getActors()) {
                var context = actor.getRight();
                if (context == null || !isPortable(context) || adapter(context).facing(context.state) == null || !seen.add(context)) continue;
                var binding = new Binding(context, drive, stations(context.world)); binding.observe(); result.add(binding);
            }
        return result;
    }
    public static List<Binding> prepareCart(Collection<MovementContext> contexts) {
        var result = new ArrayList<Binding>();
        for (var context : contexts) {
            var drive = ActorDrive.of(context); if (drive == null || adapter(context).facing(context.state) == null) continue;
            var binding = new Binding(context, drive, stations(context.world)); binding.observe(); result.add(binding);
        }
        return result;
    }
    public static double torque(MovementContext context) { var state = bindings.get(context); return state == null ? 0 : state.torque; }
    public static void observe(List<Binding> bindings, double travel) {
        for (var binding : bindings) {
            var state = PortableDocking.bindings.get(binding.context);
            if (state != null && state.occupied()) state.dissipated += state.torque * Math.abs(travel) / 1200;
            binding.observe();
        }
    }
    public static void tick(MovementContext context) {
        var drive = ActorDrive.of(context);
        if (drive == null || !isPortable(context)) return;
        new Binding(context, drive, stations(context.world)).observe();
    }
    public static void clientTick(MovementContext context) {
        var direction = adapter(context).facing(context.state); if (direction == null || context.contraption.entity == null) return;
        var entity = context.contraption.entity;
        Vec3 point = entity.toGlobalVector(Vec3.atCenterOf(context.localPos), 1), facing = entity.applyRotation(Vec3.atLowerCornerOf(direction.getNormal()), 1);
        float target = 0;
        for (var station : stations(context.world)) if (station.managed && geometry(context, point, facing, station, 1.5).valid) {
            float extension = station.extension(); target = extension * extension * station.distance() / 2; break;
        }
        var animation = PortableStorageInterfaceMovement.getAnimation(context);
        animation.chase(target, .25, net.createmod.catnip.animation.LerpedFloat.Chaser.LINEAR); animation.tickChaser();
    }
    /** Balance a known rail/controller motor impulse at rest using finite static contact capacity. */
    public static Vec3 balanceDrive(net.minecraft.world.entity.vehicle.AbstractMinecart cart, Vec3 before, Vec3 proposed, double inertia) {
        if (before.horizontalDistanceSqr() > 1e-16) return proposed;
        double capacity = 0;
        for (var state : bindings.values()) if (state.occupied() && state.context != null) {
            var drive = ActorDrive.of(state.context);
            if (drive != null && drive.root().getVehicle() == cart) capacity += state.torque;
        }
        double momentum = inertia * proposed.subtract(before).horizontalDistance() * MinecartMotion.RPM_PER_SPEED;
        if (momentum == 0 || capacity == 0) return proposed;
        double resisted = Math.min(momentum, capacity * RotationMath.TICK_MINUTES);
        return before.add(proposed.subtract(before).scale(1 - resisted / momentum));
    }
}
