package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.diliupengyun.differential_create.client.ponder.PonderSimulation;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import net.createmod.catnip.math.BlockFace;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/** Counterfactual checks: playback alone cannot prove that work or material was paid. */
final class PonderPhysicalAudit {
    static void verify() {
        source(false); source(true); honeySource(); shortWaterNetwork(); deployer(false); deployer(true);
        basin(false, false); basin(true, false); basin(false, true); basin(true, true); hopper();
        breaking(false); breaking(true); clockwork(false); clockwork(true); hoseMotion(false); hoseMotion(true); linearEndpoints(); roller(false); roller(true);
        pulleyStroke(false); pulleyStroke(true); gantryDisassembly();
        mountTransitions(); toolReplacement(); dockTransitions(false); dockTransitions(true); templateCommands(); isolatedCallbacks();
        depotPressing(4, false); depotPressing(1, true); depotPressing(4, true); depotPressing(64, true);
        DifferentialCreate.LOGGER.info("DC_PONDER_PHYSICAL_INPUTS: finite/renewable water, reserved stock, filter refusal, zero-drive work, paid recipes, native block loot, inventory transfer, tool wear and clock isolation passed");
    }
    private static PonderLevel world() { return new PonderLevel(BlockPos.ZERO, Minecraft.getInstance().level); }
    private static void depotPressing(int batch, boolean powered) {
        var world = world(); var pos = new BlockPos(0, 3, 0);
        world.setBlock(pos, AllBlocks.MECHANICAL_PRESS.getDefaultState(), 3);
        world.setBlock(pos.below(2), AllBlocks.DEPOT.getDefaultState(), 3);
        var press = (com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity) world.getBlockEntity(pos);
        var tray = (DepotBlockEntity) world.getBlockEntity(pos.below(2));
        press.initialize(); tray.initialize();
        var depot = tray.getBehaviour(DepotBehaviour.TYPE);
        depot.setCenteredHeldItem(new TransportedItemStack(new ItemStack(net.minecraft.world.item.Items.COPPER_INGOT, batch)));
        var physics = PonderKinetics.of(world); physics.useExplicitSources(); physics.boundary(world, pos, powered ? 64 : 0);
        int made = 0;
        for (int tick = 0; tick < (powered ? batch * 50 + 60 : 100); tick++) {
            world.tick(); PonderSimulation.of(world).finish(world);
            int ingots = 0, sheets = 0;
            for (int slot = 0; slot < depot.itemHandler.getSlots(); slot++) {
                var stack = depot.itemHandler.getStackInSlot(slot);
                if (stack.is(net.minecraft.world.item.Items.COPPER_INGOT)) ingots += stack.getCount();
                if (AllItems.COPPER_SHEET.isIn(stack)) {
                    sheets += stack.getCount();
                    require(slot == 0 || slot == 5, "products belong to the rim stack or the final centered sheet");
                }
                require(stack.getCount() <= stack.getMaxStackSize(), "rim presentation must respect stack limits");
            }
            require(ingots + sheets == batch, "arranging the depot rim must conserve every workpiece");
            require(sheets >= made && sheets <= made + 1, "one real press contact may make only one sheet");
            if (sheets > made) require(press.getPressingBehaviour().getRenderedHeadOffset(1) >= .99,
                "a visible new sheet must follow actual head contact");
            var center = depot.getHeldItemStack();
            int centered = AllItems.COPPER_SHEET.isIn(center) ? center.getCount() : 0;
            require(centered == (sheets == batch ? 1 : 0), "only the final product must stay where it was pressed");
            require(depot.itemHandler.getStackInSlot(5).getCount() == sheets - centered,
                "all earlier products must remain in the rim stack");
            if (sheets > made && (sheets == 2 || sheets == batch)) {
                var saved = tray.saveWithoutMetadata(world.registryAccess());
                tray.loadWithComponents(saved, world.registryAccess());
                require(saved.equals(tray.saveWithoutMetadata(world.registryAccess())), "rim stacks and final centered product survive a depot reload");
            }
            made = sheets;
            if (made == batch && !press.getPressingBehaviour().running) break;
        }
        require(made == (powered ? batch : 0), "depot recipe results must require powered travel");
        int collected = 0;
        for (int slot = 0; slot < depot.itemHandler.getSlots(); slot++) {
            var result = depot.itemHandler.extractItem(slot, Integer.MAX_VALUE, false);
            require(powered ? AllItems.COPPER_SHEET.isIn(result) || result.isEmpty()
                : result.is(net.minecraft.world.item.Items.COPPER_INGOT) || result.isEmpty(), "collection must keep the actual item type");
            collected += result.getCount();
        }
        require(collected == batch && depot.isEmpty(), "all rim stacks remain collectable without duplicates");
        DifferentialCreate.LOGGER.info("DC_PONDER_DEPOT_STACKS: batch={}, powered={}, contact, visible outputs, reload and collection passed", batch, powered);
    }
    private static void templateCommands() {
        var world = world(); var inlet = new BlockPos(0, 1, 0); var joining = inlet.east(2);
        var shaft = AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
        world.setBlock(inlet, shaft, 3); world.setBlock(joining, shaft, 3);
        var physics = PonderKinetics.of(world); physics.boundary(world, inlet, 32);
        var selection = net.createmod.ponder.foundation.SelectionImpl.of(new net.minecraft.world.level.levelgen.structure.BoundingBox(joining));
        physics.command(world, selection, old -> 0f);
        for (int i = 0; i < 20; i++) world.tick();
        world.setBlock(inlet.east(), shaft, 3);
        for (int i = 0; i < 20; i++) world.tick();
        var source = (com.simibubi.create.content.kinetics.base.KineticBlockEntity) world.getBlockEntity(inlet);
        require(Math.abs(source.getSpeed() - 32) < .1, "a joining part's old stopped initial condition must not stop the running input");
        physics.command(world, selection, old -> 16f);
        for (int i = 0; i < 20; i++) world.tick();
        require(Math.abs(source.getSpeed() - 16) < .1, "a new command on the connected running shaft must still reach its source");
    }
    private static net.createmod.ponder.foundation.element.WorldSectionElementImpl section(BlockPos first, BlockPos last) {
        return new net.createmod.ponder.foundation.element.WorldSectionElementImpl(net.createmod.ponder.foundation.SelectionImpl.of(
            net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(first, last)));
    }
    private static void hoseMotion(boolean powered) {
        var world = world(); var pos = new BlockPos(0, 5, 0);
        world.setBlock(pos, AllBlocks.HOSE_PULLEY.getDefaultState(), 3);
        world.setBlock(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState(), 3);
        var hose = (com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity) world.getBlockEntity(pos);
        var physics = PonderKinetics.of(world); physics.useExplicitSources(); physics.boundary(world, pos, powered ? 32 : 0);
        float previous = hose.getInterpolatedOffset(1);
        for (int i = 0; i < 70; i++) {
            world.tick(); PonderSimulation.of(world).finish(world);
            float displayed = hose.getInterpolatedOffset(1);
            if (hose.getSpeed() > .001 && previous < 1)
                require(displayed > previous, "a turning hose pulley must visibly lower its head from the first driven tick");
            previous = displayed;
        }
        require(powered ? hose.getInterpolatedOffset(1) > 1 : hose.getInterpolatedOffset(1) == 3 / 16f,
            "hose extension must follow preview ticks and real drive, independent of the paused server tick-rate modifier: powered=" + powered);
    }
    private static void mountTransitions() {
        var world = world(); var pos = new BlockPos(0, 1, 0);
        world.setBlock(pos, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP), 3);
        var be = (com.simibubi.create.content.kinetics.base.KineticBlockEntity) world.getBlockEntity(pos); be.initialize();
        var firstPos = pos.above(); var secondPos = pos.above().east(3);
        world.setBlock(firstPos, Blocks.OAK_PLANKS.defaultBlockState(), 3);
        world.setBlock(secondPos, Blocks.OAK_PLANKS.defaultBlockState(), 3);
        var first = section(firstPos, firstPos); var second = section(secondPos, secondPos);
        var simulation = PonderSimulation.of(world); var physics = PonderKinetics.of(world);
        physics.useExplicitSources(); physics.boundary(world, pos, 32);
        simulation.attach(first, be, true, 1, 1); double single = physics.attachmentState(pos).inertia();
        simulation.attach(second, be, true, 1, 1); world.tick(); simulation.finish(world);
        require(physics.attachmentState(pos).inertia() > single, "a second real mounted body must contribute inertia");
        second.setVisible(false); world.tick(); simulation.finish(world);
        require(Math.abs(physics.attachmentState(pos).inertia() - single) < 1e-12, "retiring a body must remove its mass from the next example");
        physics.setActorLoad(pos, 100); physics.setAttachmentLoad(pos, 200);
        first.setVisible(false); world.tick(); simulation.finish(world);
        var retired = physics.attachmentState(pos);
        require(!retired.active() && retired.actorLoad() == 0 && retired.load() == 0, "hidden mounted mass, cutting work and docking brake must all leave the active solve");
        first.setVisible(true); world.tick(); simulation.finish(world);
        require(physics.attachmentState(pos).active() && physics.attachmentState(pos).inertia() == single, "revealing the same body must restore only its own mass");
        first.erase(net.createmod.ponder.foundation.SelectionImpl.of(new net.minecraft.world.level.levelgen.structure.BoundingBox(firstPos)));
        world.tick(); simulation.finish(world);
        require(!physics.attachmentState(pos).active(), "an emptied display section cannot retain mounted mass");
        world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        world.setBlock(pos, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP), 3);
        var replacement = (com.simibubi.create.content.kinetics.base.KineticBlockEntity) world.getBlockEntity(pos); replacement.initialize();
        second.setVisible(true); simulation.attach(second, replacement, true, 1, 1); world.tick(); simulation.finish(world);
        require(!simulation.mounting().owners().containsKey(be) && simulation.mounting().owners().containsKey(replacement), "replacing an actuator at the same coordinates must retire the old identity");
    }
    private static void dockTransitions(boolean replaceStation) {
        var world = world(); var drive = new BlockPos(0, 0, 0); var endpoint = drive.above(); var station = endpoint.east(2);
        world.setBlock(drive, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP), 3);
        world.setBlock(endpoint, AllBlocks.PORTABLE_STORAGE_INTERFACE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST), 3);
        world.setBlock(station, AllBlocks.PORTABLE_STORAGE_INTERFACE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST), 3);
        var oldStation = (com.diliupengyun.differential_create.kinetics.portable.PortableEndpoint) world.getBlockEntity(station);
        var state = oldStation.differentialcreate$docking();
        var simulation = PonderSimulation.of(world); var physics = PonderKinetics.of(world); var body = section(endpoint, endpoint);
        var actuator = (com.simibubi.create.content.kinetics.base.KineticBlockEntity) world.getBlockEntity(drive); actuator.initialize();
        physics.useExplicitSources(); physics.boundary(world, drive, 0); simulation.attach(body, actuator, true, 1, 1);
        world.tick(); simulation.dock(world, body, station, endpoint, drive);
        for (int i = 0; i < 120 && state.phase != com.diliupengyun.differential_create.kinetics.portable.DockingState.Phase.TRANSFER; i++) { world.tick(); simulation.finish(world); }
        require(state.phase == com.diliupengyun.differential_create.kinetics.portable.DockingState.Phase.TRANSFER, "docking transition fixture must really open transfer first");
        if (replaceStation) {
            world.setBlock(station, Blocks.AIR.defaultBlockState(), 3);
            world.setBlock(station, AllBlocks.PORTABLE_STORAGE_INTERFACE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST), 3);
        } else body.setVisible(false);
        world.tick(); simulation.finish(world);
        require(state.phase == com.diliupengyun.differential_create.kinetics.portable.DockingState.Phase.IDLE
            && !oldStation.differentialcreate$nativePermission(), "retiring a displayed endpoint or replacing its station must revoke the old transfer session");
        if (replaceStation) {
            var next = ((com.diliupengyun.differential_create.kinetics.portable.PortableEndpoint) world.getBlockEntity(station)).differentialcreate$docking();
            for (int i = 0; i < 120 && next.phase != com.diliupengyun.differential_create.kinetics.portable.DockingState.Phase.TRANSFER; i++) { world.tick(); simulation.finish(world); }
            require(next.phase == com.diliupengyun.differential_create.kinetics.portable.DockingState.Phase.TRANSFER, "a fresh station at the same position must be able to establish its own new session");
        } else require(physics.attachmentState(drive).load() == 0, "a retired interface cannot leave its brake on the next scene");
    }
    private static void toolReplacement() {
        var world = world(); var drive = new BlockPos(0, 3, 0); var tool = drive.above(); var chest = tool.east();
        world.setBlock(drive, AllBlocks.GANTRY_SHAFT.getDefaultState().setValue(com.simibubi.create.content.kinetics.gantry.GantryShaftBlock.FACING, Direction.EAST), 3);
        world.setBlock(tool, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST), 3);
        world.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ((net.minecraft.world.level.block.entity.ChestBlockEntity) world.getBlockEntity(chest)).setItem(0, new ItemStack(Blocks.COARSE_DIRT, 8));
        var simulation = PonderSimulation.of(world); var physics = PonderKinetics.of(world);
        var be = (com.simibubi.create.content.kinetics.base.KineticBlockEntity) world.getBlockEntity(drive); be.initialize();
        physics.useExplicitSources(); physics.boundary(world, drive, 0);
        var body = section(tool, chest); simulation.attach(body, be, false, 0, 1); world.tick(); simulation.finish(world);
        require(simulation.mountedTool(tool), "the initial moving drill must have a real actor context");
        world.setBlock(tool, Blocks.AIR.defaultBlockState(), 3);
        world.setBlock(tool, AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.DOWN), 3);
        world.setBlock(chest, Blocks.AIR.defaultBlockState(), 3); world.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ((net.minecraft.world.level.block.entity.ChestBlockEntity) world.getBlockEntity(chest)).setItem(0, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 3));
        world.tick(); simulation.finish(world);
        require(!simulation.mountedTool(tool), "a replaced standalone machine cannot retain the old moving drill's actor state");
        var fresh = ((net.minecraft.world.level.block.entity.ChestBlockEntity) world.getBlockEntity(chest)).getItem(0);
        require(fresh.is(net.minecraft.world.item.Items.DIAMOND) && fresh.getCount() == 3, "an old mounted inventory must not overwrite a new container at the same coordinates");
        body.setVisible(false); world.tick(); simulation.finish(world);
        require(!simulation.mountedBlock(tool), "retired moving templates cannot reserve a position used by the next example");
    }
    private static void clockwork(boolean powered) {
        var world = world(); var pos = new BlockPos(0, 1, 0);
        world.setBlock(pos, AllBlocks.CLOCKWORK_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH), 3);
        world.setBlock(pos.north(), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        world.setBlock(pos.north(3), Blocks.STONE.defaultBlockState(), 3);
        var bearing = (com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity) world.getBlockEntity(pos); bearing.initialize();
        var hour = section(pos.north(), pos.north()); var minute = section(pos.north(3), pos.north(3));
        var physics = PonderKinetics.of(world); physics.useExplicitSources(); physics.boundary(world, pos, powered ? 32 : 0);
        var clock = PonderSimulation.of(world).clockwork();
        clock.request(hour, bearing, 2, 90); clock.request(minute, bearing, 2, 180);
        for (int i = 0; i < 300; i++) { world.tick(); PonderSimulation.of(world).finish(world); }
        var state = (com.diliupengyun.differential_create.mixin.ClockworkBearingAccessor) bearing;
        require(Math.abs(Math.IEEEremainder(state.differentialcreate$hourAngle() - (powered ? 90 : 0), 360)) < .01
            && Math.abs(Math.IEEEremainder(state.differentialcreate$minuteAngle() - (powered ? 180 : 0), 360)) < .01,
            "clock hands must independently approach native time targets only with actual drive: powered=" + powered
                + ", hour=" + state.differentialcreate$hourAngle() + ", minute=" + state.differentialcreate$minuteAngle());
        require(Math.abs(hour.getAnimatedRotation().z - state.differentialcreate$hourAngle()) < .01
            && Math.abs(minute.getAnimatedRotation().z - state.differentialcreate$minuteAngle()) < .01,
            "each visible hand must follow its own native controller rather than the other hand or continuous input");
    }
    private static void linearEndpoints() {
        var world = world(); var pos = new BlockPos(0, 1, 0);
        world.setBlock(pos, AllBlocks.GANTRY_SHAFT.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.gantry.GantryShaftBlock.FACING, Direction.EAST), 3);
        world.setBlock(pos.east(), world.getBlockState(pos), 3);
        var bodyPos = pos.above(); world.setBlock(bodyPos, AllBlocks.GANTRY_CARRIAGE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP), 3);
        var moving = section(bodyPos, bodyPos);
        var shaft = (com.simibubi.create.content.kinetics.base.KineticBlockEntity) world.getBlockEntity(pos); shaft.initialize();
        var physics = PonderKinetics.of(world); physics.useExplicitSources(); physics.boundary(world, pos, -32);
        var simulation = PonderSimulation.of(world); simulation.attach(moving, shaft, false, 0, 1);
        var extraPos = bodyPos.north(); world.setBlock(extraPos, Blocks.OAK_PLANKS.defaultBlockState(), 3);
        var extra = section(extraPos, extraPos);
        require(simulation.adjacentActuator(world, extra, 0) == shaft, "a separately revealed attached tool must find the carriage's actual actuator");
        simulation.attach(extra, shaft, false, 0, 1);
        for (int i = 0; i < 100; i++) { world.tick(); simulation.finish(world); }
        require(Math.abs(moving.getAnimatedOffset().x - 1) < 1e-6, "gantry must stop at the last physical shaft instead of sliding out of its track");
        require(extra.getAnimatedOffset().distanceToSqr(moving.getAnimatedOffset()) < 1e-12, "separately revealed attachments must stop at the same actual endpoint");
        var stopped = moving.getAnimatedOffset();
        world.setBlock(pos, world.getBlockState(pos).setValue(com.simibubi.create.content.kinetics.gantry.GantryShaftBlock.POWERED, true), 3);
        physics.boundary(world, pos, 32);
        for (int i = 0; i < 30; i++) { world.tick(); simulation.finish(world); }
        require(moving.getAnimatedOffset().distanceToSqr(stopped) < 1e-12, "powered gantry shafts must stop carriage translation even while their shaft turns");
        world.setBlock(pos, world.getBlockState(pos).setValue(com.simibubi.create.content.kinetics.gantry.GantryShaftBlock.POWERED, false), 3);
        for (int i = 0; i < 100; i++) { world.tick(); simulation.finish(world); }
        require(Math.abs(moving.getAnimatedOffset().x) < 1e-6, "reversal must leave the endpoint immediately without unwinding fictitious excess travel");
        require(extra.getAnimatedOffset().distanceToSqr(moving.getAnimatedOffset()) < 1e-12, "attached tools must reverse with their carriage");
    }
    private static void pulleyStroke(boolean powered) {
        var world = world(); var pos = new BlockPos(0, 4, 0);
        world.setBlock(pos, AllBlocks.ROPE_PULLEY.getDefaultState(), 3);
        world.setBlock(pos.below(), Blocks.GLASS.defaultBlockState(), 3);
        world.setBlock(pos.below(3), Blocks.BLUE_ORCHID.defaultBlockState(), 3);
        world.setBlock(pos.below(4), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        var pulley = (com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity) world.getBlockEntity(pos); pulley.initialize();
        var body = section(pos.below(), pos.below());
        var simulation = PonderSimulation.of(world); var physics = PonderKinetics.of(world);
        physics.useExplicitSources(); physics.boundary(world, pos, powered ? 32 : 0);
        simulation.pulley(pulley).command(2, false);
        simulation.attach(body, pulley, false, 1, -1);
        for (int i = 0; i < 200; i++) { world.tick(); simulation.finish(world); }
        require(Math.abs(pulley.offset - 2) < 1e-6 && Math.abs(body.getAnimatedOffset().y + pulley.offset) < 1e-6,
            "rope and load descend together under motor or gravity and share the endpoint: powered=" + powered
                + ", rope=" + pulley.offset + ", load=" + body.getAnimatedOffset().y);
        simulation.pulley(pulley).command(-2, false); physics.boundary(world, pos, powered ? -32 : 0);
        for (int i = 0; i < 200; i++) { world.tick(); simulation.finish(world); }
        require(Math.abs(pulley.offset - (powered ? 0 : 2)) < 1e-6 && Math.abs(body.getAnimatedOffset().y + pulley.offset) < 1e-6,
            "powered reversal lifts immediately; an unpowered command cannot lift against gravity: powered=" + powered + ", rope=" + pulley.offset);
        body.setVisible(false); world.tick(); simulation.finish(world);
        simulation.pulley(pulley).command(2, false); physics.boundary(world, pos, powered ? 32 : 0);
        for (int i = 0; i < 200; i++) { world.tick(); simulation.finish(world); }
        require(powered ? Math.abs(pulley.offset - 2) < 1e-6 : pulley.offset >= 2 && pulley.offset <= 4,
            "an unloaded rope must return to its driving shaft after the old load disappears");
    }
    private static void gantryDisassembly() {
        var world = world(); var pos = new BlockPos(0, 1, 0);
        var state = AllBlocks.GANTRY_SHAFT.getDefaultState().setValue(com.simibubi.create.content.kinetics.gantry.GantryShaftBlock.FACING, Direction.EAST);
        for (int x = 0; x <= 3; x++) world.setBlock(pos.east(x), state, 3);
        world.setBlock(pos.above(), AllBlocks.GANTRY_CARRIAGE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP), 3);
        world.setBlock(pos.above().north(), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        var carriage = section(pos.above(), pos.above()); var load = section(pos.above().north(), pos.above().north());
        var shaft = (com.simibubi.create.content.kinetics.base.KineticBlockEntity) world.getBlockEntity(pos); shaft.initialize();
        var physics = PonderKinetics.of(world); var simulation = PonderSimulation.of(world);
        physics.useExplicitSources(); physics.boundary(world, pos, -32);
        simulation.attach(carriage, shaft, false, 0, 1); simulation.attach(load, shaft, false, 0, 1);
        for (int i = 0; i < 100 && carriage.getAnimatedOffset().x < .65; i++) { world.tick(); simulation.finish(world); }
        require(carriage.getAnimatedOffset().x > .6 && carriage.getAnimatedOffset().x < .9, "fixture must stop between grid points");
        world.setBlock(pos, state.setValue(com.simibubi.create.content.kinetics.gantry.GantryShaftBlock.POWERED, true), 3);
        world.tick(); simulation.finish(world);
        require(carriage.getAnimatedOffset().x == 1 && load.getAnimatedOffset().x == 1
            && !simulation.mountedBlock(pos.above()) && !simulation.mounting().owners().containsKey(shaft),
            "redstone must settle all parts on the same integer cell and release mounted mass and actors");
        physics.boundary(world, pos, 32);
        for (int i = 0; i < 30; i++) { world.tick(); simulation.finish(world); }
        require(carriage.getAnimatedOffset().x == 1, "an unbound carriage must stay on its settled block");
        world.setBlock(pos, state, 3);
        for (int i = 0; i < 100; i++) { world.tick(); simulation.finish(world); }
        require(Math.abs(carriage.getAnimatedOffset().x) < 1e-6 && Math.abs(load.getAnimatedOffset().x) < 1e-6,
            "reassembly must restart from the settled cell and respect the original rail endpoint");
    }
    private static void roller(boolean powered) {
        var world = world(); var rollerPos = new BlockPos(0, 2, 0); var bogeyPos = rollerPos.east(); var chestPos = bogeyPos.above();
        for (int x = -6; x <= 2; x++) for (int z = -1; z <= 1; z++) world.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
        world.setBlock(rollerPos, AllBlocks.MECHANICAL_ROLLER.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST), 3);
        world.setBlock(bogeyPos, AllBlocks.SMALL_BOGEY.getDefaultState().setValue(com.simibubi.create.content.trains.bogey.StandardBogeyBlock.AXIS, Direction.Axis.X), 3);
        world.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        var roller = (com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity) world.getBlockEntity(rollerPos); roller.initialize();
        roller.getBehaviour(com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour.TYPE).setFilter(new ItemStack(Blocks.COARSE_DIRT));
        ((net.minecraft.world.level.block.entity.ChestBlockEntity) world.getBlockEntity(chestPos)).setItem(0, new ItemStack(Blocks.COARSE_DIRT, 8));
        var simulation = PonderSimulation.of(world); world.tick();
        var body = section(rollerPos, chestPos); simulation.driveTrain(world, body, -4, 400);
        if (!powered) simulation.train(world, body, 0, false, 400);
        int maximum = 0;
        for (int i = 0; i < 400; i++) {
            world.tick(); simulation.finish(world);
            int placed = 0;
            for (int x = -6; x <= 2; x++) for (int z = -1; z <= 1; z++)
                if (world.getBlockState(new BlockPos(x, 0, z)).is(Blocks.COARSE_DIRT)) {
                    require(z == 0, "roller contact must stay on the displayed row, without a half-block lateral shift"); placed++;
                }
            maximum = Math.max(maximum, placed);
            require(maximum <= 8, "finite roller inventory must not be replenished during work");
            require(AllBlocks.MECHANICAL_ROLLER.has(world.getBlockState(rollerPos)), "a moving roller must never break its own render template");
        }
        require(powered ? maximum > 0 : maximum == 0, "roller paving needs real paid travel; powered=" + powered + ", placed=" + maximum);
        require(powered ? Math.abs(body.getAnimatedOffset().x) > .1 : body.getAnimatedOffset().lengthSqr() < 1e-12, "zero towing input cannot move the roller train");
        world.setBlock(bogeyPos, Blocks.AIR.defaultBlockState(), 3);
        world.setBlock(bogeyPos, AllBlocks.SMALL_BOGEY.getDefaultState().setValue(com.simibubi.create.content.trains.bogey.StandardBogeyBlock.AXIS, Direction.Axis.X), 3);
        var stopped = body.getAnimatedOffset(); world.tick(); simulation.finish(world);
        require(!simulation.trainBogey(bogeyPos) && body.getAnimatedOffset().distanceToSqr(stopped) < 1e-12,
            "a replacement bogey must not inherit an old train's movement, inventory mirror or wheel animation");
    }
    private static void source(boolean renewable) {
        var world = world(); var water = new BlockPos(0, 1, 0);
        for (int x = -1; x <= 1; x++) {
            world.setBlock(water.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
            if (x == 0 || renewable) world.setBlock(water.offset(x, 0, 0), Blocks.WATER.defaultBlockState(), 3);
        }
        var end = new OpenEndedPipe(new BlockFace(water.above(), Direction.DOWN));
        end.manageSource(world, null);
        var cap = (OpenPipeDischarge.Placement) end.provideHandler().getCapability();
        require(cap.differentialcreate$worldTransferSize() == (renewable ? 1 : 1000), "world source must retain its actual renewal rule");
        require(cap.differentialcreate$takeWorld(new FluidStack(Fluids.LAVA, 1), 1, 1000).isEmpty(), "rejected fluid cannot consume the world source");
        var first = cap.differentialcreate$takeWorld(new FluidStack(Fluids.WATER, 1), 1, 100);
        if (renewable) {
            require(first.getAmount() == 1, "an infinite source must feed a real short receiving volume");
            for (int i = 0; i < 200; i++) require(cap.differentialcreate$takeWorld(new FluidStack(Fluids.WATER, 1), 1, 25).getAmount() == 1, "renewable source must remain usable");
            require(world.getFluidState(water).isSource() && end.provideHandler().getCapability().getFluidInTank(0).isEmpty(), "infinite supply must not invent cached finite stock");
        } else {
            require(first.isEmpty() && world.getFluidState(water).isSource(), "short receiving space must not remove a finite source");
            var taken = cap.differentialcreate$takeWorld(new FluidStack(Fluids.WATER, 1), 250, 1000);
            require(taken.getAmount() == 250 && end.provideHandler().getCapability().getFluidInTank(0).getAmount() == 750,
                "finite extraction and reserved remainder must total one actual source block");
            require(!world.getFluidState(water).isSource(), "finite extraction must change its source block");
        }
        require(!FluidKinetics.levels().contains(world), "preview cannot enter the server fluid clock");
    }
    private static void honeySource() {
        var world = world(); var hive = new BlockPos(0, 1, 0);
        world.setBlock(hive, Blocks.BEE_NEST.defaultBlockState().setValue(net.minecraft.world.level.block.BeehiveBlock.HONEY_LEVEL, 5), 3);
        var end = new OpenEndedPipe(new BlockFace(hive.south(), Direction.NORTH)); end.manageSource(world, null);
        var handler = end.provideHandler().getCapability(); var intake = (OpenPipeDischarge.Placement) handler;
        var honey = new FluidStack(com.simibubi.create.AllFluids.HONEY.get().getSource(), 1);
        require(intake.differentialcreate$worldFluid().getAmount() == 250
            && com.simibubi.create.AllFluids.HONEY.is(intake.differentialcreate$worldFluid().getFluid()), "a full hive offers exactly 250 mB of actual honey");
        require(intake.differentialcreate$takeWorld(new FluidStack(Fluids.WATER, 1), 1, 1000).isEmpty(), "a water filter cannot extract or relabel hive honey");
        require(intake.differentialcreate$takeWorld(honey, 1, 249).isEmpty()
            && world.getBlockState(hive).getValue(net.minecraft.world.level.block.BeehiveBlock.HONEY_LEVEL) == 5, "short receiving space cannot empty the indivisible hive");
        var first = intake.differentialcreate$takeWorld(honey, 1, 250);
        require(first.getAmount() == 1 && com.simibubi.create.AllFluids.HONEY.is(first.getFluid())
            && world.getBlockState(hive).getValue(net.minecraft.world.level.block.BeehiveBlock.HONEY_LEVEL) == 0, "accepted honey extraction must really deplete the hive");
        var rest = handler.drain(honey.copyWithAmount(1000), net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        require(rest.getAmount() == 249 && com.simibubi.create.AllFluids.HONEY.is(rest.getFluid()) && intake.differentialcreate$worldFluid().isEmpty(),
            "the original 250 mB must be conserved without water substitution or refilling an empty hive");
    }
    private static void deployer(boolean supplied) {
        var world = world(); var handPos = new BlockPos(0, 3, 0); var depotPos = handPos.below(2);
        world.setBlock(handPos, AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.DOWN), 3);
        world.setBlock(depotPos, AllBlocks.DEPOT.getDefaultState(), 3);
        var hand = (DeployerBlockEntity) world.getBlockEntity(handPos);
        var depot = (DepotBlockEntity) world.getBlockEntity(depotPos);
        hand.initialize(); depot.initialize();
        var data = hand.saveWithoutMetadata(world.registryAccess());
        data.put("HeldItem", AllItems.SAND_PAPER.asStack().save(world.registryAccess())); hand.loadWithComponents(data, world.registryAccess());
        depot.getBehaviour(DepotBehaviour.TYPE).setCenteredHeldItem(new TransportedItemStack(AllItems.ROSE_QUARTZ.asStack()));
        var physics = PonderKinetics.of(world); physics.useExplicitSources(); physics.boundary(world, handPos, supplied ? 64 : 0);
        double travel = 0; boolean product = false;
        for (int i = 0; i < 180; i++) {
            world.tick(); travel += physics.workTravel(handPos);
            PonderSimulation.of(world).finish(world);
            boolean[] result = {false};
            depot.getBehaviour(TransportedItemStackHandlerBehaviour.TYPE).handleProcessingOnAllItems(item -> {
                result[0] |= AllItems.POLISHED_ROSE_QUARTZ.isIn(item.stack); return TransportedResult.doNothing();
            });
            product |= result[0];
            if (result[0]) require(travel >= 500 - 1e-6, "recipe cannot precede paid hand contact");
        }
        require(product == supplied, "only a powered hand may produce polished quartz; powered=" + supplied
            + ", travel=" + travel + ", hand=" + hand.saveWithoutMetadata(world.registryAccess())
            + ", depot=" + depot.saveWithoutMetadata(world.registryAccess()));
        var tool = ItemStack.parseOptional(world.registryAccess(), hand.saveWithoutMetadata(world.registryAccess()).getCompound("HeldItem"));
        require(!tool.isEmpty() && tool.getDamageValue() == (supplied ? 1 : 0), "actual processing must pay exactly one tool use");
        require(PonderSimulation.of(world).time() == 180, "preview owns exactly one clock step per tick");
    }
    private static void shortWaterNetwork() {
        var world = world(); var water = new BlockPos(0, 1, 0); var pump = water.above(2); var sink = pump.above().east();
        for (int x = -1; x <= 1; x++) {
            world.setBlock(water.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
            world.setBlock(water.offset(x, 0, 0), Blocks.WATER.defaultBlockState(), 3);
        }
        world.setBlock(water.above(), AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y), 3);
        world.setBlock(pump, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP), 3);
        var elbow = AllBlocks.ENCASED_FLUID_PIPE.getDefaultState();
        for (var face : Direction.values()) elbow = elbow.setValue(com.simibubi.create.content.fluids.pipes.FluidPipeBlock.PROPERTY_BY_DIRECTION.get(face), face == Direction.DOWN || face == Direction.EAST);
        world.setBlock(pump.above(), elbow, 3);
        world.setBlock(sink, AllBlocks.SPOUT.getDefaultState(), 3);
        for (var be : world.getBlockEntities()) if (be instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity smart) smart.initialize();
        var cap = world.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, sink, Direction.WEST);
        require(cap != null && cap.fill(new FluidStack(Fluids.WATER, 950), net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE) == 950,
            "short receiver has only fifty mB of genuine free volume");
        var physics = PonderKinetics.of(world); physics.useExplicitSources(); physics.boundary(world, pump, 64);
        for (int i = 0; i < 250; i++) world.tick();
        require(cap.getFluidInTank(0).getAmount() > 950, "a real short network must draw from renewable water without whole-bucket room");
        require(cap.getFluidInTank(0).getAmount() <= 1000 && world.getFluidState(water).isSource(), "actual receiver capacity and source regeneration remain valid");
        require(!FluidKinetics.levels().contains(world), "a registered preview pipe cannot enter the service clock");
    }
    private static void basin(boolean supplied, boolean mixing) {
        var world = world(); var basinPos = new BlockPos(0, 1, 0); var machinePos = basinPos.above(2);
        world.setBlock(basinPos, AllBlocks.BASIN.getDefaultState(), 3);
        world.setBlock(machinePos, (mixing ? AllBlocks.MECHANICAL_MIXER : AllBlocks.MECHANICAL_PRESS).getDefaultState(), 3);
        var basin = (com.simibubi.create.content.processing.basin.BasinBlockEntity) world.getBlockEntity(basinPos);
        var machine = (com.simibubi.create.content.kinetics.base.KineticBlockEntity) world.getBlockEntity(machinePos);
        basin.initialize(); ((com.simibubi.create.foundation.blockEntity.SmartBlockEntity) machine).initialize();
        if (mixing) {
            basin.getInputInventory().insertItem(0, new ItemStack(net.minecraft.world.item.Items.RED_DYE), false);
            basin.getInputInventory().insertItem(1, new ItemStack(net.minecraft.world.item.Items.BLUE_DYE), false);
        } else basin.getInputInventory().insertItem(0, new ItemStack(net.minecraft.world.item.Items.COPPER_INGOT, 9), false);
        var physics = PonderKinetics.of(world); physics.useExplicitSources(); physics.boundary(world, machinePos, supplied ? 64 : 0);
        int output = 0;
        for (int i = 0; i < 320; i++) {
            world.tick();
            PonderSimulation.of(world).finish(world);
            output = 0;
            for (int slot = 0; slot < basin.getOutputInventory().getSlots(); slot++) {
                var stack = basin.getOutputInventory().getStackInSlot(slot);
                if (stack.is(mixing ? net.minecraft.world.item.Items.PURPLE_DYE : net.minecraft.world.item.Items.COPPER_BLOCK)) output += stack.getCount();
            }
        }
        require(supplied ? output == (mixing ? 2 : 1) : output == 0, "basin must run its actual recipe only with power: mixer=" + mixing + ", powered=" + supplied + ", data=" + basin.saveWithoutMetadata(world.registryAccess()));
        int left = 0;
        for (int slot = 0; slot < basin.getInputInventory().getSlots(); slot++) left += basin.getInputInventory().getStackInSlot(slot).getCount();
        require(left == (supplied ? 0 : mixing ? 2 : 9), "basin must retain or consume its exact real input stock");
    }
    private static void hopper() {
        var world = world(); var hopperPos = new BlockPos(0, 2, 0); var chestPos = hopperPos.below();
        world.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState(), 3); world.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        var hopper = (net.minecraft.world.level.block.entity.HopperBlockEntity) world.getBlockEntity(hopperPos);
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) world.getBlockEntity(chestPos);
        hopper.setItem(0, new ItemStack(net.minecraft.world.item.Items.COPPER_INGOT, 2));
        for (int i = 0; i < 24; i++) { world.tick(); PonderSimulation.of(world).finish(world); }
        int total = 0; for (int i = 0; i < chest.getContainerSize(); i++) total += chest.getItem(i).getCount();
        require(hopper.isEmpty() && total == 2, "preview hopper must transfer its actual stock to the real destination");
    }
    private static void isolatedCallbacks() {
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        var completed = new java.util.concurrent.atomic.AtomicInteger();
        Runnable advance = () -> {
            var owner = Thread.currentThread();
            try {
                MachineWork.batch(() -> {
                    try { barrier.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                    MachineWork.event(() -> { require(Thread.currentThread() == owner, "machine event crossed between preview and server clocks"); completed.incrementAndGet(); });
                });
            } catch (Throwable failure) { errors.add(failure); }
        };
        var first = new Thread(advance, "preview-work-audit"); var second = new Thread(advance, "server-work-audit");
        first.start(); second.start();
        try { first.join(6000); second.join(6000); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        require(errors.isEmpty() && completed.get() == 2, "simultaneous preview/server machine callbacks must remain independent: " + errors);
    }
    private static void breaking(boolean powered) {
        var world = world(); var pos = new BlockPos(0, 2, 0); var target = pos.east();
        world.setBlock(pos, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST), 3);
        world.setBlock(target, Blocks.DIRT.defaultBlockState(), 3);
        world.setBlock(target.below(2), Blocks.STONE.defaultBlockState(), 3);
        var drill = (com.simibubi.create.content.kinetics.drill.DrillBlockEntity) world.getBlockEntity(pos); drill.initialize();
        var physics = PonderKinetics.of(world); physics.useExplicitSources(); physics.boundary(world, pos, powered ? 32 : 0);
        for (int tick = 0; tick < 100; tick++) { world.tick(); PonderSimulation.of(world).finish(world); }
        int drops = world.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            new net.minecraft.world.phys.AABB(-5, -100, -5, 5, 10, 5)).stream().filter(item -> item.getItem().is(net.minecraft.world.item.Items.DIRT)).mapToInt(item -> item.getItem().getCount()).sum();
        require(world.getBlockState(target).isAir() == powered && drops == (powered ? 1 : 0),
            "drill must pay actual work before breaking and obtain the native loot result: powered=" + powered + ", drops=" + drops
                + ", target=" + world.getBlockState(target) + ", data=" + drill.saveWithoutMetadata(world.registryAccess()));
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
