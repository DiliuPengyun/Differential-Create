package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;
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
            double relative = edge.ratio * NetworkDynamics.preciseSpeed(edge.a) - NetworkDynamics.preciseSpeed(edge.b);
            loss += LoadFactor.contactLoss(edge.stiffness(), edge.damping(), edge.friction(), edge.twist, relative);
        }
        return LoadFactor.calculate(members, loss);
    }

    private static LoadFactor.Member member(KineticBlockEntity be, double ratio) {
        double speed = NetworkDynamics.preciseSpeed(be);
        if (be instanceof IPumpDynamics pump) {
            double reaction = -pump.differentialcreate$torque();
            boolean driving = reaction * speed > 0 || speed == 0 && reaction != 0;
            return new LoadFactor.Member(ratio, speed, driving ? reaction : 0,
                MachineActivity.loadTorque(be, speed), driving);
        }
        var curve = be.isSource() ? SourcePhysics.curve(be) : new SourcePhysics.Curve(0, 0);
        return new LoadFactor.Member(ratio, speed, curve.torque(speed), MachineActivity.loadTorque(be, speed),
            curve.stallTorque() != 0);
    }
}
