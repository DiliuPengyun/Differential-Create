package com.diliupengyun.differential_create.client.ponder;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.RotationMath;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.createmod.ponder.api.element.ParrotElement;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;

/** Production adapters run on the preview's own clock and inventories, including during seek. */
public final class PonderSimulation {
    private final PonderClockwork clockwork = new PonderClockwork();
    public PonderClockwork clockwork() { return clockwork; }
    private final PonderMounting mounting = new PonderMounting();
    public PonderMounting mounting() { return mounting; }
    private final Set<BlockPos> visible = new HashSet<>();
    private record Placement(org.joml.Matrix4f matrix, WorldSectionElement section) {}
    private final Map<BlockPos, Placement> placements = new java.util.LinkedHashMap<>();
    private final Map<BlockPos, BlockPos> displayed = new java.util.HashMap<>();
    private final Map<net.createmod.catnip.math.BlockFace, PonderFluidBoundary> fluidBoundaries = new java.util.HashMap<>();
    public void fluidBoundary(BlockPos pipe, net.minecraft.core.Direction side, net.neoforged.neoforge.fluids.capability.templates.FluidTank inventory) {
        var port = new net.createmod.catnip.math.BlockFace(pipe.immutable(), side);
        fluidBoundaries.put(port, new PonderFluidBoundary(port, inventory));
    }
    public PonderFluidBoundary fluidBoundary(BlockPos pipe, net.minecraft.core.Direction side) {
        return fluidBoundaries.get(new net.createmod.catnip.math.BlockFace(pipe, side));
    }
    public double height(BlockPos pos) {
        var placement = placements.get(pos);
        return placement == null ? pos.getY() : placement.matrix().transformPosition(new org.joml.Vector3f(pos.getX(), pos.getY(), pos.getZ())).y;
    }
    public net.minecraft.world.phys.Vec3 point(BlockPos pos) {
        var placement = placements.get(pos);
        return placement == null ? net.minecraft.world.phys.Vec3.atLowerCornerOf(pos)
            : new net.minecraft.world.phys.Vec3(placement.matrix().transformPosition(new org.joml.Vector3f(pos.getX(), pos.getY(), pos.getZ())));
    }
    public net.minecraft.world.phys.Vec3 particle(BlockPos source, net.minecraft.world.phys.Vec3 value, boolean direction) {
        var placement = placements.get(source);
        if (placement == null) return value;
        var vector = new org.joml.Vector3f((float) value.x, (float) value.y, (float) value.z);
        return new net.minecraft.world.phys.Vec3(direction ? placement.matrix().transformDirection(vector) : placement.matrix().transformPosition(vector));
    }
    public BlockPos relative(PonderLevel world, BlockPos origin, BlockPos offset) {
        var target = origin.offset(offset); var placement = placements.get(origin);
        if (placement == null) return target;
        var same = placements.get(target);
        var point = placement.matrix().transformPosition(new org.joml.Vector3f(target.getX(), target.getY(), target.getZ()));
        var grid = new BlockPos(Math.round(point.x), Math.round(point.y), Math.round(point.z));
        if (point.distanceSquared(new org.joml.Vector3f(grid.getX(), grid.getY(), grid.getZ())) < 1e-6) {
            var actual = displayed.get(grid);
            if (actual != null) return actual;
        }
        if (same != null && same.section() == placement.section()) return target;
        // Empty displayed space must not meet an unused copy elsewhere in the template.
        return world.getBlockState(target).isAir() ? target : grid.offset(1_000_000, 0, 1_000_000);
    }
    private long time;
    private record Punch(BlockPos target, net.minecraft.world.level.block.state.BlockState state, double travel) {}
    private final Map<BlockPos, Punch> punches = new java.util.HashMap<>();
    public void punch(BlockPos hand, BlockPos target, net.minecraft.world.level.block.state.BlockState state, double paid) {
        var world = previewWorld;
        if (world == null) return;
        double hardness = state.getDestroySpeed(world, target);
        if (!com.simibubi.create.content.kinetics.base.BlockBreakingKineticBlockEntity.isBreakable(state, (float) hardness)) return;
        var previous = punches.get(hand);
        double progress = paid + (previous != null && previous.target().equals(target) && previous.state() == state ? previous.travel() : 0);
        punches.put(hand.immutable(), new Punch(target.immutable(), state, progress));
        double required = Math.max(.1, hardness) * 1000;
        world.destroyBlockProgress(0, target, Math.min(9, (int) (10 * progress / required)));
        if (progress < required) return;
        com.simibubi.create.foundation.utility.BlockHelper.destroyBlock(world, target, 1);
        world.destroyBlockProgress(0, target, -1); punches.remove(hand);
    }
    private PonderLevel previewWorld;
    private final Set<BlockPos> initializedInventories = new HashSet<>();
    private record MaterialSupply(BlockPos position, net.minecraft.world.item.Item item) {}
    private final Set<MaterialSupply> materialSupplies = new HashSet<>();
    private final Set<BlockPos> motionInputs = new HashSet<>();
    public boolean motionInput(PonderLevel world, BlockPos pos) {
        if (motionInputs.contains(pos)) return true;
        if (world.getBlockEntity(pos) instanceof KineticBlockEntity be
            && Math.abs(be.getSpeed()) < 1e-6 && Math.abs(PonderKinetics.of(world).commandedSpeed(be)) < 1e-6) {
            motionInputs.add(pos.immutable()); return true;
        }
        return false;
    }
    public void initializeOnce(BlockPos pos, Runnable setup) { if (initializedInventories.add(pos.immutable())) setup.run(); }
    public void initializeMaterial(BlockPos pos, net.minecraft.world.item.Item item, Runnable setup) {
        if (materialSupplies.add(new MaterialSupply(pos.immutable(), item))) setup.run();
    }
    private final Map<WorldSectionElement, PonderTrain> trains = new java.util.IdentityHashMap<>();
    private record Attachment(KineticBlockEntity actuator, boolean rotation, int axis, double sign,
                              double angle, net.minecraft.world.phys.Vec3 start) {}
    private final Map<WorldSectionElement, Attachment> attachments = new java.util.IdentityHashMap<>();
    private final Map<WorldSectionElement, Attachment> settledGantries = new java.util.IdentityHashMap<>();
    private final Map<KineticBlockEntity, WorldSectionElement> stabilizedBearings = new java.util.IdentityHashMap<>();
    private final Map<com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity, PonderPulleyMotion> pulleys = new java.util.IdentityHashMap<>();
    public PonderPulleyMotion pulley(com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity be) {
        return pulleys.computeIfAbsent(be, PonderPulleyMotion::new);
    }
    public boolean pulleysArrived() { return pulleys.values().stream().allMatch(PonderPulleyMotion::arrived); }
    public double couplingRatio(KineticBlockEntity be) {
        return be instanceof com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity elevator
            ? pulley(elevator).couplingRatio() : 1;
    }
    public void followPulley(com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity follower,
        com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity leader) { pulley(follower).follow(pulley(leader)); }
    /** Move the diagram's frame without prescribing any mechanical travel. */
    public void translatePresentation(WorldSectionElement section, net.minecraft.world.phys.Vec3 delta, boolean immediate) {
        var binding = attachments.get(section);
        if (binding != null && !binding.rotation()) attachments.put(section, new Attachment(binding.actuator(), false,
            binding.axis(), binding.sign(), binding.angle(), binding.start().add(delta)));
        section.setAnimatedOffset(section.getAnimatedOffset().add(delta), immediate);
    }
    public boolean stabilizeBearing(PonderLevel world, BlockPos pos) {
        if (!(world.getBlockEntity(pos) instanceof KineticBlockEntity be)) return false;
        for (var entry : attachments.entrySet()) if (entry.getValue().rotation() && entry.getKey().isVisible()
            && ((PonderWorldSectionAccessor) entry.getKey()).differentialcreate$selection().test(pos)) {
            stabilizedBearings.put(be, entry.getKey());
            return true;
        }
        return false;
    }
    /** Both top renderers and the wooden section interpolate the same tick endpoints. */
    public Double bearingAngle(KineticBlockEntity be, float partialTick) {
        var parent = stabilizedBearings.get(be);
        if (parent != null && parent.isVisible()) {
            int axis = be.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING).getAxis().ordinal();
            return -sectionAngle(parent, axis, partialTick);
        }
        for (var entry : attachments.entrySet()) if (entry.getValue().actuator() == be
            && entry.getValue().rotation() && entry.getKey().isVisible())
            return sectionAngle(entry.getKey(), entry.getValue().axis(), partialTick);
        return null;
    }
    private static double sectionAngle(WorldSectionElement section, int axis, float partialTick) {
        var previous = ((PonderWorldSectionAccessor) section).differentialcreate$previousRotation();
        return previous.lerp(section.getAnimatedRotation(), partialTick).get(net.minecraft.core.Direction.Axis.values()[axis]);
    }
    public KineticBlockEntity adjacentActuator(PonderLevel world, WorldSectionElement section, int axis) {
        var selected = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
        for (var binding : attachments.entrySet()) {
            if (binding.getKey() == section || !binding.getKey().isVisible() || binding.getValue().rotation() || binding.getValue().axis() != axis) continue;
            var assembly = ((PonderWorldSectionAccessor) binding.getKey()).differentialcreate$selection();
            for (var cell : selected) if (!world.getBlockState(cell).isAir())
                for (var part : assembly) if (!world.getBlockState(part).isAir()
                    && point(cell).distanceToSqr(point(part)) <= 1.000001) return binding.getValue().actuator();
        }
        return null;
    }
    private final Map<WorldSectionElement, PonderLinearMotion> linearMotions = new java.util.IdentityHashMap<>();
    private record ParentSection(WorldSectionElement parent, net.minecraft.world.phys.Vec3 baseline, boolean follows) {}
    private final Map<WorldSectionElement, ParentSection> parents = new java.util.IdentityHashMap<>();
    private record Passenger(WorldSectionElement support, net.minecraft.world.phys.Vec3 supportStart,
                             net.minecraft.world.phys.Vec3 start) {}
    private final Map<ParrotElement, Passenger> passengers = new java.util.IdentityHashMap<>();
    /** A standing passenger shares the platform's integrated translation and tick interpolation. */
    public void passenger(ParrotElement passenger, WorldSectionElement support) {
        passengers.put(passenger, new Passenger(support, support.getAnimatedOffset(), passenger.getPositionOffset()));
    }
    /** A carriage mounted on a moving shaft inherits that shaft's actual transform. */
    public boolean followCarriage(PonderLevel world, WorldSectionElement section, int requestedAxis) {
        for (var cursor : ((PonderWorldSectionAccessor) section).differentialcreate$selection()) {
            if (!(world.getBlockEntity(cursor) instanceof com.simibubi.create.content.contraptions.gantry.GantryCarriageBlockEntity carriage)) continue;
            var facing = carriage.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
            var support = cursor.relative(facing.getOpposite());
            if (!(world.getBlockEntity(support) instanceof com.simibubi.create.content.kinetics.gantry.GantryShaftBlockEntity shaft)) continue;
            for (var entry : attachments.entrySet()) {
                if (entry.getKey() == section || !entry.getKey().isVisible()
                    || !((PonderWorldSectionAccessor) entry.getKey()).differentialcreate$selection().test(support)) continue;
                boolean follows = shaft.getBlockState().getValue(com.simibubi.create.content.kinetics.gantry.GantryShaftBlock.FACING).getAxis().ordinal() != requestedAxis;
                parents.put(section, new ParentSection(entry.getKey(), entry.getKey().getAnimatedOffset(), follows));
                if (follows) section.setAnimatedOffset(entry.getKey().getAnimatedOffset(), true);
                return follows;
            }
        }
        return false;
    }
    public record TravelBounds(double lower, double upper) {}
    public boolean linearBlocked(KineticBlockEntity be) {
        return attachments.entrySet().stream().filter(entry -> entry.getValue().actuator() == be && entry.getKey().isVisible())
            .anyMatch(entry -> linearMotions.containsKey(entry.getKey()) && linearMotions.get(entry.getKey()).blocked());
    }
    public TravelBounds linearBounds(KineticBlockEntity be) {
        double lower = Double.NEGATIVE_INFINITY, upper = Double.POSITIVE_INFINITY;
        for (var entry : attachments.entrySet()) if (entry.getValue().actuator() == be && entry.getKey().isVisible()) {
            var motion = linearMotions.get(entry.getKey());
            if (motion == null) continue;
            var bounds = motion.bounds((PonderLevel) be.getLevel());
            lower = Math.max(lower, bounds.lower()); upper = Math.min(upper, bounds.upper());
        }
        return new TravelBounds(lower, upper);
    }
    private record ValveHandle(com.simibubi.create.content.fluids.pipes.valve.FluidValveBlockEntity valve,
                               net.minecraft.world.phys.Vec3 start) {}
    private final Map<WorldSectionElement, ValveHandle> valveHandles = new java.util.IdentityHashMap<>();
    public void valveHandle(WorldSectionElement section, com.simibubi.create.content.fluids.pipes.valve.FluidValveBlockEntity valve) {
        valveHandles.putIfAbsent(section, new ValveHandle(valve, section.getAnimatedRotation().subtract(0,
            90 * ((PonderValveStroke) valve).differentialcreate$opening(), 0)));
    }
    private final Map<WorldSectionElement, PonderActorWork> actorSections = new java.util.IdentityHashMap<>();
    java.util.List<WorldSectionElement> sections(KineticBlockEntity actuator) {
        return attachments.entrySet().stream().filter(e -> e.getValue().actuator() == actuator && e.getKey().isVisible()).map(Map.Entry::getKey).toList();
    }
    private final Map<com.simibubi.create.content.contraptions.behaviour.MovementContext, com.diliupengyun.differential_create.kinetics.ActorDrive> actorDrives = new java.util.IdentityHashMap<>();
    void actorDrive(com.simibubi.create.content.contraptions.behaviour.MovementContext ctx, com.diliupengyun.differential_create.kinetics.ActorDrive drive) { actorDrives.put(ctx, drive); }
    void removeActor(com.simibubi.create.content.contraptions.behaviour.MovementContext ctx) { actorDrives.remove(ctx); }
    public com.diliupengyun.differential_create.kinetics.ActorDrive actorDrive(com.simibubi.create.content.contraptions.behaviour.MovementContext ctx) { return actorDrives.get(ctx); }
    private final java.util.List<PonderDocking> docks = new java.util.ArrayList<>();
    private final Set<BlockPos> dockedEndpoints = new HashSet<>();
    private final Map<net.createmod.ponder.api.element.MinecartElement, PonderCart> carts = new java.util.IdentityHashMap<>();
    private record Coupling(PonderCart first, PonderCart second, net.minecraft.world.phys.Vec3 axis,
                            double rest) {}
    private final java.util.List<Coupling> couplings = new java.util.ArrayList<>();
    private record CartLoad(net.createmod.ponder.api.element.MinecartElement cart, net.minecraft.world.phys.Vec3 cartStart,
                            net.minecraft.world.phys.Vec3 start) {}
    private final Map<WorldSectionElement, CartLoad> cartLoads = new java.util.IdentityHashMap<>();
    private final Map<com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity, PonderDeployerProcessing> hands = new java.util.IdentityHashMap<>();
    private final PonderPressProcessing pressing = new PonderPressProcessing();
    public boolean attachCart(WorldSectionElement section, net.minecraft.world.phys.Vec3 request, int duration) {
        for (var entry : carts.entrySet()) {
            var cart = entry.getValue();
            if (cart.remaining < cart.commandedTicks - 1 || cart.requested.distanceToSqr(request.scale(1d / duration)) > 1e-9) continue;
            cartLoads.put(section, new CartLoad(entry.getKey(), entry.getKey().getPositionOffset(), section.getAnimatedOffset()));
            return true;
        }
        return false;
    }
    public void cartLoad(WorldSectionElement section, net.createmod.ponder.api.element.MinecartElement first,
        net.createmod.ponder.api.element.MinecartElement second, double inertia, double drag) {
        for (var element : java.util.List.of(first, second)) {
            var cart = carts.computeIfAbsent(element, PonderCart::new);
            cart.inertia = com.diliupengyun.differential_create.kinetics.MinecartMotion.BASE_INERTIA + inertia / 2;
            cart.drag = drag / 2;
        }
        if (couplings.stream().noneMatch(pair -> pair.first().element == first && pair.second().element == second)) {
            var a = ((com.diliupengyun.differential_create.mixin.PonderMinecartAccessor) first).differentialcreate$origin().add(first.getPositionOffset());
            var b = ((com.diliupengyun.differential_create.mixin.PonderMinecartAccessor) second).differentialcreate$origin().add(second.getPositionOffset());
            var delta = b.subtract(a);
            couplings.add(new Coupling(carts.get(first), carts.get(second), delta.normalize(), delta.length()));
        }
        cartLoads.put(section, new CartLoad(first, first.getPositionOffset(), section.getAnimatedOffset()));
    }
    public void cart(net.createmod.ponder.api.element.MinecartElement element, net.minecraft.world.phys.Vec3 delta, int duration) {
        carts.computeIfAbsent(element, PonderCart::new).command(delta, duration);
    }
    public void dock(PonderLevel world, WorldSectionElement moving, BlockPos station, BlockPos endpoint, BlockPos actuator) {
        if (!dockedEndpoints.add(endpoint.immutable())) return;
        docks.add(new PonderDocking(world, moving, station, endpoint, actuator));
    }

    public void release(WorldSectionElement section) {
        clockwork.release(section);
        mounting.release(section);
        attachments.remove(section); linearMotions.remove(section); parents.remove(section); trains.remove(section); cartLoads.remove(section); valveHandles.remove(section);
        settledGantries.remove(section);
        stabilizedBearings.values().removeIf(parent -> parent == section);
        var actors = actorSections.remove(section);
        if (!actorSections.containsValue(actors)) actorDrives.values().removeIf(drive -> drive == actors);
    }
    public boolean mountedTool(BlockPos pos) { return actorDrives.entrySet().stream().anyMatch(entry -> entry.getKey().localPos.equals(pos)
        && entry.getValue() instanceof PonderActorWork work && work.active(entry.getKey())); }
    public void attach(WorldSectionElement section, KineticBlockEntity be, boolean rotation, int axis, double sign) {
        if (attachments.containsKey(section)) {
            var old = attachments.get(section);
            if (old.actuator() == be && old.rotation() == rotation && old.axis() == axis) return;
            release(section);
        }
        Double angle = PonderKinetics.of((PonderLevel) be.getLevel()).outputAngle(be, 1);
        var binding = new Attachment(be, rotation, axis, sign, angle == null ? 0 : angle,
            rotation ? section.getAnimatedRotation() : section.getAnimatedOffset());
        if (rotation && ((PonderWorldSectionAccessor) section).differentialcreate$stabilization() != null) {
            for (var entry : attachments.entrySet()) if (entry.getValue().actuator() == be && entry.getKey().isVisible()) {
                binding = entry.getValue();
                section.setAnimatedRotation(entry.getKey().getAnimatedRotation(), true);
                break;
            }
        }
        attachments.put(section, binding);
        var world = (PonderLevel) be.getLevel();
        if (!rotation) linearMotions.put(section, new PonderLinearMotion(world, section, be, axis, angle == null ? 0 : angle, sign));
        var existing = actorSections.values().stream().filter(work -> work.actuator() == be).findFirst();
        actorSections.put(section, existing.orElseGet(() -> new PonderActorWork(world, section, be, rotation, axis)));
        var origin = point(be.getBlockPos());
        // Each section contributes once; a hidden example cannot add mass to its replacement.
        var ownCells = new java.util.HashMap<BlockPos, com.diliupengyun.differential_create.kinetics.ContraptionGeometry.Cell>();
        for (var cursor : ((PonderWorldSectionAccessor) section).differentialcreate$selection()) if (!world.getBlockState(cursor).isAir()) {
            var position = point(cursor).subtract(origin);
            ownCells.put(cursor.immutable(), new com.diliupengyun.differential_create.kinetics.ContraptionGeometry.Cell((int) Math.round(position.x), (int) Math.round(position.y), (int) Math.round(position.z)));
        }
        mounting.bindSection(section, be, rotation, axis, ownCells, 1);
        if (be instanceof com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity pulley) pulley(pulley).sampleAngle();
    }
    double motionRatio(WorldSectionElement section, double fallback) {
        var binding = attachments.get(section);
        if (binding == null) return clockwork.motionRatio(section, fallback);
        if (binding.rotation()) return binding.sign();
        var motion = linearMotions.get(section);
        return motion == null ? fallback : motion.direction();
    }
    public void finish(PonderLevel world) {
        var machines = java.util.stream.StreamSupport.stream(world.getBlockEntities().spliterator(), false).toList();
        for (var be : machines) if (visible.contains(be.getBlockPos())
            && be instanceof com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity fan) { fan.getAirCurrent().rebuild(); fan.getAirCurrent().findEntities(); }
        // Render sections cache their ticker lists; edits and invisible controller
        // blocks must not decide whether a machine receives its physical tick.
        for (var be : machines)
            if (visible.contains(be.getBlockPos()) && !be.isRemoved() && be instanceof SmartBlockEntity smart
                && !(be instanceof com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity)) smart.tick();
        for (var be : machines) if (visible.contains(be.getBlockPos())
            && be instanceof com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity mill && mill.timer > 0 && mill.getSpeed() != 0) mill.spawnParticles();
        for (var be : machines) if (visible.contains(be.getBlockPos())
            && be instanceof net.minecraft.world.level.block.entity.HopperBlockEntity hopper)
            net.minecraft.world.level.block.entity.HopperBlockEntity.pushItemsTick(world, be.getBlockPos(), be.getBlockState(), hopper);
        if (world.scene == null || (!world.scene.getId().getPath().equals("mechanical_press") && !world.scene.getId().getPath().equals("continuous_work")))
            for (var be : machines) if (visible.contains(be.getBlockPos()) && be instanceof com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity press) pressing.tick(press);
        for (var be : machines) if (visible.contains(be.getBlockPos())
            && be instanceof com.simibubi.create.content.fluids.spout.SpoutBlockEntity spout) {
            var support = relative(world, be.getBlockPos(), new BlockPos(0, -2, 0));
            var handler = com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(world, support,
                com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TYPE);
            if (handler != null) handler.handleCenteredProcessingOnAllItems(.03f, item -> {
                var result = ((com.diliupengyun.differential_create.mixin.PonderSpoutAccessor) spout).differentialcreate$fill(item, handler);
                item.locked = result == com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour.ProcessingResult.HOLD;
                return com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult.doNothing();
            });
        }
        for (var be : machines) if (visible.contains(be.getBlockPos())
            && be instanceof com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity hand)
            hands.computeIfAbsent(hand, key -> new PonderDeployerProcessing()).tick(hand);
        pulleys.entrySet().removeIf(entry -> entry.getKey().isRemoved() || world.getBlockEntity(entry.getKey().getBlockPos()) != entry.getKey());
        pulleys.values().forEach(PonderPulleyMotion::tick);
        pulleys.values().forEach(PonderPulleyMotion::tickFollower);
        var disassembling = new java.util.ArrayList<WorldSectionElement>();
        attachments.forEach((section, binding) -> {
            if (!section.isVisible() || binding.actuator().isRemoved()) return;
            Double angle = PonderKinetics.of(world).outputAngle(binding.actuator(), 1);
            double travel = ((angle == null ? binding.angle() : angle) - binding.angle()) * binding.sign();
            if (!binding.rotation()) {
                var motion = linearMotions.get(section);
                travel = motion.advance(world, angle == null ? binding.angle() : angle);
                if (motion.disassemble()) disassembling.add(section);
            }
            var delta = new net.minecraft.world.phys.Vec3(binding.axis() == 0 ? travel : 0,
                binding.axis() == 1 ? travel : 0, binding.axis() == 2 ? travel : 0);
            if (binding.rotation()) {
                section.setAnimatedRotation(binding.start().add(delta), false);
                if (binding.actuator() instanceof com.simibubi.create.content.contraptions.bearing.IBearingBlockEntity bearing)
                    bearing.setAngle((float) (binding.start().get(net.minecraft.core.Direction.Axis.values()[binding.axis()]) + travel));
            } else section.setAnimatedOffset(binding.start().add(delta), false);
        });
        for (var section : disassembling) {
            var binding = attachments.get(section);
            var offset = section.getAnimatedOffset();
            section.setAnimatedOffset(new net.minecraft.world.phys.Vec3(Math.round(offset.x), Math.round(offset.y), Math.round(offset.z)), true);
            release(section);
            settledGantries.put(section, binding);
        }
        parents.forEach((section, parent) -> {
            if (!section.isVisible()) return;
            section.setAnimatedOffset(parent.follows() ? parent.parent().getAnimatedOffset()
                : section.getAnimatedOffset().add(parent.parent().getAnimatedOffset().subtract(parent.baseline())),
                disassembling.contains(parent.parent()));
        });
        stabilizedBearings.forEach((be, parent) -> {
            if (be instanceof com.simibubi.create.content.contraptions.bearing.IBearingBlockEntity bearing && parent.isVisible())
                bearing.setAngle(bearingAngle(be, 1).floatValue());
        });
        clockwork.tick();
        new java.util.HashSet<>(actorSections.values()).forEach(PonderActorWork::tick);
        valveHandles.forEach((section, handle) -> {
            if (section.isVisible() && !handle.valve().isRemoved()) section.setAnimatedRotation(handle.start().add(0,
                90 * ((PonderValveStroke) handle.valve()).differentialcreate$opening(), 0), false);
        });
        docks.forEach(PonderDocking::tick);
        cartLoads.forEach((section, load) -> {
            if (section.isVisible()) section.setAnimatedOffset(load.start().add(load.cart().getPositionOffset().subtract(load.cartStart())), false);
        });
        passengers.forEach((passenger, binding) -> {
            if (passenger.isVisible() && binding.support().isVisible())
                passenger.setPositionOffset(binding.start().add(binding.support().getAnimatedOffset().subtract(binding.supportStart())), false);
        });
    }

    public static PonderSimulation of(PonderLevel world) { return ((IPonderKinetics) world).differentialcreate$simulation(); }
    public void train(PonderLevel world, WorldSectionElement section, double throttle, boolean brake, int duration) {
        trains.computeIfAbsent(section, key -> new PonderTrain(world, key)).command(throttle, brake, duration);
    }
    public void driveTrain(PonderLevel world, WorldSectionElement section, double direction, int duration) {
        trains.computeIfAbsent(section, key -> new PonderTrain(world, key)).driver(world, direction, duration);
    }
    public boolean trainArrived(WorldSectionElement section) { return !trains.containsKey(section) || trains.get(section).arrived(); }
    public boolean trainBogey(BlockPos pos) { return trains.values().stream().anyMatch(train -> train.live() && train.contains(pos)); }
    /** Assembled blocks remain as render templates, not terrain for moving tools. */
    public boolean mountedBlock(BlockPos pos) {
        if (trains.values().stream().anyMatch(train -> train.live() && train.owns(pos))) return true;
        return attachments.keySet().stream().anyMatch(section -> section.isVisible() && ((PonderWorldSectionAccessor) section).differentialcreate$selection().test(pos));
    }
    public com.diliupengyun.differential_create.trains.TrainMechanics.Engine trainBoundary(com.simibubi.create.content.trains.entity.Train train) {
        return trains.values().stream().filter(preview -> preview.train == train).findFirst().map(PonderTrain::boundary).orElse(null);
    }

    public long time() { return time; }
    public Map<String, java.util.List<Double>> readings() {
        var result = new java.util.LinkedHashMap<String, java.util.List<Double>>();
        int i = 0; for (var train : trains.values()) result.put("train " + i++, train.reading());
        i = 0; for (var dock : docks) result.put("dock " + i++, dock.reading());
        return result;
    }
    public boolean physical(PonderLevel world, BlockPos pos) {
        return visible.contains(pos) || world.getBlockState(pos).isAir();
    }
    public void reset(PonderLevel world) {
        clockwork.reset(); mounting.reset(); linearMotions.clear(); parents.clear(); passengers.clear();
        settledGantries.clear(); stabilizedBearings.clear(); pulleys.clear();
        PonderLoot.reset(world);
        visible.clear(); placements.clear(); displayed.clear(); fluidBoundaries.clear(); trains.clear(); attachments.clear(); valveHandles.clear(); actorSections.clear(); actorDrives.clear(); docks.clear(); dockedEndpoints.clear(); carts.clear(); couplings.clear(); cartLoads.clear(); hands.clear(); time = 0;
        initializedInventories.clear(); materialSupplies.clear(); motionInputs.clear(); punches.clear(); FluidKinetics.resetPreview(world);
    }
    public Map<KineticBlockEntity, Double> advance(PonderLevel world) {
        previewWorld = world;
        time++;
        for (var entry : java.util.List.copyOf(settledGantries.entrySet())) {
            var be = entry.getValue().actuator();
            if (!entry.getKey().isVisible() || be.isRemoved() || world.getBlockEntity(be.getBlockPos()) != be) {
                settledGantries.remove(entry.getKey());
            } else if (!be.getBlockState().getValue(com.simibubi.create.content.kinetics.gantry.GantryShaftBlock.POWERED)) {
                settledGantries.remove(entry.getKey());
                attach(entry.getKey(), be, false, entry.getValue().axis(), entry.getValue().sign());
            }
        }
        for (var entry : java.util.List.copyOf(attachments.entrySet()))
            if (entry.getValue().actuator().isRemoved() || world.getBlockEntity(entry.getValue().actuator().getBlockPos()) != entry.getValue().actuator()) release(entry.getKey());
        trains.values().forEach(train -> train.tick(world));
        carts.values().stream().filter(cart -> cart.element.isVisible()).forEach(cart -> cart.tick(world));
        for (var pair : couplings) {
            var a = pair.first(); var b = pair.second(); var axis = pair.axis();
            if (!a.element.isVisible() || !b.element.isVisible()) continue;
            var aOrigin = ((com.diliupengyun.differential_create.mixin.PonderMinecartAccessor) a.element).differentialcreate$origin();
            var bOrigin = ((com.diliupengyun.differential_create.mixin.PonderMinecartAccessor) b.element).differentialcreate$origin();
            double extension = bOrigin.add(b.element.getPositionOffset()).subtract(aOrigin.add(a.element.getPositionOffset())).dot(axis) - pair.rest();
            double va = a.velocity.dot(axis), vb = b.velocity.dot(axis);
            var result = com.diliupengyun.differential_create.kinetics.MinecartMotion.couple(va, vb, a.inertia, b.inertia, 1, 1, extension, true, true);
            a.velocity = a.velocity.add(axis.scale(result.first() - va)); b.velocity = b.velocity.add(axis.scale(result.second() - vb));
        }
        carts.values().stream().filter(cart -> cart.element.isVisible()).forEach(PonderCart::integrate);
        visible.clear(); placements.clear(); displayed.clear();
        if (world.scene == null) world.getBlockMap().keySet().forEach(pos -> visible.add(pos.immutable()));
        if (world.scene != null) world.scene.forEachVisible(WorldSectionElement.class, section -> {
            var selection = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
            if (selection != null) {
                var pose = new com.mojang.blaze3d.vertex.PoseStack();
                ((net.createmod.ponder.foundation.element.WorldSectionElementImpl) section).transformMS(pose, 1);
                var placement = new Placement(new org.joml.Matrix4f(pose.last().pose()), section);
                selection.forEach(cursor -> {
                    var pos = cursor.immutable(); visible.add(pos); placements.put(pos, placement);
                    if (world.getBlockState(pos).isAir()) return;
                    var point = placement.matrix().transformPosition(new org.joml.Vector3f(pos.getX(), pos.getY(), pos.getZ()));
                    var grid = new BlockPos(Math.round(point.x), Math.round(point.y), Math.round(point.z));
                    if (point.distanceSquared(new org.joml.Vector3f(grid.getX(), grid.getY(), grid.getZ())) < 1e-6) {
                        var old = displayed.get(grid); var oldPlacement = old == null ? null : placements.get(old);
                        boolean moved = section.getAnimatedOffset().lengthSqr() > 1e-10 || section.getAnimatedRotation().lengthSqr() > 1e-10;
                        boolean oldMoved = oldPlacement != null && (oldPlacement.section().getAnimatedOffset().lengthSqr() > 1e-10 || oldPlacement.section().getAnimatedRotation().lengthSqr() > 1e-10);
                        if (old == null || moved || !oldMoved) displayed.put(grid, pos);
                    }
                });
            }
        });
        // Offstage inventory shares its pipe's display transform and elevation,
        // but adds no visible block or independently ticking machine.
        for (var port : fluidBoundaries.keySet()) {
            var placement = placements.get(port.getPos());
            if (placement != null && world.getBlockState(port.getConnectedPos()).isAir())
                placements.put(port.getConnectedPos(), placement);
        }
        mounting.sync(world);
        for (var be : world.getBlockEntities()) if (be instanceof com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity controller) {
            var pair = com.diliupengyun.differential_create.kinetics.CrushingKinetics.pair(controller);
            if (pair != null && visible.contains(pair.a().getBlockPos()) && visible.contains(pair.b().getBlockPos()))
                visible.add(be.getBlockPos().immutable());
        }
        // Native portable-interface lessons use the same attached sections as their bearing.
        for (var iterator = docks.iterator(); iterator.hasNext();) {
            var dock = iterator.next();
            if (!dock.live()) { dock.retire(); dockedEndpoints.remove(dock.endpoint()); iterator.remove(); }
        }
        for (var moving : attachments.entrySet()) {
            var section = moving.getKey();
            if (!section.isVisible()) continue;
            var selection = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
            for (var cursor : selection) {
                var endpoint = cursor.immutable();
                var item = world.getBlockEntity(endpoint);
                if (!(item instanceof com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity)
                    || dockedEndpoints.contains(endpoint)) continue;
                for (var candidate : world.getBlockEntities()) {
                    if (!visible.contains(candidate.getBlockPos()) || candidate.getClass() != item.getClass()
                        || selection.test(candidate.getBlockPos())) continue;
                    dock(world, section, candidate.getBlockPos(), endpoint, moving.getValue().actuator().getBlockPos());
                    break;
                }
            }
        }
        // A preassembled steam installation may reveal its cylinders in stages.
        // Restore real engine/shaft links, including cylinders not drawn yet, from geometry.
        for (var be : world.getBlockEntities()) if (be instanceof com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity engine) {
            var tank = engine.getTank();
            if (tank == null || !visible.contains(tank.getBlockPos()) || !engine.isValid()) continue;
            var output = com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock.getShaftPos(engine.getBlockState(), engine.getBlockPos());
            if (world.getBlockEntity(output) instanceof com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity shaft) {
                if (shaft.initialTicks > 0) shaft.initialTicks--;
                engine.tick();
            }
        }
        for (var be : world.getBlockEntities()) if (visible.contains(be.getBlockPos()) && be instanceof SmartBlockEntity smart) {
            be.setBlockState(world.getBlockState(be.getBlockPos()));
            var pipe = smart.getBehaviour(FluidTransportBehaviour.TYPE);
            if (pipe != null) {
                pipe.getConnection(net.minecraft.core.Direction.DOWN);
                FluidKinetics.register(pipe);
            }
            if (be instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank && tank.isController()
                && tank.boiler.isActive() && trains.values().stream().noneMatch(PonderTrain::live)) {
                boolean passive = false; int heat = 0;
                for (int x = 0; x < tank.getWidth(); x++) for (int z = 0; z < tank.getWidth(); z++) {
                    var heater = relative(world, tank.getBlockPos(), new BlockPos(x, -1, z));
                    float value = com.simibubi.create.api.boiler.BoilerHeater.findHeat(world, heater, world.getBlockState(heater));
                    if (value == 0) passive = true; else if (value > 0) heat += value;
                }
                tank.boiler.activeHeat = heat; tank.boiler.passiveHeat = passive && heat == 0;
                tank.boiler.needsHeatLevelUpdate = false;
                ((com.diliupengyun.differential_create.mixin.PonderTankAccessor) tank).differentialcreate$refreshCapability();
                com.diliupengyun.differential_create.kinetics.SteamBoilerWater.begin(tank);
            }
        }
        FluidKinetics.beginTick(world);
        if (!FluidKinetics.hasPipes(world)) return Map.of();
        FluidKinetics.invalidate(world);
        var frame = FluidKinetics.capture(world);
        var reactions = FluidKinetics.advance(frame, RotationMath.TICK_MINUTES);
        FluidKinetics.publish(frame);
        for (var be : world.getBlockEntities())
            if (visible.contains(be.getBlockPos()) && be instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank
                && tank.isController() && tank.boiler.isActive()) {
                tank.boiler.read(tank.boiler.write(), tank.getTotalTankSize());
                tank.boiler.gauge.tickChaser();
            }
        return reactions;
    }
}
