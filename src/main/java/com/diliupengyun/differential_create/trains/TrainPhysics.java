package com.diliupengyun.differential_create.trains;

import java.util.*;
import com.diliupengyun.differential_create.kinetics.BoilerAdmission;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.simibubi.create.content.fluids.tank.BoilerData;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/** One server tick per Train, independent of rendered/loaded dimensional entities. */
public final class TrainPhysics {
    public interface Holder { State differentialcreate$physics(); }
    public static final class State {
        public int gear = 1, direction, travelDirection = 1, cabDirection = 1;
        public boolean brake, manualCommand, navigationCommand, gearInitialized, emergencyStop, neutralRev, stationAssist;
        public double navigationDistance = Double.POSITIVE_INFINITY;
        public double distance, mass = 1000, grade, engineRpm;
        public double controlsAdmission;
        public boolean controlsBraking;
        public String failure = "no_engine";
        public int ticks, lastShift;
        public Level level;
        public CompoundTag write() {
            var tag = new CompoundTag(); tag.putInt("Gear", gear); tag.putInt("Ticks", ticks); tag.putInt("LastShift", lastShift); tag.putInt("TravelDirection", travelDirection); tag.putBoolean("EmergencyStop", emergencyStop);
            // This marks the saved navigation as player-held, never as an input to replay.
            tag.putBoolean("StationAssist", stationAssist); return tag;
        }
        public void read(CompoundTag tag) { gear = TrainMechanics.clampGear(tag.getInt("Gear")); gearInitialized = true; ticks = Math.max(0, tag.getInt("Ticks")); lastShift = Math.min(ticks, tag.getInt("LastShift")); travelDirection = tag.getInt("TravelDirection") < 0 ? -1 : 1; emergencyStop = tag.getBoolean("EmergencyStop"); }
    }
    private TrainPhysics() {}
    public static State state(Train train) { return ((Holder) train).differentialcreate$physics(); }
    public static CarriageSteamState carriage(Carriage carriage) {
        var holder = (CarriageSteamState.Holder) carriage; var state = holder.differentialcreate$steam();
        if (state == null) {
            var entity = carriage.anyAvailableEntity();
            var layout = entity != null && entity.getContraption() instanceof CarriageContraption cc ? ((TrainAssembly.LayoutHolder) cc).differentialcreate$layout() : null;
            state = new CarriageSteamState(layout); holder.differentialcreate$steam(state);
        }
        return state;
    }
    public static boolean hasLever(Train train) {
        for (var c : train.carriages) { var l = carriage(c).layout; if (l != null && !l.levers.isEmpty()) return true; }
        return false;
    }
    public static void shift(Train train, int gear) {
        if (!hasLever(train)) return;
        var s = state(train); s.gear = TrainMechanics.clampGear(gear); s.gearInitialized = true; s.lastShift = s.ticks;
        for (var c : train.carriages) carriage(c).apply(c, s.gear);
    }
    public static void manual(Train train, int direction, boolean brake) {
        manual(train, direction, brake, false);
    }
    public static void manual(Train train, int direction, boolean brake, boolean neutralRev) {
        boolean neutral = hasLever(train) && state(train).gear == 0;
        var s = state(train); s.emergencyStop = false; s.direction = Integer.signum(direction); s.brake = brake; s.manualCommand = true;
        s.neutralRev = neutral && !brake && (neutralRev || direction != 0);
        if (neutral) s.direction = 0;
        train.manualTick = true; train.targetSpeed = s.direction;
    }
    public static void navigate(Train train) {
        var nav = train.navigation; var s = state(train);
        if (s.emergencyStop) { s.brake = true; return; }
        s.navigationCommand = true; s.direction = nav.destinationBehindTrain ? -1 : 1;
        s.navigationDistance = nav.waitingForSignal != null ? nav.distanceToSignal : nav.distanceToDestination;
        if (s.navigationDistance < -10) { nav.cancelNavigation(); s.direction = 0; s.brake = true; }
    }
    public static void endStationAssist(Train train) {
        var s = state(train);
        if (!s.stationAssist) return;
        s.stationAssist = false;
        if (train.navigation.destination != null) train.navigation.cancelNavigation();
    }
    public static void overrun(Train train) {
        state(train).emergencyStop = true;
        train.navigation.waitingForSignal = null;
        train.navigation.cancelNavigation();
    }
    public static void prepareDisassembly(Train train) {
        if (!train.canDisassemble()) return;
        for (var c : train.carriages) {
            var cs = carriage(c); cs.apply(c, state(train).gear);
            var entity = c.anyAvailableEntity();
            for (var stack : cs.fuelReturns) {
                var left = c.storage == null ? stack : ItemHandlerHelper.insertItemStacked(c.storage.getAllItems(), stack, false);
                if (!left.isEmpty()) net.minecraft.world.Containers.dropItemStack(entity.level(), entity.getX(), entity.getY(), entity.getZ(), left);
            }
            cs.fuelReturns.clear();
        }
    }
    public static double brakeAcceleration(Train train) {
        return Math.max(.01, TrainMechanics.BRAKE_ACCELERATION + TrainMechanics.ROLLING_ACCELERATION
            - TrainMechanics.GRAVITY * Math.abs(state(train).grade)) / 400;
    }
    /** A display/prediction estimate; no movement code clamps to this value. */
    public static double nominalSpeed(Train train) {
        double best = 1;
        for (var c : train.carriages) {
            var l = carriage(c).layout; if (l == null) continue;
            for (var drive : l.drives) best = Math.max(best, 512 * Math.abs(drive.ratio()
                * (hasLever(train) ? TrainMechanics.GEARS[Math.max(1, state(train).gear)] : drive.dial())) / TrainMechanics.RPM_PER_SPEED);
        }
        return best;
    }
    /** All carriages in this train share one silhouette; other trains never cast a shadow here. */
    public static double frontalArea(Train train) {
        var silhouette = new HashSet<Long>();
        double unknown = 0;
        for (var c : train.carriages) {
            var layout = carriage(c).layout;
            if (layout == null) { unknown += 2; continue; }
            TrainAerodynamics.include(silhouette, layout.frontalProjection, c.leadingBogey().isUpsideDown());
        }
        return Math.max(1, silhouette.size() + unknown);
    }
    public static void advance(Train train) {
        var s = state(train); Level level = s.level;
        if (level == null || level.isClientSide) return;
        var steam = TrainSteamConnections.resolve(train);
        s.ticks++; s.mass = 0; double gradeMass = 0;
        train.fuelTicks = 0;
        train.throttle = Double.isFinite(train.throttle) ? Math.clamp(train.throttle, 0, 1) : 0;
        boolean[] controlled = {s.manualCommand || train.manualTick};
        for (var c : train.carriages) c.forEachPresentEntity(e -> { if (e.getControllingPlayer().isPresent()) controlled[0] = true; });
        boolean human = controlled[0];
        int command = s.direction;
        if (!human && !s.navigationCommand) command = 0;
        if (s.emergencyStop) command = 0;
        s.failure = "no_engine";
        for (var c : train.carriages) {
            var cs = carriage(c); var l = cs.layout;
            double mass = TrainMechanics.BLOCK_MASS * (l == null ? 4 : Math.max(4, l.blocks)); s.mass += mass;
            var front = c.getLeadingPoint(); var rear = c.getTrailingPoint();
            if (train.graph != null && front.edge != null && rear.edge != null && !front.edge.isInterDimensional() && !rear.edge.isInterDimensional()) {
                var delta = front.getPosition(train.graph).subtract(rear.getPosition(train.graph));
                if (delta.length() > 1e-6) gradeMass += mass * delta.y / delta.length();
            }
            if (l != null && (!l.motors.isEmpty() || s.failure.equals("no_engine"))) s.failure = l.failure;
        }
        s.mass = Math.max(1000, s.mass); s.grade = gradeMass / s.mass;
        boolean reversing = command != 0 && train.speed * command < -1e-5;
        double brake = s.brake || reversing || train.derailed || s.emergencyStop ? 1 : 0;
        if (s.navigationCommand) {
            double forward = train.speed * command * 20;
            double downhill = -s.grade * command;
            double stopping = TrainMechanics.stoppingDistance(Math.max(0, forward), downhill);
            if (s.navigationDistance <= stopping + Math.abs(train.speed) * 2 + .1) brake = 1;
            // Finite-force approach after braking, to reach the station listener
            // without assigning a minimum speed or teleporting the train.
            if (forward < .1 && s.navigationDistance > 1e-6) brake = 0;
        }
        // The cab shows service/navigation braking, not the automatic stationary hold.
        s.controlsBraking = brake > 0;
        if (command == 0 && (train.getCurrentStation() != null || Math.abs(train.speed) < 1e-4)) brake = 1;
        if (!human && !train.runtime.paused && s.navigationCommand && hasLever(train)) {
            if (command < 0 && s.gear != TrainMechanics.REVERSE_GEAR) shift(train, TrainMechanics.REVERSE_GEAR);
            else if (command > 0 && (s.gear == 0 || s.gear == TrainMechanics.REVERSE_GEAR)) shift(train, 1);
            else if (command > 0 && s.ticks - s.lastShift > 40) {
                if (s.engineRpm > 430 && s.gear < TrainMechanics.FORWARD_GEARS) shift(train, s.gear + 1);
                else if (s.engineRpm < 170 && s.gear > 1) shift(train, s.gear - 1);
            }
        }
        // Neutral opens the steam valve without transmitting torque to the wheels.
        // Automatic stationary brakes may hold the carriage while the input spins.
        boolean lever = hasLever(train);
        boolean neutral = lever && s.gear == 0;
        boolean neutralRev = neutral && human && s.neutralRev && !s.brake && !train.derailed && !s.emergencyStop;
        int gearedDirection = s.cabDirection * (s.gear == TrainMechanics.REVERSE_GEAR ? -1 : 1);
        boolean wrongGear = s.stationAssist && !neutral && command != 0 && command != gearedDirection;
        double throttle = neutral ? neutralRev ? train.throttle : 0 : command == 0 || brake > 0 || reversing ? 0 : train.throttle;
        if (wrongGear) throttle = 0;
        if (s.navigationCommand) throttle *= Math.clamp(s.navigationDistance / 2, 0, 1);
        throttle = Double.isFinite(throttle) ? Math.clamp(throttle, 0, 1) : 0;
        s.controlsAdmission = throttle;
        var active = new ArrayList<TrainMechanics.Engine>();
        double rpmSum = 0; int rpmCount = 0;
        // A shared boiler pays and distributes steam once, across every connected carriage.
        for (var supply : steam.supplies) {
            var cs = supply.carriage; var b = supply.boiler;
            int consumers = supply.connections.size();
            int heat = 0; boolean passive = false;
            for (var heater : b.heaters()) {
                var burner = cs.burners.get(heater.pos());
                float score = heater.fixedHeat();
                if (burner != null) {
                    burner.tick();
                    if (consumers > 0 && throttle > 0) feedFuel(train, burner);
                    score = burner.heatScore();
                }
                if (score == 0) passive = true; else if (score > 0) heat += score;
            }
            var boiler = cs.boilers.computeIfAbsent(b.pos(), at -> new BoilerData());
            boiler.attachedEngines = consumers;
            boiler.activeHeat = heat;
            boiler.passiveHeat = passive && heat == 0;
            BoilerAdmission.set(boiler, throttle);
            double target = SourcePhysics.thermalSteamLevel(boiler, b.size());
            double demand = 0;
            for (var connection : supply.connections) {
                var drive = connection.drive();
                var engine = connection.carriage().engines.get(drive.motor().pos());
                double ratio = drive.ratio() * (lever ? TrainMechanics.GEARS[s.gear] : drive.dial());
                int direction = inputDirection(engine, drive.ratio(), ratio, command, neutralRev);
                demand += SourcePhysics.steamDemand(drive.motor().capacity(), target, consumers, direction, throttle, engine.rpm());
            }
            double available = cs.water.getOrDefault(b.pos(), 0d);
            available += drainWater(train, Math.max(0, (int) Math.ceil(demand - available)));
            double consumed = Math.min(available, demand);
            cs.water.put(b.pos(), available - consumed);
            BoilerAdmission.supply(boiler, demand, consumed);
            double supplied = SourcePhysics.steamLevel(boiler, b.size());
            if (target == 0 && consumers > 0) s.failure = "no_heat";
            else if (demand > 0 && supplied == 0 && consumers > 0) s.failure = "no_water";
        }
        for (var c : train.carriages) {
            var cs = carriage(c); var l = cs.layout; if (l == null) continue;
            if (c.storage != null) {
                for (var iterator = cs.fuelReturns.listIterator(); iterator.hasNext();) {
                    var left = ItemHandlerHelper.insertItemStacked(c.storage.getAllItems(), iterator.next(), false);
                    if (left.isEmpty()) iterator.remove(); else iterator.set(left);
                }
            }
            var connected = new HashSet<BlockPos>();
            for (var drive : l.drives) {
                var motor = drive.motor(); var e = cs.engines.get(motor.pos()); connected.add(motor.pos());
                e.shift(drive.ratio() * (lever ? TrainMechanics.GEARS[s.gear] : drive.dial()));
                int inputDirection = inputDirection(e, drive.ratio(), e.ratio, command, neutralRev);
                var source = steam.source(cs, motor.pos());
                var curve = SourcePhysics.admittedCurve(motor.capacity(), source.carriage.boilers.get(source.boiler.pos()),
                    source.boiler.size(), inputDirection);
                e.stallTorque = curve.stallTorque() * TrainMechanics.TORQUE_SCALE;
                e.power = curve.powerLimit() * TrainMechanics.TORQUE_SCALE * (2 * Math.PI / 60);
                e.governor = curve.governorSpeed(); e.governorRange = curve.governorRange();
                if (!Double.isFinite(e.power)) e.power = 0;
                rpmSum += Math.abs(e.rpm()); rpmCount++;
            }
            var grouped = new LinkedHashMap<BlockPos, List<TrainAssembly.Drive>>();
            for (var drive : l.drives) grouped.computeIfAbsent(drive.controller(), at -> new ArrayList<>()).add(drive);
            for (var group : grouped.entrySet()) {
                var first = group.getValue().getFirst(); var original = cs.engines.get(first.motor().pos());
                var rotor = cs.driveRotors.computeIfAbsent(group.getKey(), at -> {
                    var result = new TrainMechanics.Engine(); result.inertia = 0;
                    result.omega = original.omega * first.ratio(); result.turns = original.turns * first.ratio();
                    result.ratio = original.ratio / first.ratio(); result.shiftTime = original.shiftTime;
                    return result;
                });
                rotor.inertia = 0; rotor.cylinders.clear();
                for (var drive : group.getValue()) {
                    var e = cs.engines.get(drive.motor().pos()); rotor.inertia += e.inertia / (drive.ratio() * drive.ratio());
                    rotor.cylinders.add(new TrainMechanics.Cylinder(e.stallTorque, e.power, drive.ratio(), e.governor, e.governorRange));
                }
                rotor.shift(lever ? TrainMechanics.GEARS[s.gear] : first.dial()); active.add(rotor);
            }
            for (var motor : l.motors) if (!connected.contains(motor.pos())) {
                var e = cs.engines.get(motor.pos()); e.shift(0); e.stallTorque = e.power = 0; active.add(e);
            }
        }
        var motion = TrainMechanics.advance(train.speed * 20, s.mass, TrainAerodynamics.AIR_COEFFICIENT * frontalArea(train),
            s.grade, brake, active, TrainMechanics.TICK_SECONDS);
        train.speed = motion.speed() / 20;
        s.distance = com.diliupengyun.differential_create.kinetics.TrainActorMotion.feed(train, motion.distance(), active);
        rpmSum = 0; rpmCount = 0;
        for (var c : train.carriages) {
            var cs = carriage(c); if (cs.layout == null) continue;
            for (var drive : cs.layout.drives) {
                var rotor = cs.driveRotors.get(drive.controller()); var e = cs.engines.get(drive.motor().pos());
                e.omega = rotor.omega / drive.ratio(); e.turns = rotor.turns / drive.ratio(); e.shiftTime = rotor.shiftTime;
                e.clutchHeat = rotor.clutchHeat; rpmSum += Math.abs(e.rpm()); rpmCount++;
            }
        }
        s.engineRpm = rpmCount == 0 ? 0 : rpmSum / rpmCount;
        if (s.emergencyStop) {
            s.failure = "overrun";
            if (Math.abs(train.speed) < 1e-6) s.emergencyStop = false;
        }
        for (var c : train.carriages) { var cs = carriage(c); cs.wheelTurns += s.distance / (2 * Math.PI * TrainMechanics.WHEEL_RADIUS); cs.apply(c, s.gear); }
        train.speedBeforeStall = null; train.manualTick = false;
        s.direction = 0; s.brake = false; s.manualCommand = false; s.navigationCommand = false; s.neutralRev = false; s.navigationDistance = Double.POSITIVE_INFINITY;
        TrainStatePayload.send(train);
    }
    private static int inputDirection(TrainMechanics.Engine engine, double driveRatio, double ratio, int command, boolean neutralRev) {
        return neutralRev ? engine.omega == 0 ? (int) Math.signum(driveRatio) : (int) Math.signum(engine.omega)
            : command == 0 || ratio == 0 ? 0 : (int) Math.signum(command * ratio);
    }
    private static int drainWater(Train train, int amount) {
        if (amount <= 0) return 0; int collected = 0;
        for (var c : train.carriages) {
            if (c.storage == null) continue;
            var fluid = c.storage.getFluids().drain(new FluidStack(Fluids.WATER, amount - collected), FluidAction.EXECUTE);
            collected += fluid.getAmount(); if (collected >= amount) return collected;
        }
        return collected;
    }
    private static void feedFuel(Train train, MountedBurner burner) {
        if (burner.creative) return;
        for (int index = 0; index < train.carriages.size(); index++) {
            var c = train.carriages.get(train.speed < 0 ? train.carriages.size() - 1 - index : index);
            if (c.storage == null || c.storage.getFuelItems() == null) continue;
            var items = c.storage.getFuelItems();
            for (int slot = 0; slot < items.getSlots(); slot++) {
                var sample = items.extractItem(slot, 1, true); if (!burner.accepts(sample, false)) continue;
                var stack = items.extractItem(slot, 1, false); if (stack.isEmpty()) continue;
                if (!burner.accepts(stack, true)) { ItemHandlerHelper.insertItemStacked(items, stack, false); continue; }
                var remainder = stack.getCraftingRemainingItem();
                if (!remainder.isEmpty()) {
                    var left = ItemHandlerHelper.insertItemStacked(items, remainder, false);
                    // A full chest must not silently delete the container item.
                    if (!left.isEmpty()) carriage(c).fuelReturns.add(left);
                }
                return;
            }
        }
    }
}
