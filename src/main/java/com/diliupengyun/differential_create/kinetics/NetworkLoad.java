package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

/** Server-side readout from current member speeds, real source curves and working states. */
public final class NetworkLoad {
    private NetworkLoad() {}

    public static LoadFactor.Reading of(KineticBlockEntity be) {
        if (be.getLevel() == null || be.getLevel().isClientSide) return LoadFactor.Reading.UNKNOWN;
        return be.hasNetwork() ? calculate(be.getOrCreateNetwork()) : LoadFactor.calculate(List.of(member(be, 1)), 0);
    }

    public static LoadFactor.Reading calculate(KineticNetwork network) {
        var dynamics = (INetworkDynamics) network;
        var ratios = dynamics.differentialcreate$getRatios();
        var members = new ArrayList<LoadFactor.Member>();
        for (var entry : ratios.entrySet())
            if (!entry.getKey().isRemoved()) members.add(member(entry.getKey(), entry.getValue()));
        double loss = 0;
        for (var edge : dynamics.differentialcreate$data().connections) {
            if (edge.a.isRemoved() || edge.b.isRemoved()) continue;
            double relative = edge.ratio * SequencerKinetics.portSpeed(edge.a, edge.b) - SequencerKinetics.portSpeed(edge.b, edge.a);
            loss += LoadFactor.contactLoss(edge.stiffness(), edge.damping(), edge.friction(), edge.twist, relative);
        }
        for (var be : network.members.keySet()) if (be instanceof ISequencerDynamics servo) {
            var m = servo.differentialcreate$motion(); var c = m.command;
            loss += LoadFactor.contactLoss(0, SequencerMotion.COUPLING_DAMPING, c.coupling(), 0,
                c.gear() * NetworkDynamics.preciseSpeed(be) - m.speed) + c.brake() * Math.abs(m.speed);
        }
        for (var be : network.members.keySet()) if (ActorFeed.enabled(be)) {
            var feed = ActorFeed.of(be); double ratio = ratios.getOrDefault(be, 1f);
            members.add(new LoadFactor.Member(ratio, feed.speed, 0, feed.brake + ContraptionPhysics.feedLoad(be)
                + ContraptionPhysics.airDrag(be) * feed.speed * feed.speed, false));
            loss += LoadFactor.contactLoss(0, ActorFeed.DAMPING, feed.coupling, 0, NetworkDynamics.preciseSpeed(be) - feed.speed);
        }
        return LoadFactor.calculate(members, loss);
    }

    private static LoadFactor.Member member(KineticBlockEntity be, double ratio) {
        double speed = NetworkDynamics.preciseSpeed(be);
        if (be instanceof IPumpDynamics pump) {
            var pipe = be.getBehaviour(FluidTransportBehaviour.TYPE);
            double flow = Math.abs(pump.differentialcreate$flow());
            double rpm = Math.abs(speed) / IdealFluidSolver.REFERENCE_RPM;
            // Include the flow scale implied by the rotor: a blocked graph can
            // leave a few flow ulps even though its exact solution is zero.
            double scale = pipe == null ? Math.abs(pump.differentialcreate$torque())
                : FluidMaterials.of(FluidKinetics.state(pipe).fluid).pumpScale() / IdealFluidSolver.REFERENCE_RPM
                    * (rpm + flow / IdealFluidSolver.REFERENCE_FLOW)
                    * Math.max(flow, rpm * IdealFluidSolver.REFERENCE_FLOW);
            double reaction = resolvedTorque(-pump.differentialcreate$torque(), scale);
            boolean driving = reaction * speed > 0 || speed == 0 && reaction != 0;
            return new LoadFactor.Member(ratio, speed, driving ? reaction : 0,
                Math.max(0, -reaction * Math.signum(speed)), driving);
        }
        var curve = be.isSource() ? SourcePhysics.curve(be) : new SourcePhysics.Curve(0, 0);
        // Power limits and governor admission can produce genuine tiny drive.
        // Use that actual term, rather than the unthrottled stall torque.
        double driving = DriveTorque.torque(curve.stallTorque(), curve.powerLimit(), speed, curve.governorSpeed(), curve.governorRange());
        double drive = resolvedTorque(curve.torque(speed), Math.abs(driving) + Math.abs(curve.damping() * speed));
        return new LoadFactor.Member(ratio, speed, drive, MachineActivity.loadTorque(be, speed),
            curve.stallTorque() != 0);
    }

    /** Readout only: cancellation and pressure projection leave small rounding
     * residues. Never divide those into a fabricated load or braking state.
     * Scale with floating-point precision, not a fixed torque/flow cutoff.
     * Physical forces, speeds, inventory and energy integration stay untouched. */
    private static double resolvedTorque(double value, double scale) {
        return Double.isFinite(scale) && Math.abs(value) <= 64 * Math.ulp(scale) ? 0 : value;
    }
}
