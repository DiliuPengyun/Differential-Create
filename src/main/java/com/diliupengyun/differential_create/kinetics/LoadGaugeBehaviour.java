package com.diliupengyun.differential_create.kinetics;

import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.network.chat.Component;

/** All gauges share the network's accepted-tick sample, including without viewers. */
public final class LoadGaugeBehaviour extends BlockEntityBehaviour {
    public static final BehaviourType<LoadGaugeBehaviour> TYPE = new BehaviourType<>();
    private int lastSignal = -1;

    public LoadGaugeBehaviour(StressGaugeBlockEntity gauge) { super(gauge); }
    @Override public BehaviourType<?> getType() { return TYPE; }
    @Override public void tick() { refresh(); }
    @Override public void initialize() { refresh(); }

    public static LoadFactor.Reading sample(KineticNetwork network) {
        var reading = NetworkLoad.calculate(network);
        ((INetworkDynamics) network).differentialcreate$data().loadFactor = reading;
        for (var be : network.members.keySet()) {
            var gauge = be.getBehaviour(TYPE);
            if (gauge != null) gauge.refresh();
        }
        return reading;
    }

    public static LoadFactor.Reading reading(StressGaugeBlockEntity gauge) {
        if (gauge.getLevel() == null) return LoadFactor.Reading.UNKNOWN;
        if (gauge.getLevel().isClientSide) {
            if (gauge.getLevel() instanceof net.createmod.ponder.api.level.PonderLevel level)
                return com.diliupengyun.differential_create.client.ponder.PonderKinetics.of(level).loadReading(gauge);
            return gauge.hasNetwork() ? ClientNetworkSpeeds.loadFactorOf(gauge) : LoadFactor.Reading.NO_POWER;
        }
        return gauge.hasNetwork() ? ((INetworkDynamics) gauge.getOrCreateNetwork()).differentialcreate$data().loadFactor
            : LoadFactor.Reading.NO_POWER;
    }

    public void refresh() {
        var gauge = (StressGaugeBlockEntity) blockEntity;
        var reading = reading(gauge);
        gauge.dialTarget = LoadGaugeScale.position(reading);
        gauge.color = LoadGaugeScale.color(reading);
        // Old overstress saves can contain off-scale, randomly wobbling pointers.
        gauge.dialState = Float.isFinite(gauge.dialState) ? Math.clamp(gauge.dialState, LoadGaugeScale.REST, 1) : LoadGaugeScale.REST;
        gauge.prevDialState = Float.isFinite(gauge.prevDialState) ? Math.clamp(gauge.prevDialState, LoadGaugeScale.REST, 1) : LoadGaugeScale.REST;
        var level = gauge.getLevel();
        if (level != null && !level.isClientSide) {
            int signal = LoadGaugeScale.signal(reading);
            if (signal != lastSignal) {
                lastSignal = signal;
                level.updateNeighbourForOutputSignal(gauge.getBlockPos(), gauge.getBlockState().getBlock());
            }
        }
    }

    public static Component text(LoadFactor.Reading reading) {
        return switch (reading.status()) {
            case AVAILABLE -> LoadGaugeScale.available(reading)
                ? Component.literal(LoadGaugeScale.percent(reading.fraction()))
                : Component.translatable("gui.differentialcreate.goggles.load_undefined");
            case UNDEFINED -> Component.translatable("gui.differentialcreate.goggles.load_undefined");
            case NO_POWER -> Component.translatable("gui.differentialcreate.goggles.load_no_power");
            case BRAKING -> Component.translatable("gui.differentialcreate.goggles.load_braking");
            case UNKNOWN -> Component.translatable("gui.differentialcreate.goggles.load_waiting");
        };
    }
}
