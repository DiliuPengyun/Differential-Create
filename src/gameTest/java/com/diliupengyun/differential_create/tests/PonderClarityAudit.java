package com.diliupengyun.differential_create.tests;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.diliupengyun.differential_create.kinetics.BoilerAdmission;
import com.diliupengyun.differential_create.kinetics.IEjectorDynamics;
import com.diliupengyun.differential_create.kinetics.IPumpDynamics;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;

/** A claimed result must occur while its caption is visible, not merely somewhere in the scene. */
final class PonderClarityAudit {
    private final Set<String> seen = new HashSet<>();
    private final Map<String, Double> first = new HashMap<>(), last = new HashMap<>();
    private boolean coastingPressStarted;
    private final Map<BlockPos, Integer> comparedSheets = new HashMap<>();
    private int comparedBatch;
    private int flywheelBatch, flywheelInputs, flywheelSheets;

    private boolean caption(PonderScene scene, List<String> active, int number) {
        return active.contains(PonderIndex.getLangAccess().getSpecific(scene.getId(), "text_" + number));
    }
    private void reading(String key, double value) { first.putIfAbsent(key, value); last.put(key, value); }
    private double change(String key) { return last.getOrDefault(key, 0d) - first.getOrDefault(key, 0d); }
    private double rpm(PonderScene scene, int x, int y, int z) {
        return scene.getWorld().getBlockEntity(new BlockPos(x, y, z)) instanceof KineticBlockEntity be ? be.getSpeed() : 0;
    }

    void sample(PonderScene scene, List<String> active) {
        var world = scene.getWorld();
        switch (scene.getId().toString()) {
            case "create:windmill_source" -> {
                if (caption(scene, active, 7)) scene.forEachVisible(net.createmod.ponder.api.element.WorldSectionElement.class, section -> {
                    if (!((com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor) section).differentialcreate$selection().test(new BlockPos(3, 2, 0))) return;
                    if (section.getAnimatedRotation().lengthSqr() > 1e-8)
                        throw new AssertionError("Disassembled windmill sails are not grid-aligned");
                    seen.add("windmill-grid-aligned");
                });
            }
            case "create:piston_pole" -> pistonPassenger(scene);
            case "create:portable_fluid_interface" -> portableFluid(scene, active);
            case "create:portable_storage_interface_redstone" -> portableRedstone(scene, active);
            case "differentialcreate:continuous_work" -> {
                if (!(world.getBlockEntity(new BlockPos(0, 3, 2)) instanceof MechanicalPressBlockEntity press)
                    || !(world.getBlockEntity(new BlockPos(0, 1, 2)) instanceof DepotBlockEntity depot)) return;
                if (caption(scene, active, 1) && press.getPressingBehaviour().running) seen.add("press-start");
                if (caption(scene, active, 2) && press.getPressingBehaviour().running) seen.add("press-slow");
                if (caption(scene, active, 3)) {
                    coastingPressStarted |= press.getPressingBehaviour().running && depot.getHeldItem().is(Items.COPPER_INGOT);
                    if (coastingPressStarted && AllItems.COPPER_SHEET.isIn(depot.getHeldItem())) seen.add("press-coast-finished");
                }
            }
            case "differentialcreate:dynamics" -> {
                boolean accelerating = caption(scene, active, 1), coasting = caption(scene, active, 2);
                if ((accelerating || coasting)
                    && world.getBlockEntity(new BlockPos(0, 1, 2)) instanceof DepotBlockEntity depot) {
                    int inputs = depotCount(depot, Items.COPPER_INGOT);
                    int outputs = depotCount(depot, AllItems.COPPER_SHEET.get());
                    stackedSheets(depot, outputs);
                    if (flywheelBatch == 0) {
                        flywheelBatch = inputs;
                        flywheelInputs = inputs;
                    }
                    if (inputs + outputs != flywheelBatch || inputs > flywheelInputs || outputs < flywheelSheets)
                        throw new AssertionError("The startup batch must stay on the depot through coasting; no silent replacement");
                    if (accelerating) reading("flywheel-startup", Math.abs(rpm(scene, 2, 3, 2)));
                    if (coasting) {
                        if (!world.getBlockState(new BlockPos(3, 3, 2)).isAir())
                            throw new AssertionError("The disconnected-power caption needs a visible break in the input shaft");
                        seen.add("flywheel-disconnected");
                        reading("flywheel-coast", Math.abs(rpm(scene, 2, 3, 2)));
                        reading("flywheel-coast-products", outputs);
                        if (change("flywheel-coast-products") > 0 && Math.abs(rpm(scene, 0, 3, 2)) > 1)
                            seen.add("flywheel-coast-work");
                    }
                    flywheelInputs = inputs;
                    flywheelSheets = outputs;
                }
                if (caption(scene, active, 4)
                    && world.getBlockEntity(new BlockPos(0, 2, 3)) instanceof DepotBlockEntity fast
                    && world.getBlockEntity(new BlockPos(0, 1, 2)) instanceof DepotBlockEntity slow) {
                    int fastInput = depotCount(fast, Items.COPPER_INGOT), slowInput = depotCount(slow, Items.COPPER_INGOT);
                    int fastOutput = depotCount(fast, AllItems.COPPER_SHEET.get()), slowOutput = depotCount(slow, AllItems.COPPER_SHEET.get());
                    if (comparedBatch == 0) {
                        if (fastInput <= 0 || fastInput != slowInput || fastOutput != 0 || slowOutput != 0)
                            throw new AssertionError("The simultaneous-press caption must begin with equal fresh batches, before either has products: "
                                + fastInput + "/" + fastOutput + " vs " + slowInput + "/" + slowOutput);
                        comparedBatch = fastInput;
                    }
                    for (var depot : List.of(fast, slow)) {
                        int inputs = depotCount(depot, Items.COPPER_INGOT), outputs = depotCount(depot, AllItems.COPPER_SHEET.get());
                        stackedSheets(depot, outputs);
                        if (inputs + outputs != comparedBatch)
                            throw new AssertionError("Press comparison lost or created workpieces at " + depot.getBlockPos());
                        Integer previous = comparedSheets.put(depot.getBlockPos().immutable(), outputs);
                        if (previous != null && outputs > previous) {
                            var press = (MechanicalPressBlockEntity) world.getBlockEntity(depot.getBlockPos().above(2));
                            if (outputs - previous != 1 || press.getPressingBehaviour().getRenderedHeadOffset(1) < .99)
                                throw new AssertionError("Each compared press contact must convert exactly one ingot at " + depot.getBlockPos());
                        }
                    }
                    if (fastOutput == comparedBatch && slowInput > 0) seen.add("geared-batch-first");
                    if (fastOutput == comparedBatch && slowOutput == comparedBatch
                        && !((MechanicalPressBlockEntity) world.getBlockEntity(fast.getBlockPos().above(2))).getPressingBehaviour().running
                        && !((MechanicalPressBlockEntity) world.getBlockEntity(slow.getBlockPos().above(2))).getPressingBehaviour().running)
                        seen.add("geared-batch-finished");
                }
            }
            case "differentialcreate:clutch" -> {
                if (caption(scene, active, 3) && Math.abs(rpm(scene, 0, 1, 2) - rpm(scene, 4, 1, 2)) > 10)
                    seen.add("clutch-independent");
            }
            case "differentialcreate:ratio" -> {
                double input = rpm(scene, 2, 2, 2), output = rpm(scene, 2, 1, 2);
                if (caption(scene, active, 2) && input * output < -10) seen.add("ratio-reversed");
                if (caption(scene, active, 4) && Math.abs(output - 2 * input) < 1 && Math.abs(input) > 5)
                    seen.add("ratio-doubled");
            }
            case "differentialcreate:ejector_work" -> {
                if (caption(scene, active, 1) && world.getBlockEntity(new BlockPos(4, 1, 2)) instanceof IEjectorDynamics ejector)
                    reading("ejector-charge", ejector.differentialcreate$charge().travel());
                if (caption(scene, active, 2) && world.getBlockEntity(new BlockPos(0, 1, 2)) instanceof DepotBlockEntity depot
                    && depot.getHeldItem().is(Items.COPPER_INGOT)) seen.add("ejector-far");
                if (caption(scene, active, 3) && world.getBlockEntity(new BlockPos(2, 1, 2)) instanceof DepotBlockEntity depot
                    && depot.getHeldItem().is(Items.COPPER_INGOT)) seen.add("ejector-near");
            }
            case "differentialcreate:fan_work" -> {
                int phase = caption(scene, active, 1) ? 1 : caption(scene, active, 2) ? 2 : 0;
                if (phase > 0 && world.getBlockEntity(new BlockPos(2, 1, 1)) instanceof DepotBlockEntity depot) {
                    depot.getBehaviour(com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TYPE)
                        .handleProcessingOnAllItems(stack -> {
                            String key = "fan-smelt-" + phase;
                            if (stack.stack.is(Items.GOLD_ORE)) {
                                seen.add(key + "-ore");
                                if (stack.processedBy != null && stack.processingTime > 0)
                                    first.putIfAbsent(key + "-timer", (double) stack.processingTime);
                            }
                            if (seen.contains(key + "-ore") && stack.stack.is(Items.GOLD_INGOT)) seen.add(key);
                            return com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult.doNothing();
                        });
                }
                if (caption(scene, active, 3)) reading("fan-coast", Math.abs(rpm(scene, 2, 2, 4)));
            }
            case "differentialcreate:steam_power" -> {
                if (caption(scene, active, 3) && world.getBlockEntity(new BlockPos(3, 2, 2)) instanceof FluidTankBlockEntity boiler
                    && boiler.boiler.activeHeat == 0 && BoilerAdmission.demand(boiler.boiler) == 0) {
                    for (var pos : BlockPos.betweenClosed(3, 1, 2, 4, 1, 3))
                        if (!world.getBlockState(pos).isAir()) throw new AssertionError("Steam shutdown must visibly remove the heat sources");
                    boolean coasting = true;
                    for (int z : new int[]{2, 3}) {
                        if (!(world.getBlockEntity(new BlockPos(0, 3, z)) instanceof com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity shaft)) {
                            coasting = false;
                            continue;
                        }
                        var curve = com.diliupengyun.differential_create.kinetics.SourcePhysics.steamCurve(shaft);
                        coasting &= curve.stallTorque() == 0 && curve.torque(shaft.getSpeed()) == 0 && Math.abs(shaft.getSpeed()) > 1;
                    }
                    if (coasting) seen.add("steam-coast");
                }
            }
            case "differentialcreate:train_driving" -> {
                if (caption(scene, active, 2))
                    for (var entry : PonderSimulation.of(world).readings().entrySet()) if (entry.getKey().startsWith("train")) {
                        var values = entry.getValue();
                        if (values.get(2) > 1 && Math.abs(values.getFirst()) < .001) seen.add("neutral-engine");
                    }
                // The train reading's fractional receipt pool is not its total
                // stock. Observe the actual synchronized mounted tank inventory.
                if (caption(scene, active, 2) && world.getBlockEntity(new BlockPos(5, 3, 5)) instanceof FluidTankBlockEntity tank)
                    reading("neutral-water", tank.getTankInventory().getFluidAmount());
            }
            case "create:mechanical_pump_speed" -> {
                if (world.getBlockEntity(new BlockPos(0, 1, 2)) instanceof FluidTankBlockEntity tank) {
                    if (caption(scene, active, 3)) reading("pump-fast-receiver", tank.getTankInventory().getFluidAmount());
                    if (caption(scene, active, 4)) {
                        reading("pump-parallel-receiver", tank.getTankInventory().getFluidAmount());
                        if (tank.getTankInventory().getFluid().getFluid() == Fluids.LAVA) seen.add("pump-parallel-lava");
                    }
                }
                boolean together = caption(scene, active, 4), opposed = caption(scene, active, 5);
                if (together || opposed) {
                    boolean flowing = true;
                    for (int z = 1; z <= 3; z++) {
                        var pos = new BlockPos(2, 1, z);
                        var direction = opposed && z == 1 ? Direction.EAST : Direction.WEST;
                        if (world.getBlockState(pos).getValue(PumpBlock.FACING) != direction)
                            throw new AssertionError("Pump arrow disagrees with its active caption at " + pos);
                        flowing &= world.getBlockEntity(pos) instanceof IPumpDynamics pump
                            && pump.differentialcreate$flow() * direction.getStepX() > .05;
                    }
                    if (flowing) seen.add(together ? "pump-parallel-delivery" : "pump-parallel-counterflow");
                }
            }
            case "differentialcreate:pump_direction" -> {
                int phase = caption(scene, active, 1) ? 1 : caption(scene, active, 2) ? 2
                    : caption(scene, active, 3) ? 3 : 0;
                if (phase > 0) {
                    tankWaterline(scene, new BlockPos(0, 1, 2), "pump-source-" + phase);
                    tankWaterline(scene, new BlockPos(4, 1, 2), "pump-receiver-" + phase);
                }
                if (world.getBlockEntity(new BlockPos(0, 1, 2)) instanceof FluidTankBlockEntity source
                    && world.getBlockEntity(new BlockPos(4, 1, 2)) instanceof FluidTankBlockEntity receiver) {
                    double total = source.getTankInventory().getFluidAmount() + receiver.getTankInventory().getFluidAmount()
                        + com.diliupengyun.differential_create.kinetics.FluidKinetics.ownedVolume(world);
                    if (Math.abs(total - 16000) > .01)
                        throw new AssertionError("Pump direction lesson lost or created water: " + total);
                }
            }
            case "differentialcreate:pump_network" -> {
                if (world.getBlockEntity(new BlockPos(3, 1, 2)) instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity glass) {
                    var pipe = glass.getBehaviour(com.simibubi.create.content.fluids.FluidTransportBehaviour.TYPE);
                    if (pipe != null) {
                        var fluid = com.diliupengyun.differential_create.kinetics.FluidKinetics.state(pipe);
                        if (caption(scene, active, 1) && fluid.volume() < .01 && fluid.airVolume() > .1)
                            seen.add("empty-air-pipe");
                        if (caption(scene, active, 2) && fluid.volume() > .1 && fluid.airVolume() > .1)
                            seen.add("pipe-water-front");
                    }
                }
                if (caption(scene, active, 3) && world.getBlockEntity(new BlockPos(4, 1, 2)) instanceof FluidTankBlockEntity tank)
                    reading("pipe-receiver", tank.getTankInventory().getFluidAmount());
            }
            case "create:valve_pipe" -> {
                var valve = world.getBlockState(new BlockPos(2, 1, 1));
                if (com.simibubi.create.AllBlocks.FLUID_VALVE.has(valve)) {
                    if (valve.getValue(com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock.ENABLED))
                        seen.add("native-valve-opened");
                    else if (seen.contains("native-valve-opened") && caption(scene, active, 4))
                        seen.add("native-valve-closed");
                }
                if (caption(scene, active, 3) && world.getBlockEntity(new BlockPos(1, 1, 3)) instanceof FluidTankBlockEntity tank)
                    reading("native-valve-receiver", tank.getTankInventory().getFluidAmount());
            }
            case "create:smart_pipe" -> {
                if (caption(scene, active, 5)) {
                    if (world.getBlockEntity(new BlockPos(1, 1, 4)) instanceof com.simibubi.create.content.fluids.pipes.SmartFluidPipeBlockEntity filter) {
                        var pipe = filter.getBehaviour(com.simibubi.create.content.fluids.FluidTransportBehaviour.TYPE);
                        if (pipe != null)
                            for (var part : com.diliupengyun.differential_create.kinetics.FluidKinetics.state(pipe).parcels())
                                if (!part.identity().isEmpty() && part.identity().getFluid() != net.neoforged.neoforge.common.NeoForgeMod.MILK.get())
                                    throw new AssertionError("Milk filter contains a stranded non-milk parcel: " + part);
                    }
                    if (world.getBlockEntity(new BlockPos(4, 1, 3)) instanceof FluidTankBlockEntity chocolate
                        && world.getBlockEntity(new BlockPos(4, 1, 4)) instanceof FluidTankBlockEntity milk) {
                        if (!com.simibubi.create.AllFluids.CHOCOLATE.is(chocolate.getTankInventory().getFluid().getFluid())
                            || milk.getTankInventory().getFluid().getFluid() != net.neoforged.neoforge.common.NeoForgeMod.MILK.get())
                            throw new AssertionError("Smart pipe routing delivered fluid into the wrong tank");
                        reading("native-filter-chocolate", chocolate.getTankInventory().getFluidAmount());
                        reading("native-filter-milk", milk.getTankInventory().getFluidAmount());
                        tankWaterline(scene, chocolate.getBlockPos(), "native-filter-chocolate-surface");
                        tankWaterline(scene, milk.getBlockPos(), "native-filter-milk-surface");
                    }
                }
            }
            default -> { }
        }
    }

    private void portableRedstone(PonderScene scene, List<String> active) {
        var world = scene.getWorld();
        var station = new BlockPos(1, 1, 3);
        if (!(world.getBlockEntity(station)
            instanceof com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity port)) return;
        var state = ((com.diliupengyun.differential_create.kinetics.portable.PortableEndpoint) port).differentialcreate$docking();
        boolean powered = world.hasNeighborSignal(station);
        var phase = state.phase;
        if (powered) {
            if (!port.isPowered() || state.permission())
                throw new AssertionError("Powered portable interfaces must immediately revoke transfer permission");
            if (caption(scene, active, 1) && (state.extension() > 1e-6
                || phase != com.diliupengyun.differential_create.kinetics.portable.DockingState.Phase.IDLE))
                throw new AssertionError("The redstone lesson must keep both heads retracted while the visible circuit is powered");
            seen.add("portable-redstone-powered");
        } else if (phase == com.diliupengyun.differential_create.kinetics.portable.DockingState.Phase.TRANSFER) {
            seen.add("portable-redstone-initial-connection");
        }
        if (seen.contains("portable-redstone-initial-connection")
            && phase == com.diliupengyun.differential_create.kinetics.portable.DockingState.Phase.RETRACTING)
            seen.add("portable-redstone-retracted");
        scene.forEachVisible(net.createmod.ponder.api.element.WorldSectionElement.class, section -> {
            if (!((com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor) section)
                .differentialcreate$selection().test(new BlockPos(1, 3, 3))) return;
            double angle = section.getAnimatedRotation().y;
            if (Math.abs(angle) > .1 && !seen.contains("portable-redstone-retracted"))
                throw new AssertionError("The redstone lesson must show a real connection and retraction before rotating");
            if (powered) reading("portable-redstone-travel", angle);
            if (caption(scene, active, 1)) {
                if (!powered) throw new AssertionError("The redstone caption has no powered input");
                reading("portable-redstone-caption-travel", angle);
            }
        });
    }

    private void portableFluid(PonderScene scene, List<String> active) {
        var world = scene.getWorld();
        var bearing = new BlockPos(5, 1, 2);
        if (!(world.getBlockEntity(new BlockPos(2, 2, 2))
            instanceof com.diliupengyun.differential_create.kinetics.portable.PortableEndpoint endpoint)) return;
        var state = endpoint.differentialcreate$docking();
        var phase = state.phase;
        boolean installing = caption(scene, active, 2) || caption(scene, active, 3);
        scene.forEachVisible(net.createmod.ponder.api.element.WorldSectionElement.class, section -> {
            var selected = ((com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor) section).differentialcreate$selection();
            if (!selected.test(new BlockPos(5, 3, 2))) return;
            double angle = section.getAnimatedRotation().y;
            if (installing) {
                reading("portable-install-angle", angle);
                if (Math.abs(PonderKinetics.of(world).outputSpeed(bearing)) > .001
                    || Math.abs(Math.IEEEremainder(angle, 360)) > .05)
                    throw new AssertionError("Installation and spacing must show a stopped, aligned assembly");
                seen.add("portable-installed-stopped");
            }
            if (phase == com.diliupengyun.differential_create.kinetics.portable.DockingState.Phase.TRANSFER
                && Math.abs(Math.IEEEremainder(angle, 360)) > 2.5)
                throw new AssertionError("The fluid-interface heads connected while visibly tilted: " + angle);
        });
        boolean receiving = caption(scene, active, 6), draining = caption(scene, active, 7);
        if (caption(scene, active, 5) || receiving || draining) {
            if (phase != com.diliupengyun.differential_create.kinetics.portable.DockingState.Phase.TRANSFER)
                throw new AssertionError("The connected/inserting/extracting caption has no usable fluid connection at tick "
                    + scene.getCurrentTime() + ": " + phase);
            seen.add("portable-connected-caption");
        }
        double inventory = 0;
        for (var pos : List.of(new BlockPos(0, 1, 5), new BlockPos(5, 3, 2), new BlockPos(6, 3, 2))) {
            if (!(world.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) continue;
            inventory += tank.getTankInventory().getFluidAmount();
            if (pos.getX() == 0) continue;
            if (receiving) reading("portable-in-" + pos.getX(), tank.getTankInventory().getFluidAmount());
            if (draining) reading("portable-out-" + pos.getX(), tank.getTankInventory().getFluidAmount());
        }
        if (scene.getCurrentTime() >= 10) {
            inventory += com.diliupengyun.differential_create.kinetics.FluidKinetics.ownedVolume(world);
            if (Math.abs(inventory - 24000) > .01)
                throw new AssertionError("Portable-fluid transfer lost or created chocolate: " + inventory);
        }
        if (caption(scene, active, 8)) {
            if (phase == com.diliupengyun.differential_create.kinetics.portable.DockingState.Phase.RETRACTING)
                seen.add("portable-retracted");
            if (seen.contains("portable-retracted")
                && phase == com.diliupengyun.differential_create.kinetics.portable.DockingState.Phase.IDLE
                && Math.abs(PonderKinetics.of(world).outputSpeed(bearing)) > 1)
                seen.add("portable-departed");
        }
    }

    private void tankWaterline(PonderScene scene, BlockPos pos, String key) {
        if (!(scene.getWorld().getBlockEntity(pos) instanceof FluidTankBlockEntity tank)
            || !Boolean.TRUE.equals(tank.getExtraData()) || tank.getFluidLevel() == null
            || tank.getTankInventory().isEmpty())
            throw new AssertionError("Tank caption needs a visible liquid surface at " + pos);
        // Native FluidTankRenderer's actual liquid height, in blocks. Inventory
        // changes alone miss a frozen chaser or an imperceptibly small window.
        reading(key, tank.getFluidLevel().getValue(1) * (tank.getHeight() - 9 / 16d));
    }

    private static void stackedSheets(DepotBlockEntity depot, int expected) {
        var inventory = depot.getBehaviour(com.simibubi.create.content.logistics.depot.DepotBehaviour.TYPE).itemHandler;
        for (int slot = 0; slot < inventory.getSlots(); slot++)
            if (slot != 0 && slot != 5 && AllItems.COPPER_SHEET.isIn(inventory.getStackInSlot(slot)))
                throw new AssertionError("Earlier sheets must share one rim position; the final sheet stays centered");
        var center = inventory.getStackInSlot(0);
        int centered = AllItems.COPPER_SHEET.isIn(center) ? center.getCount() : 0;
        if (centered > 1) throw new AssertionError("Only the final sheet may remain in the center");
        var stack = inventory.getStackInSlot(5);
        if (stack.getCount() != expected - centered || expected > centered && !AllItems.COPPER_SHEET.isIn(stack))
            throw new AssertionError("The rim stack and final centered sheet must contain all " + expected
                + " finished sheets at " + depot.getBlockPos());
    }

    private static int depotCount(DepotBlockEntity depot, net.minecraft.world.item.Item item) {
        var inventory = depot.getBehaviour(com.simibubi.create.content.logistics.depot.DepotBehaviour.TYPE).itemHandler;
        int count = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++)
            if (inventory.getStackInSlot(slot).is(item)) count += inventory.getStackInSlot(slot).getCount();
        return count;
    }

    private void pistonPassenger(PonderScene scene) {
        scene.forEachVisible(net.createmod.ponder.api.element.ParrotElement.class, bird -> {
            try {
                var entityField = net.createmod.ponder.foundation.element.ParrotElementImpl.class.getDeclaredField("entity");
                entityField.setAccessible(true);
                var entity = (net.minecraft.world.entity.animal.Parrot) entityField.get(bird);
                if (entity == null) return;
                scene.forEachVisible(net.createmod.ponder.api.element.WorldSectionElement.class, section -> {
                    if (!((com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor) section)
                        .differentialcreate$selection().test(new BlockPos(2, 1, 1))) return;
                    for (float partial : new float[]{0, .5f, 1}) {
                        var pose = new com.mojang.blaze3d.vertex.PoseStack();
                        ((net.createmod.ponder.foundation.element.WorldSectionElementImpl) section).transformMS(pose, partial);
                        var top = new net.minecraft.world.phys.Vec3(pose.last().pose().transformPosition(new org.joml.Vector3f(2.5f, 2, 1.5f)));
                        var passenger = new net.minecraft.world.phys.Vec3(entity.xo, entity.yo, entity.zo)
                            .lerp(entity.position(), partial).add(2.5, 2, 2.5);
                        if (top.distanceToSqr(passenger) > 1e-10)
                            throw new AssertionError("Piston passenger slipped off its platform at tick " + scene.getCurrentTime()
                                + ", partial=" + partial + ": " + passenger + " vs " + top);
                    }
                    reading("piston-passenger-travel", section.getAnimatedOffset().x);
                    if (section.getAnimatedOffset().x <= -1.999) seen.add("piston-passenger-arrived");
                });
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        });
    }

    void verify(PonderScene scene) {
        String id = scene.getId().toString();
        var expected = switch (id) {
            case "create:windmill_source" -> List.of("windmill-grid-aligned");
            case "create:piston_pole" -> List.of("piston-passenger-arrived");
            case "differentialcreate:continuous_work" -> List.of("press-start", "press-slow", "press-coast-finished");
            case "differentialcreate:dynamics" -> List.of("flywheel-disconnected", "flywheel-coast-work",
                "geared-batch-first", "geared-batch-finished");
            case "differentialcreate:clutch" -> List.of("clutch-independent");
            case "differentialcreate:ratio" -> List.of("ratio-reversed", "ratio-doubled");
            case "differentialcreate:ejector_work" -> List.of("ejector-far", "ejector-near");
            case "differentialcreate:fan_work" -> List.of("fan-smelt-1", "fan-smelt-2");
            case "differentialcreate:steam_power" -> List.of("steam-coast");
            case "differentialcreate:train_driving" -> List.of("neutral-engine");
            case "differentialcreate:pump_network" -> List.of("empty-air-pipe", "pipe-water-front");
            case "create:mechanical_pump_speed" -> List.of("pump-parallel-lava", "pump-parallel-delivery", "pump-parallel-counterflow");
            case "create:valve_pipe" -> List.of("native-valve-opened", "native-valve-closed");
            case "create:portable_fluid_interface" -> List.of("portable-installed-stopped", "portable-connected-caption",
                "portable-retracted", "portable-departed");
            case "create:portable_storage_interface_redstone" -> List.of("portable-redstone-initial-connection",
                "portable-redstone-retracted", "portable-redstone-powered");
            default -> List.<String>of();
        };
        if (!seen.containsAll(expected)) throw new AssertionError(id + " caption/result mismatch: expected=" + expected + ", witnessed=" + seen);
        if (id.equals("create:piston_pole") && change("piston-passenger-travel") > -1.999)
            throw new AssertionError("The passenger must stay on the block throughout the complete two-block piston stroke");
        if (id.equals("create:portable_storage_interface_redstone")
            && (change("portable-redstone-travel") < 360 || change("portable-redstone-caption-travel") < 360))
            throw new AssertionError("Powered interfaces must pass each other without docking, including a full pass during the caption");
        if (id.equals("create:portable_fluid_interface")) {
            if (Math.abs(change("portable-install-angle")) > .001)
                throw new AssertionError("The installation labels moved away from the rotating body");
            for (int tank : new int[]{5, 6})
                if (change("portable-in-" + tank) < 3000 || change("portable-out-" + tank) > -3000)
                    throw new AssertionError("Both mounted tanks must visibly fill and drain during their captions: " + first + " -> " + last);
        }
        if (id.equals("differentialcreate:dynamics")
            && (change("flywheel-startup") < 10 || change("flywheel-coast") >= 0))
            throw new AssertionError("The flywheel must accelerate under drive and spend stored energy after disconnection: "
                + first + " -> " + last);
        if (id.equals("differentialcreate:ejector_work") && change("ejector-charge") < 100)
            throw new AssertionError("Ejector charging must happen during its caption");
        if (id.equals("differentialcreate:fan_work") && change("fan-coast") > -5)
            throw new AssertionError("Fan must visibly slow down during its coasting caption");
        if (id.equals("differentialcreate:fan_work")
            && (!first.containsKey("fan-smelt-1-timer") || !first.containsKey("fan-smelt-2-timer")
                || Math.abs(first.get("fan-smelt-1-timer") - first.get("fan-smelt-2-timer")) > 1))
            throw new AssertionError("Both fan speeds must demonstrate the same real processing time: " + first);
        if (id.equals("differentialcreate:train_driving") && change("neutral-water") > -.01)
            throw new AssertionError("Real neutral throttle must actually consume water");
        if (id.equals("differentialcreate:pump_network") && change("pipe-receiver") < 100)
            throw new AssertionError("Pipe receiver must visibly rise during its caption: " + first + " -> " + last);
        if (id.equals("differentialcreate:pump_direction"))
            for (int phase = 1; phase <= 3; phase++) {
                double sign = phase == 1 ? 1 : -1;
                double minimum = phase == 3 ? .08 : .25;
                if (sign * change("pump-receiver-" + phase) < minimum
                    || -sign * change("pump-source-" + phase) < minimum)
                    throw new AssertionError("Pump phase " + phase + " needs plainly visible, opposite waterline changes: "
                        + first + " -> " + last);
            }
        if (id.equals("create:valve_pipe") && change("native-valve-receiver") < 20)
            throw new AssertionError("Native valve must actually pass liquid during its open-valve caption: " + first + " -> " + last);
        if (id.equals("create:mechanical_pump_speed")
            && (change("pump-fast-receiver") < 200 || change("pump-parallel-receiver") < 100))
            throw new AssertionError("Pump captions need real forward delivery: " + first + " -> " + last);
        if (id.equals("create:smart_pipe")
            && (change("native-filter-chocolate") < 500 || change("native-filter-milk") < 500
                || change("native-filter-chocolate-surface") < .04 || change("native-filter-milk-surface") < .04))
            throw new AssertionError("Both smart-pipe tanks must receive new matching liquid during the final caption: " + first + " -> " + last
                + "; pipes=" + filterRoutingState(scene));
    }

    private List<String> filterRoutingState(PonderScene scene) {
        var result = new java.util.ArrayList<String>();
        for (var be : scene.getWorld().getBlockEntities())
            if (be instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity smart) {
                var pipe = smart.getBehaviour(com.simibubi.create.content.fluids.FluidTransportBehaviour.TYPE);
                if (pipe != null) {
                    var saved = com.diliupengyun.differential_create.kinetics.FluidKinetics.state(pipe);
                    result.add(be.getBlockPos() + " " + saved.parcels() + " flow=" + saved.outward);
                }
            }
        return result;
    }
}
