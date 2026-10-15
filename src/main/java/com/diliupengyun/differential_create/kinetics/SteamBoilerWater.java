package com.diliupengyun.differential_create.kinetics;

import java.lang.ref.WeakReference;
import java.util.LinkedHashSet;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.fluids.tank.BoilerData;
import com.simibubi.create.content.fluids.tank.CreativeFluidTankBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import com.simibubi.create.foundation.fluid.FluidHelper;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.fluids.FluidStack;

/** Stationary pumps pay the same per-cylinder demand as mounted inventories.
 * This accepts water directly as steam feed, never as a drainable liquid tank. */
public final class SteamBoilerWater {
    public static final class State {
        WeakReference<FluidTankBlockEntity> controller = new WeakReference<>(null);
        long tick = Long.MIN_VALUE;
        double demand, delivered;
    }
    private SteamBoilerWater() {}
    private static State state(BoilerData boiler) { return ((BoilerAdmission) boiler).differentialcreate$waterState(); }
    public static void reset(BoilerData boiler) {
        var state = state(boiler); state.tick = Long.MIN_VALUE; state.demand = state.delivered = 0;
        BoilerAdmission.supply(boiler, 0, 0);
    }

    public static void bind(FluidTankBlockEntity controller) {
        state(controller.boiler).controller = new WeakReference<>(controller);
    }

    public static void tick(FluidTankBlockEntity controller) {
        var boiler = controller.boiler;
        if (boiler.needsHeatLevelUpdate && boiler.updateTemperature(controller)) controller.notifyUpdate();
        begin(controller);
        award(controller);
        if (controller.getLevel().getGameTime() % 5 == 0 && boiler.isActive()) controller.notifyUpdate();
    }

    public static void begin(FluidTankBlockEntity controller) {
        var level = controller.getLevel();
        if (level == null || level.isClientSide || controller.isVirtual() || controller.isRemoved() || !controller.isController()) return;
        var boiler = controller.boiler; var state = state(boiler);
        bind(controller);
        long tick = level.getGameTime();
        if (state.tick == tick) return;
        state.tick = tick;
        BoilerAdmission.set(boiler, 1);
        state.demand = demand(controller); state.delivered = 0;
        var links = (SteamHoseLinks.TankLinks) controller;
        double paid = links.differentialcreate$feedWater();
        state.delivered = Math.min(paid, state.demand);
        links.differentialcreate$feedWater(paid - state.delivered);
        if (controller instanceof CreativeFluidTankBlockEntity) state.delivered = state.demand;
        BoilerAdmission.supply(boiler, state.demand, state.delivered);
    }

    /** SIMULATE reserves nothing; repeated ports share one request per world tick. */
    public static int fill(BoilerData boiler, FluidStack resource, boolean execute) {
        if (!FluidHelper.isWater(resource.getFluid()) || resource.isEmpty() || !boiler.isActive()) return 0;
        var state = state(boiler); var controller = state.controller.get();
        if (controller == null || controller.isRemoved() || controller.getLevel() == null
            || controller.getLevel().isClientSide || !controller.isController() || controller.boiler != boiler
            || !controller.getLevel().isLoaded(controller.getBlockPos())
            || !(SourcePhysics.thermalSteamLevel(boiler, controller.getTotalTankSize()) > 0)) return 0;
        begin(controller);
        int amount = Math.min(resource.getAmount(), (int) Math.ceil(Math.max(0, state.demand - state.delivered)));
        if (execute && amount > 0) {
            double used = Math.min(amount, state.demand - state.delivered);
            state.delivered += used;
            var links = (SteamHoseLinks.TankLinks) controller;
            links.differentialcreate$feedWater(links.differentialcreate$feedWater() + amount - used);
            BoilerAdmission.supply(boiler, state.demand, state.delivered);
            award(controller);
            controller.setChanged();
        }
        return amount;
    }
    private static void award(FluidTankBlockEntity controller) {
        if (SourcePhysics.steamLevel(controller.boiler, controller.getTotalTankSize()) == 18)
            controller.award(AllAdvancements.STEAM_ENGINE_MAXED);
    }

    public static double demand(FluidTankBlockEntity controller) {
        var level = controller.getLevel(); var boiler = controller.boiler;
        double thermal = SourcePhysics.thermalSteamLevel(boiler, controller.getTotalTankSize());
        if (!(thermal > 0)) return 0;
        var engines = new LinkedHashSet<SteamEngineBlockEntity>();
        for (int y = 0; y < controller.getHeight(); y++) for (int x = 0; x < controller.getWidth(); x++)
            for (int z = 0; z < controller.getWidth(); z++) {
                var at = controller.getBlockPos().offset(x, y, z);
                if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof FluidTankBlockEntity part)) continue;
                for (var side : Direction.values()) {
                    var pos = at.relative(side);
                    if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof SteamEngineBlockEntity engine
                        && !engine.isRemoved() && engine.getTank() == controller) engines.add(engine);
                }
                for (var offset : ((SteamHoseLinks.TankLinks) part).differentialcreate$engines()) {
                    var pos = at.offset(offset);
                    if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof SteamEngineBlockEntity engine
                        && !engine.isRemoved() && engine.getTank() == controller) engines.add(engine);
                }
            }
        double demand = 0;
        for (var engine : engines) {
            var shaft = engine.getShaft();
            if (shaft == null || !engine.isValid()) continue;
            int direction = shaft.movementDirection == 0 ? 1 : shaft.movementDirection;
            demand += SourcePhysics.steamDemand(BlockStressValues.getCapacity(engine.getBlockState().getBlock()),
                thermal, boiler.attachedEngines, direction, 1, NetworkDynamics.preciseSpeed(shaft));
        }
        // A whistle-only boiler retains its native water-fed operation.
        if (engines.isEmpty() && boiler.attachedWhistles > 0) demand = SteamDemand.RATED_FLOW * thermal;
        return demand;
    }
}
