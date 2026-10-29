package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.diliupengyun.differential_create.mixin.ClockworkBearingAccessor;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.nbt.CompoundTag;

/** Each clock hand has its own momentum, brake and passive input coupling.
 * Speeds use the input-equivalent coordinate; the fixed hand ratio never flips. */
public final class ClockworkMotion {
    public static final class Hand {
        public UUID entity;
        public double speed, angle, inertia, brakeHeat;
        public PositionServo.Command command = new PositionServo.Command(1, 0, PositionServo.TORQUE, 0);
        void write(CompoundTag tag) {
            if (entity != null) tag.putUUID("Entity", entity);
            tag.putDouble("Speed", speed); tag.putDouble("Angle", angle);
            tag.putDouble("Inertia", inertia); tag.putDouble("BrakeHeat", brakeHeat);
        }
        void read(CompoundTag tag) {
            entity = tag.hasUUID("Entity") ? tag.getUUID("Entity") : null;
            speed = finite(tag.getDouble("Speed")); angle = finite(tag.getDouble("Angle"));
            inertia = Math.max(0, finite(tag.getDouble("Inertia")));
            brakeHeat = Math.max(0, finite(tag.getDouble("BrakeHeat")));
        }
    }
    public record Output(ContraptionPhysics.Drive drive, Hand hand) {}
    public final Hand hour = new Hand(), minute = new Hand();
    private static double finite(double x) { return Double.isFinite(x) ? x : 0; }
    public static boolean manages(KineticBlockEntity be) { return be instanceof ClockworkBearingBlockEntity; }
    public static ClockworkMotion of(KineticBlockEntity be) { return ElasticKinetics.state(be).clockwork; }
    public static Hand hand(ContraptionPhysics.Drive drive) { return drive.ratio() == .5 ? of(drive.shaft()).hour : of(drive.shaft()).minute; }

    public static List<Output> outputs(KineticBlockEntity be) {
        if (!manages(be)) return List.of();
        var cached = KineticReadCache.of(be);
        if (cached != null && cached.clockOutputs != null) return cached.clockOutputs;
        var outputs = collectOutputs(be);
        if (cached != null) cached.clockOutputs = outputs;
        return outputs;
    }
    private static List<Output> collectOutputs(KineticBlockEntity be) {
        var drives = ContraptionPhysics.drives(be);
        boolean incomplete = ContraptionPhysics.incomplete(be);
        var outputs = new ArrayList<Output>(2);
        for (var drive : drives) if (!drive.stabilized()) {
            var hand = hand(drive);
            double inertia = drives.stream().filter(d -> d.root() == drive.root()).mapToDouble(ContraptionPhysics.Drive::inertia).sum();
            if (incomplete && drive.root().getUUID().equals(hand.entity)) inertia = Math.max(inertia, hand.inertia);
            if (!drive.root().getUUID().equals(hand.entity)) {
                var access = (ClockworkBearingAccessor) be;
                hand.entity = drive.root().getUUID(); hand.speed = 0; hand.inertia = 0;
                hand.angle = (drive.ratio() == .5 ? access.differentialcreate$hourAngle() : access.differentialcreate$minuteAngle()) / (360 * drive.ratio());
            } else if (inertia > hand.inertia && hand.inertia > 0) hand.speed *= hand.inertia / inertia;
            hand.inertia = inertia;
            if (inertia > 0) outputs.add(new Output(drive, hand));
        }
        return outputs;
    }

    public static double error(ContraptionPhysics.Drive drive) {
        var access = (ClockworkBearingAccessor) drive.shaft();
        int mode = access.differentialcreate$operationMode().getValue();
        boolean hour = drive.ratio() == .5;
        float target = hour ? mode == 1 ? access.differentialcreate$minuteTarget() : access.differentialcreate$hourTarget(mode == 2)
            : mode == 1 ? access.differentialcreate$hourTarget(false) : access.differentialcreate$minuteTarget();
        double angle = (hour ? access.differentialcreate$hourAngle() : access.differentialcreate$minuteAngle())
            + ContraptionMotion.geometry(drive).angle();
        return AngleHelper.getShortestAngleDiff((float) (angle % 360), target) / (360 * drive.ratio());
    }

    public static void prepare(Output output, ShaftPotential gravity, double resistance) {
        var drive = output.drive(); var hand = output.hand();
        boolean holding = false;
        for (var child : ContraptionPhysics.drives(drive.shaft())) if (child.root() == drive.root())
            for (var pair : child.entity().getContraption().getActors()) {
                var ctx = pair.getRight();
                if (ctx == null || !MovingDeployerWork.isHand(ctx)) continue;
                var tool = ContraptionActorWork.of(ctx).hand(); tool.prepare(); holding |= tool.holding();
            }
        hand.command = PositionServo.plan(error(drive), hand.speed, NetworkDynamics.preciseSpeed(drive.shaft()),
            hand.inertia, resistance, gravity.torque(0, 0), holding);
    }
    public static double kineticEnergy(KineticBlockEntity be) {
        return outputs(be).stream().mapToDouble(o -> .5 * o.hand.inertia * o.hand.speed * o.hand.speed).sum();
    }
    public static double inertia(KineticBlockEntity be) { return outputs(be).stream().mapToDouble(o -> o.hand.inertia).sum(); }
    public void write(CompoundTag tag) {
        var a = new CompoundTag(); var b = new CompoundTag(); hour.write(a); minute.write(b);
        tag.put("Hour", a); tag.put("Minute", b);
    }
    public void read(CompoundTag tag) { hour.read(tag.getCompound("Hour")); minute.read(tag.getCompound("Minute")); }
}
