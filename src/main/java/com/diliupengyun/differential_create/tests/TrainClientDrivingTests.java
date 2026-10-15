package com.diliupengyun.differential_create.tests;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ClientTrainKinetics;
import com.diliupengyun.differential_create.client.TrainControlsClient;
import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.trains.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.actors.trainControls.*;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.*;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.graph.TrackNodeLocation.DiscoveredLocation;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/** Real local driver, key mappings, native input packets and server-to-HUD gear updates. */
public final class TrainClientDrivingTests {
    private static final BlockPos WHEEL = new BlockPos(4, -58, -60), CAB = WHEEL.east(2), LEVER = WHEEL.east();
    private static final BlockPos WATER = WHEEL.south(2), OTHER_WATER = WHEEL.south(4);
    private static final int R = TrainMechanics.REVERSE_GEAR;
    private static final int[] SHIFT_STEPS = {-1, -1, -1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1};
    private static final int[] SHIFT_GEARS = {0, R, R, 0, 1, 2, 3, 4, 5, 5, 4, 3, 2, 1, 0, R};
    private record Fixture(UUID train, int entity) {}
    private static CompletableFuture<Fixture> assembly;
    private static CompletableFuture<?> work;
    private static CompletableFuture<Boolean> verification;
    private static Fixture fixture;
    private static int stage, age, shift, scenario, stationScenario;
    private static GlobalStation frontStation, backStation;
    private static UUID stationTarget;
    private static long driveTick;
    private static boolean controlsChecked;
    private TrainClientDrivingTests() {}

    public static boolean tick(Minecraft mc) {
        if (stage == 9) return true;
        if (++age > 1800) throw new AssertionError("Driver client operation timed out: stage=" + stage + " scenario=" + scenario);
        switch (stage) {
            case 0 -> {
                mc.options.hideGui = false;
                assembly = mc.getSingleplayerServer().submit(() -> {
                    var level = mc.getSingleplayerServer().overworld(); var player = level.getServer().getPlayerList().getPlayers().getFirst();
                    for (int x = 0; x <= 2; x++) {
                        level.setBlockAndUpdate(WHEEL.east(x).below(), Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(WHEEL.east(x), x == 0 ? AllBlocks.SMALL_BOGEY.getDefaultState().setValue(AbstractBogeyBlock.AXIS, Direction.Axis.Z)
                            : x == 1 ? ModContent.GEAR_LEVER.getDefaultState() : AllBlocks.TRAIN_CONTROLS.getDefaultState().setValue(ControlsBlock.FACING, Direction.SOUTH));
                    }
                    for (int z = 1; z <= 4; z++) level.setBlockAndUpdate(WHEEL.south(z).below(), Blocks.STONE.defaultBlockState());
                    for (var pos : List.of(WATER, OTHER_WATER)) {
                        level.setBlockAndUpdate(pos, AllBlocks.FLUID_TANK.getDefaultState());
                        ((FluidTankBlockEntity) level.getBlockEntity(pos)).getTankInventory().setFluid(new FluidStack(Fluids.WATER, pos.equals(WATER) ? 4000 : 1000));
                    }
                    SteamBogey.install((com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity) level.getBlockEntity(WHEEL));
                    var chunk = new net.minecraft.world.level.ChunkPos(WHEEL);
                    for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setChunkForced(chunk.x + x, chunk.z + z, true);
                    level.addFreshEntity(new SuperGlueEntity(level, SuperGlueEntity.span(WHEEL.below(), CAB.south(4).above())));
                    var cc = new CarriageContraption(Direction.SOUTH);
                    try {
                        if (!cc.assemble(level, WHEEL)) throw new AssertionError("Native client driving fixture did not assemble");
                    } catch (com.simibubi.create.content.contraptions.AssemblyException error) { throw new AssertionError(error); }
                    cc.removeBlocksFromWorld(level, BlockPos.ZERO);
                    var graph = new TrackGraph(); var origin = Vec3.atCenterOf(WHEEL).subtract(0, 0, 100);
                    var a = new DiscoveredLocation(level, origin).withNormal(new Vec3(0, 1, 0)).materialA(TrackMaterial.ANDESITE);
                    var b = new DiscoveredLocation(level, origin.add(0, 0, 200)).withNormal(new Vec3(0, 1, 0)).materialA(TrackMaterial.ANDESITE);
                    graph.createNodeIfAbsent(a); graph.createNodeIfAbsent(b); graph.connectNodes(level, a, b, null);
                    var na = graph.locateNode(a); var nb = graph.locateNode(b); var edge = graph.getConnection(Couple.create(na, nb));
                    double spacing = AllBlocks.SMALL_BOGEY.get().getWheelPointSpacing();
                    var bogey = new CarriageBogey(AllBlocks.SMALL_BOGEY.get(), false, cc.getBlocks().get(BlockPos.ZERO).nbt().getCompound("BogeyData").copy(),
                        new TravellingPoint(na, nb, edge, 100 + spacing / 2, false), new TravellingPoint(na, nb, edge, 100 - spacing / 2, false));
                    var carriage = new Carriage(bogey, null, 0);
                    var train = new Train(UUID.randomUUID(), player.getUUID(), graph, List.of(carriage), List.of(), false, 0);
                    Create.RAILWAYS.trackNetworks.put(graph.id, graph); Create.RAILWAYS.addTrain(train);
                    carriage.setContraption(level, cc); carriage.manageEntities(level);
                    var entity = carriage.anyAvailableEntity(); if (entity == null) throw new AssertionError("Driver fixture entity is not loaded");
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                    var at = entity.toGlobalVector(Vec3.atCenterOf(CAB.subtract(WHEEL)), 0);
                    player.teleportTo(at.x, at.y, at.z); player.inventoryMenu.broadcastChanges();
                    Create.RAILWAYS.sync.sendFullGraphTo(graph, player);
                    CatnipServices.NETWORK.sendToClient(player, new AddTrainPacket(train));
                    return new Fixture(train.id, entity.getId());
                });
                next(1);
            }
            case 1 -> {
                if (!assembly.isDone()) return false;
                fixture = assembly.join(); var entity = clientEntity(mc);
                if (entity == null || entity.getCarriage() == null || entity.getContraption() == null
                    || !entity.validForRender || entity.firstPositionUpdate || ClientTrainKinetics.state(fixture.train) == null) return false;
                if (!TrainClientFluidTests.tick(mc, entity, WATER.subtract(WHEEL), OTHER_WATER.subtract(WHEEL))) return false;
                if (!TrainClientBogeyLightTests.tick(mc, entity)) return false;
                TrainClientSteamSoundTests.verify(mc, entity);
                if (!entity.handlePlayerInteraction(mc.player, CAB.subtract(WHEEL), Direction.UP, InteractionHand.MAIN_HAND))
                    throw new AssertionError("Client cannot enter native driving controls");
                work = mc.getSingleplayerServer().submit(() -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    var nativeEntity = (CarriageContraptionEntity) player.level().getEntity(fixture.entity);
                    if (!nativeEntity.handlePlayerInteraction(player, CAB.subtract(WHEEL), Direction.UP, InteractionHand.MAIN_HAND))
                        throw new AssertionError("Server cannot enter native driving controls");
                    return null;
                });
                next(2);
            }
            case 2 -> {
                if (!work.isDone()) return false;
                work.join();
                if (ControlsHandler.getContraption() != clientEntity(mc)) throw new AssertionError("Native driver context is missing");
                if (!controlsChecked) {
                    TrainClientControlsTests.verify(mc, clientEntity(mc), CAB.subtract(WHEEL));
                    controlsChecked = true;
                }
                if (shift == SHIFT_STEPS.length) { next(4); return false; }
                KeyMapping.click((SHIFT_STEPS[shift] < 0 ? TrainControlsClient.DOWN : TrainControlsClient.UP).getKey());
                TrainControlsClient.tick(); next(3);
            }
            case 3 -> {
                var state = ClientTrainKinetics.state(fixture.train); var entity = clientEntity(mc);
                if (age < 12 || state == null || state.gear() != SHIFT_GEARS[shift]
                    || entity.getContraption().getBlocks().get(LEVER.subtract(WHEEL)).state().getValue(GearLeverBlock.GEAR) != state.gear()) return false;
                shift++; next(2);
            }
            case 4 -> {
                if (scenario == 18) { next(10); return false; }
                send(mc, List.of(0, 1, 4), false);
                work = mc.getSingleplayerServer().submit(() -> {
                    var train = Create.RAILWAYS.trains.get(fixture.train); var entity = train.carriages.getFirst().anyAvailableEntity();
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    var pos = CAB.subtract(WHEEL); var block = entity.getContraption().getBlocks().get(pos);
                    Direction facing = entity.getInitialOrientation().getCounterClockWise();
                    entity.setBlock(pos, new StructureBlockInfo(pos, block.state().setValue(ControlsBlock.FACING, inverted() ? facing.getOpposite() : facing), block.nbt()));
                    TrainPhysics.shift(train, gear()); train.speed = scenario % 3 == 0 ? 0 : (inverted() ? -.05 : .05);
                    driveTick = player.level().getGameTime();
                    return null;
                });
                next(5);
            }
            case 5 -> {
                if (!work.isDone()) return false;
                work.join(); var state = ClientTrainKinetics.state(fixture.train);
                if (state == null || state.gear() != gear()) return false;
                send(mc, buttons(), true); next(6);
            }
            case 6 -> {
                if (age % 5 == 0) send(mc, buttons(), true);
                if (verification == null) {
                    verification = mc.getSingleplayerServer().submit(() -> {
                        var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                        if (player.level().getGameTime() - driveTick < 20) return false;
                        var train = Create.RAILWAYS.trains.get(fixture.train);
                        int expected = scenario % 3 == 0 && gear() != 0 ? (inverted() ? -1 : 1) * (gear() == R ? -1 : 1) : 0;
                        if (train.targetSpeed != expected || scenario % 3 != 0 && Math.abs(train.speed) > 1e-8)
                            throw new AssertionError("Native driver packet did not honor W/S/R: gear=" + gear() + " keys=" + buttons()
                                + " expected=" + expected + " target=" + train.targetSpeed + " speed=" + train.speed);
                        return true;
                    });
                    return false;
                }
                if (!verification.isDone()) return false;
                if (!verification.join()) { verification = null; return false; }
                scenario++; next(4);
            }
            case 7 -> {
                send(mc, List.of(0, 1, 4), false); ControlsHandler.stopControlling();
                work = mc.getSingleplayerServer().submit(() -> {
                    var train = Create.RAILWAYS.trains.get(fixture.train);
                    Create.RAILWAYS.removeTrain(train.id); Create.RAILWAYS.trackNetworks.remove(train.graph.id);
                    train.carriages.forEach(c -> c.forEachPresentEntity(e -> e.discard()));
                    return null;
                });
                next(8);
            }
            case 8 -> {
                if (!work.isDone()) return false;
                work.join();
                DifferentialCreate.LOGGER.info("DC_TRAIN_CLIENT_DRIVING_PASS: {} native key shifts, {} cab/gear/W/S and {} station-assist packet cases", shift, scenario, stationScenario);
                next(9);
            }
            case 10 -> {
                if (stationScenario == 16) { next(7); return false; }
                send(mc, List.of(0, 1, 4, 5), false);
                work = mc.getSingleplayerServer().submit(() -> {
                    var train = Create.RAILWAYS.trains.get(fixture.train); var entity = train.carriages.getFirst().anyAvailableEntity();
                    TrainPhysics.endStationAssist(train); train.navigation.cancelNavigation();
                    if (frontStation != null) train.graph.removePoint(EdgePointType.STATION, frontStation.id);
                    if (backStation != null) train.graph.removePoint(EdgePointType.STATION, backStation.id);
                    var pos = CAB.subtract(WHEEL); var block = entity.getContraption().getBlocks().get(pos);
                    var facing = entity.getInitialOrientation().getCounterClockWise();
                    entity.setBlock(pos, new StructureBlockInfo(pos, block.state().setValue(ControlsBlock.FACING,
                        stationInverted() ? facing.getOpposite() : facing), block.nbt()));
                    TrainPhysics.shift(train, stationGear());
                    int direction = (stationInverted() ? -1 : 1) * (stationGear() == R ? -1 : 1);
                    train.speed = direction * .02;
                    frontStation = station(train, 1); backStation = station(train, -1);
                    stationTarget = direction > 0 ? frontStation.id : backStation.id;
                    driveTick = mc.getSingleplayerServer().overworld().getGameTime();
                    return null;
                });
                next(11);
            }
            case 11 -> {
                if (!work.isDone()) return false;
                work.join(); var state = ClientTrainKinetics.state(fixture.train);
                if (state == null || state.gear() != stationGear()) return false;
                send(mc, List.of(4), true); next(12);
            }
            case 12 -> {
                if (age % 5 == 0) send(mc, List.of(4), true);
                if (age < 12) return false;
                if (verification == null) verification = mc.getSingleplayerServer().submit(() -> {
                    if (mc.getSingleplayerServer().overworld().getGameTime() - driveTick < 10) return false;
                    var train = Create.RAILWAYS.trains.get(fixture.train);
                    if (train.navigation.destination == null || !stationTarget.equals(train.navigation.destination.id)
                        || !TrainPhysics.state(train).stationAssist || TrainPhysics.state(train).gear != stationGear())
                        throw new AssertionError("Native space packet selected the wrong station or changed the manual gear: " + stationScenario);
                    return true;
                });
                if (!verification.isDone()) return false;
                if (!verification.join()) { verification = null; return false; }
                switch (stationScenario % 4) {
                    case 1 -> send(mc, List.of(4), false);
                    case 2 -> send(mc, List.of(1), true);
                    case 3 -> send(mc, List.of(5), true);
                    default -> { }
                }
                work = mc.getSingleplayerServer().submit(() -> { driveTick = mc.getSingleplayerServer().overworld().getGameTime(); return null; });
                next(13);
            }
            case 13 -> {
                if (stationScenario % 4 == 0 && age % 5 == 0) send(mc, List.of(4), true);
                if (age < 12) return false;
                if (!work.isDone()) return false;
                work.join();
                if (verification == null) verification = mc.getSingleplayerServer().submit(() -> {
                    if (mc.getSingleplayerServer().overworld().getGameTime() - driveTick < 10) return false;
                    var train = Create.RAILWAYS.trains.get(fixture.train); boolean held = stationScenario % 4 == 0;
                    if ((train.navigation.destination != null) != held || TrainPhysics.state(train).stationAssist != held
                        || stationScenario % 4 == 2 && train.speed != 0)
                        throw new AssertionError("Station assistance failed native hold/release/S/exit transition: " + stationScenario
                            + " destination=" + train.navigation.destination + " assist=" + TrainPhysics.state(train).stationAssist + " speed=" + train.speed);
                    return true;
                });
                if (!verification.isDone()) return false;
                if (!verification.join()) { verification = null; return false; }
                send(mc, List.of(0, 1, 4, 5), false);
                if (stationScenario % 4 == 3) { next(14); return false; }
                stationScenario++; next(10);
            }
            case 14 -> {
                if (ControlsHandler.getContraption() != null) return false;
                var entity = clientEntity(mc);
                if (!entity.handlePlayerInteraction(mc.player, CAB.subtract(WHEEL), Direction.UP, InteractionHand.MAIN_HAND))
                    throw new AssertionError("Client cannot re-enter controls after station-assist exit");
                work = mc.getSingleplayerServer().submit(() -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    var nativeEntity = (CarriageContraptionEntity) player.level().getEntity(fixture.entity);
                    if (!nativeEntity.handlePlayerInteraction(player, CAB.subtract(WHEEL), Direction.UP, InteractionHand.MAIN_HAND))
                        throw new AssertionError("Server cannot re-enter controls after station-assist exit");
                    return null;
                }); next(15);
            }
            case 15 -> {
                if (!work.isDone()) return false;
                work.join(); stationScenario++; next(10);
            }
            default -> throw new AssertionError("Unknown driver client stage");
        }
        return false;
    }
    private static CarriageContraptionEntity clientEntity(Minecraft mc) {
        return mc.level.getEntity(fixture.entity) instanceof CarriageContraptionEntity entity ? entity : null;
    }
    private static boolean inverted() { return scenario >= 9; }
    private static boolean stationInverted() { return stationScenario >= 8; }
    private static int stationGear() { return stationScenario / 4 % 2 == 0 ? 1 : R; }
    private static GlobalStation station(Train train, int direction) {
        var point = direction > 0 ? train.carriages.getFirst().getLeadingPoint() : train.carriages.getLast().getTrailingPoint();
        var station = new GlobalStation(); station.setType(EdgePointType.STATION); station.setId(UUID.randomUUID());
        station.name = "Client " + direction; station.blockEntityPos = WHEEL;
        station.blockEntityDimension = point.node1.getLocation().dimension;
        double position = point.position + direction * 10;
        station.setLocation(direction > 0 ? Couple.create(point.node1.getLocation(), point.node2.getLocation())
            : Couple.create(point.node2.getLocation(), point.node1.getLocation()), direction > 0 ? position : point.edge.getLength() - position);
        train.graph.addPoint(EdgePointType.STATION, station); return station;
    }
    private static int gear() { return new int[]{0, 1, R}[scenario / 3 % 3]; }
    private static List<Integer> buttons() { return scenario % 3 == 0 ? List.of(0) : scenario % 3 == 1 ? List.of(1) : List.of(0, 1); }
    private static void send(Minecraft mc, List<Integer> keys, boolean press) {
        CatnipServices.NETWORK.sendToServer(new ControlsInputPacket(keys, press, fixture.entity, CAB.subtract(WHEEL), false));
    }
    private static void next(int value) { stage = value; age = 0; verification = null; }
}
