package com.diliupengyun.differential_create.tests;

import java.util.HashSet;
import java.util.Set;
import com.diliupengyun.differential_create.client.ponder.PonderHoseSupply;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** A scene completing its instructions is not evidence that its machines actually worked. */
final class PonderOutcomeAudit {
    private final Set<String> seen = new HashSet<>();
    private double distance, maximumEngine;
    private boolean transfer, armTransfer, fanDepotStarted, fanDepotFinished;
    private boolean hoseFilled, hoseDrained, hoseLowered, hoseRefilled, hoseFullPool;
    private boolean infiniteHose, drainTransferred;
    private boolean rollerFilled, rollerSloped, rollerTuff, rollerGrass;
    private boolean honeyExtracted, honeyReceived;
    private int dryPipeTicks;
    private boolean pipeStarted, pipeFront, pipeVented, pipeReceived;
    private double pipeAirBefore;
    void sample(PonderScene scene) {
        var world = scene.getWorld();
        if (scene.getId().toString().equals("differentialcreate:pump_network")) pipeNetwork(scene);
        if (scene.getId().getPath().equals("fluid_pipe_interaction")) {
            var hive = world.getBlockState(new net.minecraft.core.BlockPos(1, 1, 2));
            if (hive.is(net.minecraft.world.level.block.Blocks.BEE_NEST)) {
                honeyExtracted |= hive.getValue(net.minecraft.world.level.block.BeehiveBlock.HONEY_LEVEL) == 0;
                if (world.getBlockEntity(new net.minecraft.core.BlockPos(3, 1, 2)) instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank) {
                    var fluid = tank.getTankInventory().getFluid();
                    if (!fluid.isEmpty() && !com.simibubi.create.AllFluids.HONEY.is(fluid.getFluid()))
                        throw new AssertionError("Honey receiver contains another fluid: " + fluid);
                    honeyReceived |= !fluid.isEmpty() && com.simibubi.create.AllFluids.HONEY.is(fluid.getFluid()) && fluid.getAmount() == 250;
                }
            }
        }
        if (scene.getId().getPath().startsWith("mechanical_roller"))
            for (int x = 0; x <= 5; x++) for (int y = 0; y <= 3; y++) for (int z = 1; z <= 7; z++) {
                var state = world.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
                rollerFilled |= state.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT);
                rollerSloped |= state.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT) && (z < 3 || z > 5);
                rollerTuff |= state.is(net.minecraft.world.level.block.Blocks.TUFF);
                rollerGrass |= state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK);
            }
        if (scene.getId().getPath().equals("hose_pulley_infinite"))
            for (var be : world.getBlockEntities()) if (be instanceof com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity hose)
                infiniteHose |= hose.getBehaviour(com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour.TYPE).isInfinite();
        if (scene.getId().getPath().equals("item_drain")
            && world.getBlockEntity(new net.minecraft.core.BlockPos(1, 1, 4)) instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank)
            drainTransferred |= tank.getTankInventory().getFluidAmount() > 0;
        if (scene.getId().getPath().equals("hose_pulley"))
            for (var pos : world.getBlockMap().keySet()) if (world.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.WATER)) hoseFilled = true;
        if (scene.getId().getPath().equals("hose_pulley_level")) {
            hoseDrained |= PonderHoseSupply.stock(world) > PonderHoseSupply.RECEIVER_STOCK;
            int water = 0;
            for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) for (int y = 1; y <= 3; y++)
                if (!world.getFluidState(new net.minecraft.core.BlockPos(x, y, z)).isEmpty()) water++;
            hoseFullPool |= water == 27;
            hoseLowered |= hoseFullPool && water <= 9 && hoseDrained;
            hoseRefilled |= hoseLowered && water == 27;
        }
        if ((scene.getId().getPath().equals("hose_pulley") || scene.getId().getPath().equals("hose_pulley_level"))
            && scene.getCurrentTime() > 5) {
            double water = 0;
            for (var pos : world.getBlockMap().keySet()) if (world.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.WATER)) water += 1000;
            water += PonderHoseSupply.stock(world);
            var inlet = world.getBlockState(PonderHoseSupply.PORT);
            if (!inlet.is(com.simibubi.create.AllBlocks.FLUID_PIPE.get()))
                throw new AssertionError(scene.getId() + " changed the native inlet pipe");
            for (var face : net.minecraft.core.Direction.values())
                if (inlet.getValue(com.simibubi.create.content.fluids.pipes.FluidPipeBlock.PROPERTY_BY_DIRECTION.get(face)) != (face.getAxis() == net.minecraft.core.Direction.Axis.Y))
                    throw new AssertionError(scene.getId() + " changed the native straight pipe connections");
            scene.forEachVisible(net.createmod.ponder.api.element.WorldSectionElement.class, section -> {
                var selection = ((com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor) section).differentialcreate$selection();
                for (var be : world.getBlockEntities())
                    if (selection.test(be.getBlockPos()) && be instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity)
                        throw new AssertionError(scene.getId() + " exposed an extra supply tank on the stage");
            });
            for (var be : world.getBlockEntities()) if (be instanceof com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity) {
                var handler = world.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, be.getBlockPos(), null);
                water += ((com.simibubi.create.content.fluids.hosePulley.HosePulleyFluidHandler) handler).getInternalTank().getFluidAmount();
            }
            water += com.diliupengyun.differential_create.kinetics.FluidKinetics.ownedVolume(world);
            double expected = scene.getId().getPath().equals("hose_pulley") ? 24000 : 27000 + com.diliupengyun.differential_create.client.ponder.PonderHoseSupply.RECEIVER_STOCK;
            if (Math.abs(water - expected) > .01) throw new AssertionError(scene.getId() + " lost or created water at tick " + scene.getCurrentTime() + ": expected=" + expected + ", actual=" + water);
        }
        if (scene.getId().getPath().equals("mechanical_arm")
            && world.getBlockEntity(new net.minecraft.core.BlockPos(0, 2, 1)) instanceof com.simibubi.create.content.logistics.depot.DepotBlockEntity depot
            && depot.getHeldItem().is(net.minecraft.world.item.Items.COPPER_INGOT)) armTransfer = true;
        if (scene.getId().getPath().equals("fan_processing")
            && world.getBlockEntity(new net.minecraft.core.BlockPos(3, 4, 2)) instanceof com.simibubi.create.content.logistics.depot.DepotBlockEntity depot) {
            var held = depot.saveWithoutMetadata(world.registryAccess()).getCompound("HeldItem");
            if (depot.getHeldItem().is(net.minecraft.world.item.Items.SAND) && held.contains("FanProcessingTime")) fanDepotStarted = true;
            if (fanDepotStarted && !depot.getHeldItem().is(net.minecraft.world.item.Items.SAND)) fanDepotFinished = true;
        }
        for (var be : world.getBlockEntities()) {
            if (world instanceof com.diliupengyun.differential_create.kinetics.PhysicsPreview preview && !preview.differentialcreate$physical(be.getBlockPos())) continue;
            var inventory = world.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null);
            if (inventory != null) for (int i = 0; i < inventory.getSlots(); i++) observe(inventory.getStackInSlot(i));
            if (be instanceof BasinBlockEntity basin) for (int i = 0; i < basin.getOutputInventory().getSlots(); i++) observe(basin.getOutputInventory().getStackInSlot(i));
            if (be instanceof BeltBlockEntity belt && belt.isController()) for (var item : belt.getInventory().getTransportedItems()) observe(item.stack);
        }
        for (var entity : world.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            new net.minecraft.world.phys.AABB(-100, -10, -100, 100, 100, 100))) observe(entity.getItem());
        for (var entry : com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(world).readings().entrySet()) {
            var values = entry.getValue();
            if (entry.getKey().startsWith("train")) { distance += Math.abs(values.get(1)); maximumEngine = Math.max(maximumEngine, values.get(2)); }
            else if (entry.getKey().startsWith("dock") && values.getFirst() == (double) com.diliupengyun.differential_create.kinetics.portable.DockingState.Phase.TRANSFER.ordinal()) {
                if (Math.abs(values.get(1)) > 1e-5 || values.get(2) > 1e-5) throw new AssertionError("Storage transfer opened before the mechanism stopped in " + scene.getId());
                transfer = true;
            }
        }
    }
    private void observe(ItemStack stack) { if (!stack.isEmpty()) seen.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); }
    private void pipeNetwork(PonderScene scene) {
        var world = scene.getWorld();
        var window = new net.minecraft.core.BlockPos(3, 1, 2);
        if (!(world.getBlockEntity(new net.minecraft.core.BlockPos(4, 1, 2))
            instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank)
            || !(world.getBlockEntity(new net.minecraft.core.BlockPos(0, 1, 2))
                instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity source)
            || !(world.getBlockEntity(window) instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity glass)) return;
        var pipe = glass.getBehaviour(com.simibubi.create.content.fluids.FluidTransportBehaviour.TYPE);
        if (pipe == null) return;
        var fluid = com.diliupengyun.differential_create.kinetics.FluidKinetics.state(pipe);
        double amount = tank.getTankInventory().getFluidAmount();
        double vented = com.diliupengyun.differential_create.kinetics.FluidKinetics.airAudit(world).outgoing();
        pipeStarted |= source.getTankInventory().getFluidAmount() > 0;
        if (!pipeStarted) {
            if (amount != 0 || fluid.volume() > .01)
                throw new AssertionError("The introductory empty pipe must stay dry, receiver=" + amount + ", pipe=" + fluid.volume());
            dryPipeTicks++;
            pipeAirBefore = vented;
        } else {
            pipeFront |= fluid.volume() > .1 && fluid.airVolume() > .1;
            pipeVented |= vented > pipeAirBefore + 50;
            pipeReceived |= amount >= 300;
            double total = source.getTankInventory().getFluidAmount() + amount
                + com.diliupengyun.differential_create.kinetics.FluidKinetics.ownedVolume(world);
            if (Math.abs(total - 6000) > .01)
                throw new AssertionError("Pipe filling created or lost water: " + total);
        }
    }
    void verify(PonderScene scene) {
        String id = scene.getId().toString();
        if (id.equals("differentialcreate:pump_network")
            && (dryPipeTicks < 40 || !pipeFront || !pipeVented || !pipeReceived))
            throw new AssertionError("Pipe lesson must show a dry pipe, a real water/air front, venting and filling: "
                + dryPipeTicks + "/" + pipeFront + "/" + pipeVented + "/" + pipeReceived);
        if (id.equals("create:fluid_pipe_interaction") && (!honeyExtracted || !honeyReceived))
            throw new AssertionError(id + " never actually extracted honey and delivered it to the receiving tank: " + honeyExtracted + "/" + honeyReceived);
        String[] expected = switch (id) {
            case "create:millstone" -> new String[]{"create:wheat_flour"};
            case "create:mechanical_mixer" -> new String[]{"minecraft:purple_dye"};
            case "create:mechanical_press_compacting" -> new String[]{"minecraft:copper_block", "minecraft:oak_wood"};
            case "create:crushing_wheels" -> new String[]{"create:crushed_raw_gold"};
            case "create:basin" -> new String[]{"minecraft:bricks", "minecraft:copper_block"};
            case "create:fan_processing" -> new String[]{"minecraft:gold_ingot"};
            case "create:mechanical_arm_filtering" -> new String[]{"minecraft:tnt"};
            case "create:item_drain" -> new String[]{"minecraft:bucket"};
            case "differentialcreate:structure_load", "create:mechanical_harvester" -> new String[]{"minecraft:wheat"};
            default -> new String[0];
        };
        for (var item : expected) if (!seen.contains(item)) throw new AssertionError(id + " never produced its actual recipe result " + item + "; observed=" + seen);
        if ((id.equals("create:train_controls") || id.equals("create:train_schedule") || id.equals("differentialcreate:train_driving")) && (distance < .01 || maximumEngine <= 0))
            throw new AssertionError(id + " never moved with a running physical engine; distance=" + distance + ", engine=" + maximumEngine);
        if ((id.equals("create:portable_storage_interface") || id.equals("create:portable_fluid_interface")) && !transfer) throw new AssertionError(id + " never completed actual stopped docking");
        if (id.equals("create:mechanical_arm") && !armTransfer) throw new AssertionError(id + " never transferred its actual copper input to the output depot");
        if (id.equals("create:fan_processing") && !fanDepotFinished) throw new AssertionError(id + " never completed actual depot washing (which may legitimately yield no clay)");
        if (id.equals("create:hose_pulley") && !hoseFilled) throw new AssertionError(id + " never placed actual source water from its supplied inventory");
        if (id.equals("create:hose_pulley_level") && (!hoseDrained || !hoseLowered || !hoseRefilled))
            throw new AssertionError(id + " did not physically drain and refill its pool: drained=" + hoseDrained + ", lowered=" + hoseLowered + ", refilled=" + hoseRefilled + "; " + hoseState(scene));
        if (id.equals("create:hose_pulley_infinite") && (!infiniteHose || !(scene.getWorld().getBlockEntity(new net.minecraft.core.BlockPos(4, 1, 1)) instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank)
            || tank.getTankInventory().isEmpty())) throw new AssertionError(id + " never extracted actual ocean water; " + hoseState(scene));
        if (id.equals("create:item_drain") && !drainTransferred) throw new AssertionError(id + " never pumped its actual emptied container fluid into the receiving tank");
        if (id.equals("create:mechanical_roller_fill") && (!rollerFilled || !rollerSloped))
            throw new AssertionError(id + " never actually placed straight and sloped fill: " + rollerFilled + "/" + rollerSloped);
        if (id.equals("create:mechanical_roller_pave") && (!rollerTuff || !rollerGrass))
            throw new AssertionError(id + " never actually paved both demonstrated materials: " + rollerTuff + "/" + rollerGrass);
    }
    private String hoseState(PonderScene scene) {
        var result = new StringBuilder(); var world = scene.getWorld();
        for (var be : world.getBlockEntities()) if (be instanceof com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity hose) {
            var handler = world.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, be.getBlockPos(), null);
            var root = ((com.diliupengyun.differential_create.mixin.HosePulleyFluidHandlerAccessor) handler).differentialcreate$rootPosition().get();
            result.append("rpm=").append(hose.getSpeed()).append(" offset=").append(hose.getInterpolatedOffset(1)).append(" root=").append(root)
                .append(" rootState=").append(world.getBlockState(root)).append(" data=").append(hose.saveWithoutMetadata(world.registryAccess()));
        }
        return result.toString();
    }
}
