package com.diliupengyun.differential_create.tests;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import com.diliupengyun.differential_create.trains.*;
import com.diliupengyun.differential_create.kinetics.RatioValueBehaviour;
import com.diliupengyun.differential_create.content.ModContent;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.minecart.TrainCargoManager;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlock;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.entity.*;
import com.simibubi.create.content.trains.graph.*;
import com.simibubi.create.content.trains.graph.TrackNodeLocation.DiscoveredLocation;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import com.simibubi.create.Create;
import net.createmod.catnip.data.Couple;
import net.minecraft.world.phys.Vec3;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainIntegrationTests {
    private static final BlockPos BOILER = new BlockPos(2, 4, 4), RESERVE = new BlockPos(9, 4, 8), ENGINE = new BlockPos(5, 4, 4);
    private static final BlockPos SHAFT = new BlockPos(5, 6, 4), CONTROLLER = new BlockPos(5, 5, 6), WHEEL = new BlockPos(6, 4, 5);
    private static final BlockPos CHEST = new BlockPos(9, 4, 4), LEVER = new BlockPos(8, 4, 5);
    private static void add(List<TestFunction> cases, String name, Consumer<GameTestHelper> test) {
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            cases.add(new TestFunction("trains", name, "differentialcreate_trains:kineticphysicstests.physics_test", 150, 0, true, test));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var result = new ArrayList<TestFunction>();
        add(result, "bogey_item_upgrade_hose_and_refund", TrainIntegrationTests::upgradeInteraction);
        add(result, "bogey_engine_break_returns_item", TrainIntegrationTests::bogeyDrop);
        add(result, "unlinked_bogey_no_traction", h -> withTrain(h, 4000, false, fixture -> {}, t -> {
            for(int i=0;i<20;i++){TrainPhysics.manual(t,1,false);t.tick(h.getLevel());}
            h.assertTrue(t.speed==0 && water(t)==4000 && items(t)==16 && TrainPhysics.state(t).failure.equals("no_steam_connection"),
                "Installed but unlinked bogey neither drives nor consumes water or fuel");h.succeed();
        }, false));
        add(result, "hose_boiler_recognition", TrainIntegrationTests::hose);
        add(result, "embedded_bogey_drive", h -> withTrain(h, 4000, false, t -> {
            var cs = TrainPhysics.carriage(t.carriages.getFirst()); h.assertTrue(cs.layout.drives.size() == 1 && cs.layout.drives.getFirst().motor().pos().equals(WHEEL), "Installed bogey drives directly without external shafts or a controller");
            h.assertTrue(cs.layout.boilers.size() == 1 && !cs.layout.levers.isEmpty(), "Boiler and remote gear lever captured"); h.succeed();
        }));
        add(result, "unloaded_real_consumption", h -> supply(h, 4000, false));
        add(result, "two_engines_shared_boiler", h -> supply(h, 4000, true));
        add(result, "no_water_no_traction", h -> withTrain(h, 0, false, t -> {
            for (int i = 0; i < 20; i++) { TrainPhysics.manual(t, 1, false); t.tick(h.getLevel()); }
            h.assertTrue(t.speed == 0 && TrainPhysics.state(t).failure.equals("no_water"), "No phantom steam from empty storage"); h.succeed();
        }));
        add(result, "uninstalled_bogey_no_free_power", h -> withTrain(h, 4000, false,
            fixture -> unpower(fixture), t -> {
                for (int i = 0; i < 20; i++) { TrainPhysics.manual(t, 1, false); t.tick(h.getLevel()); }
                h.assertTrue(t.speed == 0 && water(t) == 4000 && items(t) == 16,
                    "An ordinary bogey cannot create drive or consume train supplies"); h.succeed();
            }));
        add(result, "closed_valve_no_new_fuel", h -> withTrain(h, 4000, false, t -> {
            int initial = items(t); t.throttle = 0;
            for (int i = 0; i < 20; i++) { TrainPhysics.manual(t, 1, false); t.tick(h.getLevel()); }
            h.assertTrue(items(t) == initial && water(t) == 4000 && t.speed == 0, "Closed valve takes neither new fuel nor water"); h.succeed();
        }));
        add(result, "fractional_valve_conserves_water", h -> withTrain(h, 4000, false, t -> {
            t.throttle = .03;
            for (int i = 0; i < 20; i++) { TrainPhysics.manual(t, 1, false); t.tick(h.getLevel()); }
            double buffered = TrainPhysics.carriage(t.carriages.getFirst()).water.values().stream().mapToDouble(Double::doubleValue).sum();
            h.assertTrue(Math.abs(4000 - water(t) - buffered - 6) < 1e-8, "Fractional admission is paid from real water with conserved carry"); h.succeed();
        }));
        add(result, "neutral_clutch_and_shared_levers", h -> withTrain(h, 4000, false, t -> {
            TrainPhysics.shift(t, 0);
            for (int i = 0; i < 20; i++) { TrainPhysics.manual(t, 1, false); t.tick(h.getLevel()); }
            h.assertTrue(t.speed == 0 && water(t) == 4000, "N disconnects and closes admission");
            TrainPhysics.shift(t, 3); h.assertTrue(TrainPhysics.state(t).gear == 3, "Remote lever controls train state"); h.succeed();
        }));
        add(result, "carriage_and_burner_reload", h -> withTrain(h, 4000, false, t -> {
            for (int i = 0; i < 12; i++) { TrainPhysics.manual(t, 1, false); t.tick(h.getLevel()); }
            var before = TrainPhysics.carriage(t.carriages.getFirst());
            var after = CarriageSteamState.read(before.write(h.getLevel().registryAccess()), h.getLevel().registryAccess());
            h.assertTrue(after.layout.drives.size() == before.layout.drives.size() && after.engines.get(WHEEL).omega == before.engines.get(WHEEL).omega, "Transmission and rotor persisted");
            for (var entry : before.burners.entrySet()) h.assertTrue(after.burners.get(entry.getKey()).remaining == entry.getValue().remaining, "Remaining fuel persisted"); h.succeed();
        }));
        add(result, "native_fuel_container_return", h -> withTrain(h, 4000, false, t -> {
            var inventory = t.carriages.getFirst().storage.getFuelItems();
            for (int slot = 0; slot < inventory.getSlots(); slot++) inventory.extractItem(slot, 64, false);
            inventory.insertItem(0, new ItemStack(Items.LAVA_BUCKET), false);
            TrainPhysics.manual(t, 1, false); t.tick(h.getLevel());
            h.assertTrue(inventory.getStackInSlot(0).is(Items.BUCKET) && TrainPhysics.carriage(t.carriages.getFirst()).burners.values().iterator().next().remaining == 20000,
                "Native lava burn time and returned bucket retained"); h.succeed();
        }));
        add(result, "ratio_legacy_nbt_and_clipboard", TrainIntegrationTests::ratio);
        add(result, "native_fuel_blacklist", TrainIntegrationTests::blacklist);
        add(result, "native_assembly_and_rotated_disassembly", TrainIntegrationTests::assembly);
        add(result, "native_rail_coast_above_old_limit", h -> onRail(h, t -> {
            TrainPhysics.shift(t, 0); t.speed = 3;
            double initial = t.carriages.getFirst().getLeadingPoint().position;
            for (int i = 0; i < 20; i++) t.tick(h.getLevel());
            h.assertTrue(t.speed * 20 > 40 && t.speed < 3, "Native rail motion coasts above old limit with finite drag");
            h.assertTrue(t.carriages.getFirst().getLeadingPoint().position - initial > 40, "Native TravellingPoints use integrated distance"); h.succeed();
        }));
        add(result, "native_rail_power_brake_and_reload", h -> onRail(h, t -> {
            for (int i = 0; i < 50; i++) { TrainPhysics.manual(t, 1, false); t.tick(h.getLevel()); }
            h.assertTrue(t.speed > 0 && water(t) < 4000, "Real assembled carriage accelerates on native graph");
            TrainPhysics.shift(t, 3);
            var palette = new DimensionPalette(); var tag = t.write(palette, h.getLevel().registryAccess());
            var restored = Train.read(tag, h.getLevel().registryAccess(), Map.of(t.graph.id, t.graph), palette);
            h.assertTrue(TrainPhysics.state(restored).gear == 3 && restored.speed == t.speed && water(restored) == water(t) && items(restored) == items(t), "Native train reload retains gear, momentum and cargo");
            var before = TrainPhysics.carriage(t.carriages.getFirst()); var after = TrainPhysics.carriage(restored.carriages.getFirst());
            h.assertTrue(before.engines.values().iterator().next().omega == after.engines.values().iterator().next().omega, "Native train reload retains engine rotor");
            var old = tag.copy();
            for (var raw : old.getList("Carriages", net.minecraft.nbt.Tag.TAG_COMPOUND)) ((CompoundTag) raw).remove("DCSteam");
            var legacy = Train.read(old, h.getLevel().registryAccess(), Map.of(t.graph.id, t.graph), palette);
            h.assertTrue(TrainPhysics.carriage(legacy.carriages.getFirst()).layout.blocks == before.layout.blocks
                && TrainPhysics.carriage(legacy.carriages.getFirst()).layout.failure.equals("needs_assembly"), "Old trains retain captured structural mass and request reassembly");
            double speed = t.speed;
            TrainPhysics.manual(t, 0, true); t.tick(h.getLevel());
            h.assertTrue(t.speed >= 0 && t.speed < speed && speed - t.speed < .025, "Native rail brake changes speed by finite force"); h.succeed();
        }));
        add(result, "two_dimension_entities_share_consumption", h -> onRail(h, t -> {
            var c = t.carriages.getFirst(); c.manageEntities(h.getLevel());
            var original = c.anyAvailableEntity(); h.assertTrue(original != null, "Native source entity loaded");
            var otherLevel = h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
            var cc = (CarriageContraption) com.simibubi.create.content.contraptions.Contraption.fromNBT(otherLevel,
                original.getContraption().writeNBT(otherLevel.registryAccess(), false), false);
            var other = CarriageContraptionEntity.create(otherLevel, cc); other.setCarriage(c);
            c.getDimensional(otherLevel).entity = new java.lang.ref.WeakReference<>(other);
            int[] entities = {0}; c.forEachPresentEntity(e -> entities[0]++); h.assertTrue(entities[0] == 2, "Two native dimensional entity copies share one carriage");
            TrainPhysics.state(t).level = h.getLevel();
            for (int i = 0; i < 20; i++) { TrainPhysics.manual(t, 1, false); TrainPhysics.advance(t); }
            h.assertTrue(water(t) == 3800 && items(t) == 15, "Portal entity copies cannot double-consume water or fuel"); h.succeed();
        }));
        add(result, "native_station_finite_approach", h -> onRail(h, t -> {
            var lead = t.carriages.getFirst().getLeadingPoint(); var station = station(h, t, lead.position + 60);
            t.navigation.destination = station; t.navigation.distanceToDestination = 60; t.runtime.paused = true; t.speed = 1;
            for (int i = 0; i < 3000 && t.currentStation == null; i++) {
                double before = t.speed; t.tick(h.getLevel());
                h.assertTrue(Math.abs(t.speed - before) < .025, "Station approach uses bounded brake impulses");
            }
            h.assertTrue(station.id.equals(t.currentStation) && t.speed == 0, "Native arrival completes after finite approach: speed=" + t.speed + " remaining=" + t.navigation.distanceToDestination + " fault=" + TrainPhysics.state(t).failure); h.succeed();
        }));
        add(result, "native_station_overrun_keeps_momentum", h -> onRail(h, t -> {
            var lead = t.carriages.getFirst().getLeadingPoint(); var station = station(h, t, lead.position + 2);
            t.navigation.destination = station; t.navigation.distanceToDestination = 2; t.runtime.paused = true; t.speed = 1;
            for (int i = 0; i < 4; i++) {
                double before = t.speed; t.tick(h.getLevel());
                h.assertTrue(before - t.speed < .025, "Missed station cannot erase kinetic energy");
            }
            h.assertTrue(t.currentStation == null && lead.position > station.position && TrainPhysics.state(t).emergencyStop,
                "Too-short stopping distance overruns station and applies emergency brake");
            for (int i = 0; i < 80; i++) t.tick(h.getLevel());
            h.assertTrue(t.speed == 0, "Overrun stops under finite brake force"); h.succeed();
        }));
        add(result, "native_red_signal_finite_stop", h -> onRail(h, t -> {
            var lead = t.carriages.getFirst().getLeadingPoint(); var station = station(h, t, lead.position + 100);
            var signal = new SignalBoundary(); signal.setType(EdgePointType.SIGNAL); signal.setId(UUID.randomUUID());
            signal.setLocation(Couple.create(lead.node1.getLocation(), lead.node2.getLocation()), lead.position + 40);
            signal.blockEntities.getFirst().put(h.absolutePos(BlockPos.ZERO), true);
            var occupied = new SignalEdgeGroup(UUID.randomUUID()); occupied.reserved = signal;
            var opposite = new SignalEdgeGroup(UUID.randomUUID());
            Create.RAILWAYS.signalEdgeGroups.put(occupied.id, occupied); Create.RAILWAYS.signalEdgeGroups.put(opposite.id, opposite);
            signal.setGroup(true, occupied.id); signal.setGroup(false, opposite.id); t.graph.addPoint(EdgePointType.SIGNAL, signal);
            t.navigation.destination = station; t.navigation.distanceToDestination = 100; t.runtime.paused = true; t.speed = 1;
            try {
                for (int i = 0; i < 400; i++) {
                    double before = t.speed; t.tick(h.getLevel());
                    h.assertTrue(Math.abs(t.speed - before) < .025, "Signal approach uses bounded brake impulses");
                }
                h.assertTrue(t.navigation.waitingForSignal != null && lead.position <= signal.position + 1e-6 && t.speed < .01, "Occupied native signal stops the train before its boundary: speed=" + t.speed + " position=" + lead.position + " target=" + signal.position); h.succeed();
            } finally { Create.RAILWAYS.signalEdgeGroups.remove(occupied.id); Create.RAILWAYS.signalEdgeGroups.remove(opposite.id); }
        }));
        add(result, "unpowered_manual_keeps_station", h -> withPlatform(h, false, (t, block) -> {
            var parked = block.getStation(); double start = t.carriages.getFirst().getLeadingPoint().position;
            for (int i = 0; i < 20; i++) { TrainPhysics.manual(t, 1, false); t.tick(h.getLevel()); }
            h.assertTrue(t.speed == 0 && t.carriages.getFirst().getLeadingPoint().position == start
                && t.getCurrentStation() == parked && parked.getPresentTrain() == t,
                "A missing engine cannot turn throttle input into a phantom departure");
            h.assertTrue(block.tryDisassembleTrain(null) && AllBlocks.SMALL_BOGEY.has(h.getBlockState(WHEEL)),
                "A powerless train remains removable through the native station"); h.succeed();
        }));
        add(result, "unpowered_navigation_keeps_station", h -> withPlatform(h, false, (t, block) -> {
            var parked = block.getStation(); var target = station(h, t, t.carriages.getFirst().getLeadingPoint().position + 40);
            t.navigation.destination = target; t.navigation.distanceToDestination = 40; t.runtime.paused = true;
            for (int i = 0; i < 20; i++) t.tick(h.getLevel());
            h.assertTrue(t.speed == 0 && t.getCurrentStation() == parked && parked.getPresentTrain() == t
                && t.navigation.destination == target, "Navigation cannot lose the departure station before real movement"); h.succeed();
        }));
        add(result, "powered_departure_requires_real_movement", h -> withPlatform(h, true, (t, block) -> {
            var parked = block.getStation(); double start = t.carriages.getFirst().getLeadingPoint().position;
            t.throttle = 0;
            for (int i = 0; i < 10; i++) { TrainPhysics.manual(t, 1, false); t.tick(h.getLevel()); }
            h.assertTrue(t.getCurrentStation() == parked && t.carriages.getFirst().getLeadingPoint().position == start,
                "A closed valve preserves parked station state even with an engine installed");
            t.throttle = 1;
            for (int i = 0; i < 30; i++) { TrainPhysics.manual(t, 1, false); t.tick(h.getLevel()); }
            h.assertTrue(t.speed > 0 && t.carriages.getFirst().getLeadingPoint().position > start && t.currentStation == null
                && parked.getPresentTrain() == null, "Real finite-power departure releases the station reservation"); h.succeed();
        }));
        add(result, "lost_station_registration_native_disassembly", h -> withPlatform(h, false, (t, block) -> {
            t.leaveStation(); double start = t.carriages.getFirst().getLeadingPoint().position;
            h.assertTrue(block.tryDisassembleTrain(null), "Existing stranded train recovers its station before native disassembly");
            h.assertTrue(t.carriages.getFirst().getLeadingPoint().position == start && !Create.RAILWAYS.trains.containsKey(t.id)
                && AllBlocks.SMALL_BOGEY.has(h.getBlockState(WHEEL)), "Rescue restores blocks without powered travel or leaving a duplicate train"); h.succeed();
        }));
        add(result, "parked_station_recovery_syncs_gui", h -> withPlatform(h, false, (t, block) -> {
            t.leaveStation();
            h.assertTrue(TrainStationParking.recover(block), "Station identifies an aligned stationary train without a crossing event");
            block.tick(); var update = block.getUpdateTag(h.getLevel().registryAccess());
            h.assertTrue(update.contains("TrainPresent") && update.contains("TrainCanDisassemble") && update.getUUID("ImminentTrain").equals(t.id),
                "Recovered parking is advertised to the existing station GUI"); h.succeed();
        }));
        add(result, "station_rescue_rejects_moving_or_distant_train", h -> withPlatform(h, false, (t, block) -> {
            t.leaveStation(); t.speed = .01;
            h.assertTrue(!TrainStationParking.recover(block) && t.speed == .01 && t.currentStation == null,
                "A station cannot erase a moving train's momentum");
            t.speed = 0; t.forEachTravellingPoint(point -> point.position += 2); t.tick(h.getLevel());
            double start = t.carriages.getFirst().getLeadingPoint().position;
            h.assertTrue(!TrainStationParking.recover(block) && t.currentStation == null && t.carriages.getFirst().getLeadingPoint().position == start,
                "Rescue cannot teleport an arbitrary nearby train to the assembly pad"); h.succeed();
        }));
        add(result, "native_drill_debits_train_energy", h -> onRail(h,
            fixture -> fixture.setBlock(new BlockPos(7, 4, 9), AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH)), t -> {
                var carriage = t.carriages.getFirst(); carriage.manageEntities(h.getLevel());
                var entity = carriage.anyAvailableEntity(); h.assertTrue(entity != null, "Loaded native carriage entity available for actors");
                var context = entity.getContraption().getActors().stream().map(p -> p.getRight()).filter(c -> AllBlocks.MECHANICAL_DRILL.has(c.state)).findFirst().orElseThrow();
                var behaviour = com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY.get(context.state);
                var target = BlockPos.containing(entity.toGlobalVector(Vec3.atCenterOf(context.localPos).add(behaviour.getActiveAreaOffset(context)), 1));
                h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
                TrainPhysics.shift(t, 0); t.speed = .005;
                for (int i = 0; i < 60; i++) t.tick(h.getLevel());
                h.assertTrue(t.speed == 0 && context.data.getDouble("DCActorTravel") > 0 && h.getLevel().getBlockState(target).is(Blocks.STONE),
                    "Weak train spends finite kinetic energy on a partial stone cut and physically stops: speed=" + t.speed + " paid=" + context.data.getDouble("DCActorTravel") + " target=" + h.getLevel().getBlockState(target) + " motion=" + context.relativeMotion + " managed=" + com.diliupengyun.differential_create.kinetics.ContraptionActorWork.of(context).motionManaged());
                double paid = context.data.getDouble("DCActorTravel");
                for (int i = 0; i < 10; i++) t.tick(h.getLevel());
                h.assertTrue(context.data.getDouble("DCActorTravel") == paid, "Stopped train grants no unpaid cutting progress"); h.succeed();
            }));
        return result;
    }
    private static Set<BlockPos> structure(GameTestHelper h, int water, boolean two) {
        var positions = new HashSet<BlockPos>();
        Consumer<BlockPos> remember = positions::add;
        for (BlockPos root : List.of(BOILER, RESERVE)) for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            var at = root.offset(x, y, z); h.setBlock(at, AllBlocks.FLUID_TANK.getDefaultState()); remember.accept(at);
        }
        h.setBlock(BOILER.below(), AllBlocks.BLAZE_BURNER.getDefaultState().setValue(com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HEAT_LEVEL,
            com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel.SMOULDERING)); remember.accept(BOILER.below());
        if (two) { h.setBlock(BOILER.east().below(), AllBlocks.BLAZE_BURNER.getDefaultState().setValue(com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HEAT_LEVEL,
            com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel.SMOULDERING)); remember.accept(BOILER.east().below()); }
        h.setBlock(WHEEL, AllBlocks.SMALL_BOGEY.getDefaultState().setValue(AbstractBogeyBlock.AXIS, Direction.Axis.Z)); remember.accept(WHEEL);
        SteamBogey.install(h.getBlockEntity(WHEEL));
        h.setBlock(LEVER, ModContent.GEAR_LEVER.getDefaultState()); remember.accept(LEVER);
        h.setBlock(CHEST, Blocks.BARREL); remember.accept(CHEST);
        if (two) {
            BlockPos second = WHEEL.south(4);
            h.setBlock(second, AllBlocks.SMALL_BOGEY.getDefaultState().setValue(AbstractBogeyBlock.AXIS, Direction.Axis.Z)); remember.accept(second);
            SteamBogey.install(h.getBlockEntity(second));
        }
        return positions;
    }
    private static void unpower(GameTestHelper h) {
        AbstractBogeyBlockEntity bogey=h.getBlockEntity(WHEEL);
        var data=bogey.getBogeyData().copy(); data.remove(SteamBogey.ENGINE); data.remove("DCGeared");
        bogey.setBogeyData(data); bogey.notifyUpdate();
    }
    private static void upgradeInteraction(GameTestHelper h) {
        structure(h,0,false);unpower(h);
        var player=h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var engine=AllBlocks.STEAM_ENGINE.asStack();engine.setCount(2);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,engine);
        h.runAtTickTime(25,()->{
            var pos=h.absolutePos(WHEEL);
            var hit=new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false);
            var upgrade=new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player,net.minecraft.world.InteractionHand.MAIN_HAND,pos,hit);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(upgrade);
            h.assertTrue(upgrade.isCanceled()&&SteamBogey.powered(h.getLevel().getBlockEntity(pos))&&engine.getCount()==1,
                "Survival right-click installs exactly one engine on the existing wheel");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player,net.minecraft.world.InteractionHand.MAIN_HAND,pos,hit));
            h.assertTrue(engine.getCount()==1,"Repeated upgrade cannot consume a second engine");
            var hose=ModContent.STEAM_HOSE.asStack();player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,hose);
            var tankPos=h.absolutePos(BOILER);
            ModContent.STEAM_HOSE.get().useOn(new net.minecraft.world.item.context.UseOnContext(player,net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(tankPos),Direction.UP,tankPos,false)));
            ModContent.STEAM_HOSE.get().useOn(new net.minecraft.world.item.context.UseOnContext(player,net.minecraft.world.InteractionHand.MAIN_HAND,hit));
            var bogey=h.getLevel().getBlockEntity(pos);var tank=SteamHoseLinks.tank(bogey);
            h.assertTrue(tank!=null&&tank.boiler.isActive()&&tank.boiler.attachedEngines==1&&hose.getCount()==1,
                "The reusable hose binds an upgraded wheel and activates a native boiler");
            player.setShiftKeyDown(true);player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player,net.minecraft.world.InteractionHand.MAIN_HAND,pos,hit));
            h.assertTrue(!SteamBogey.powered(bogey)&&tank.boiler.attachedEngines==0&&player.getInventory().countItem(AllBlocks.STEAM_ENGINE.asItem())==1,
                "Crouch empty-hand removes the upgrade, clears its boiler link and refunds one engine");h.succeed();
        });
    }
    private static void bogeyDrop(GameTestHelper h) {
        structure(h,0,false);
        h.runAtTickTime(3,()->{
            var pos=h.absolutePos(WHEEL);h.getLevel().destroyBlock(pos,true);
            int returned=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(pos).inflate(2))
                .stream().filter(e->AllBlocks.STEAM_ENGINE.isIn(e.getItem())).mapToInt(e->e.getItem().getCount()).sum();
            h.assertTrue(returned==1,"Breaking an upgraded bogey returns exactly one installed engine");h.succeed();
        });
    }
    private static void withTrain(GameTestHelper h, int volume, boolean two, Consumer<Train> check) {
        withTrain(h, volume, two, fixture -> {}, check);
    }
    private static void withTrain(GameTestHelper h, int volume, boolean two, Consumer<GameTestHelper> customise, Consumer<Train> check) {
        withTrain(h,volume,two,customise,check,true);
    }
    private static void withTrain(GameTestHelper h, int volume, boolean two, Consumer<GameTestHelper> customise, Consumer<Train> check, boolean connect) {
        Set<BlockPos> positions = structure(h, volume, two);
        customise.accept(h);
        h.runAtTickTime(25, () -> {
            FluidTankBlockEntity tank = h.getBlockEntity(BOILER); AbstractBogeyBlockEntity engine = h.getBlockEntity(WHEEL);
            if (connect && SteamBogey.powered(engine)) SteamHoseLinks.connect(engine, tank);
            if (connect && two) SteamHoseLinks.connect(h.getBlockEntity(WHEEL.south(4)), tank);
            FluidTankBlockEntity reserve = h.getBlockEntity(RESERVE); reserve.getControllerBE().getTankInventory().setFluid(new FluidStack(Fluids.WATER, volume));
            IItemHandler source = h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(CHEST), null);
            h.assertTrue(source != null, "Native barrel capability available"); source.insertItem(0, new ItemStack(Items.COAL, 16), false);
            var cc = new CarriageContraption(Direction.SOUTH); cc.anchor = h.absolutePos(BlockPos.ZERO);
            var cargo = new TrainCargoManager();
            for (BlockPos local : positions) {
                BlockPos world = h.absolutePos(local); var state = h.getLevel().getBlockState(world); var be = h.getLevel().getBlockEntity(world);
                CompoundTag tag = be == null ? null : be.saveWithFullMetadata(h.getLevel().registryAccess());
                cc.getBlocks().put(local, new StructureBlockInfo(local, state, tag)); cargo.addBlock(h.getLevel(), state, world, local, be);
            }
            var layout = TrainAssembly.capture(h.getLevel(), cc);
            var bogey = new CarriageBogey(AllBlocks.SMALL_BOGEY.get(), false, new CompoundTag());
            var carriage = new Carriage(bogey, null, 0); carriage.storage = cargo; carriage.storage.initialize();
            ((CarriageSteamState.Holder) carriage).differentialcreate$steam(new CarriageSteamState(layout));
            var train = new Train(UUID.randomUUID(), null, null, List.of(carriage), List.of(), false, 0);
            // Deliberately no dimensional carriage entity: the global Train tick
            // must still consume mounted inventory and advance its persistent rotors.
            h.assertTrue(carriage.anyAvailableEntity() == null, "Offscreen train fixture");
            check.accept(train);
        });
    }
    private static void supply(GameTestHelper h, int volume, boolean two) {
        withTrain(h, volume, two, t -> {
            var cs = TrainPhysics.carriage(t.carriages.getFirst()); h.assertTrue(cs.layout.drives.size() == (two ? 2 : 1), "Physical engines counted once");
            for (int i = 0; i < 20; i++) { TrainPhysics.manual(t, 1, false); t.tick(h.getLevel()); }
            h.assertTrue(t.speed > 0 && cs.engines.values().stream().allMatch(e -> e.omega != 0), "Finite live boiler output starts train offscreen");
            h.assertTrue(water(t) == volume - (two ? 400 : 200), "Water actually drained once per boiler level per tick");
            h.assertTrue(items(t) == 16 - (two ? 2 : 1), "Each boiler burner takes one real coal item");
            h.assertTrue(t.fuelTicks == 0, "No second vanilla fuel boost"); h.succeed();
        });
    }
    private static int water(Train train) { return train.carriages.getFirst().storage.getFluids().storages.values().stream().mapToInt(s -> s.getFluidInTank(0).getAmount()).sum(); }
    private static int items(Train train) { var items = train.carriages.getFirst().storage.getFuelItems(); int n = 0; if (items != null) for (int i = 0; i < items.getSlots(); i++) n += items.getStackInSlot(i).getCount(); return n; }
    private static void hose(GameTestHelper h) {
        structure(h, 0, false);
        h.setBlock(ENGINE.below(), Blocks.STONE);
        h.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.getDefaultState().setValue(SteamEngineBlock.FACE, AttachFace.FLOOR));
        h.runAtTickTime(25, () -> {
            FluidTankBlockEntity part = h.getBlockEntity(BOILER); SteamEngineBlockEntity engine = h.getBlockEntity(ENGINE);
            SteamHoseLinks.connect(engine, part); var controller = part.getControllerBE();
            h.assertTrue(engine.getTank() == controller && controller.boiler.isActive() && controller.boiler.attachedEngines == 1, "Remote engine activates boiler without direct mounting");
            h.assertTrue(!controller.boiler.evaluate(controller), "Stable remote connection does not falsely rebuild the boiler every evaluation");
            CompoundTag tag = engine.saveWithFullMetadata(h.getLevel().registryAccess()); h.assertTrue(tag.contains(SteamHoseLinks.ENGINE_KEY), "Hose endpoint persisted");
            SteamHoseLinks.unlink(engine); h.assertTrue(engine.getTank() == null && controller.boiler.attachedEngines == 0, "Unlink deactivates remote boiler");
            h.setBlock(ENGINE.below(), AllBlocks.FLUID_TANK.getDefaultState());
            h.runAfterDelay(10, () -> {
                FluidTankBlockEntity direct = h.getBlockEntity(ENGINE.below()); direct = direct.getControllerBE();
                SteamHoseLinks.connect(engine, part);
                h.assertTrue(engine.getTank() == controller && engine.source.get() == controller && controller.boiler.attachedEngines == 1 && direct.boiler.attachedEngines == 0,
                    "Explicit hose overrides direct mount without activating a second boiler");
                // Native placement evaluates the backing tank before engine
                // link NBT is read. Its first tick must repair that old count.
                direct.boiler.attachedEngines = 1; engine.tick();
                h.assertTrue(direct.boiler.attachedEngines == 0 && engine.getTank() == controller, "First engine tick repairs a stale backing-boiler count after load");
                SteamHoseLinks.unlink(engine);
                h.assertTrue(engine.getTank() == direct && direct.boiler.attachedEngines == 1 && controller.boiler.attachedEngines == 0,
                    "Removing hose restores original direct mount"); h.succeed();
            });
        });
    }
    private static void ratio(GameTestHelper h) {
        h.setBlock(CONTROLLER, AllBlocks.ROTATION_SPEED_CONTROLLER.getDefaultState());
        SpeedControllerBlockEntity controller = h.getBlockEntity(CONTROLLER); var ratio = (RatioValueBehaviour) controller.targetSpeed;
        for (int raw : new int[]{-256, -131, -17, -1, 0, 1, 17, 131, 256}) {
            ratio.setValue(raw); var cursor = ratio.getValueSettings(); ratio.setValueSettings(null, cursor, false);
            h.assertTrue(ratio.getValue() == raw, "Opening/closing logarithmic dial preserves exact legacy ratio");
            var tag = new CompoundTag(); ratio.writeToClipboard(h.getLevel().registryAccess(), tag, Direction.UP);
            ratio.setValue(raw == 16 ? 32 : 16); ratio.readFromClipboard(h.getLevel().registryAccess(), tag, null, Direction.UP, false);
            h.assertTrue(ratio.getValue() == raw, "Clipboard retains signed sixteenths, including N");
        }
        h.assertTrue(AllConfigs.server().trains.manualTrainSpeedModifier.getF() == 1, "Removed config field retains safe binary-compatible constant"); h.succeed();
    }
    private static void blacklist(GameTestHelper h) {
        h.setBlock(CHEST, AllBlocks.ITEM_VAULT.getDefaultState());
        h.runAtTickTime(10, () -> {
            var storage = new TrainCargoManager(); BlockPos world = h.absolutePos(CHEST);
            storage.addBlock(h.getLevel(), h.getLevel().getBlockState(world), world, CHEST, h.getLevel().getBlockEntity(world)); storage.initialize();
            h.assertTrue(storage.getFuelItems() == null, "Create's vault fuel blacklist is retained"); h.succeed();
        });
    }
    private static void assembly(GameTestHelper h) {
        Set<BlockPos> parts = structure(h, 0, false);
        for (int x = 2; x <= 10; x++) for (int z = 4; z <= 9; z++) {
            var at = new BlockPos(x, 3, z); if (!parts.contains(at)) h.setBlock(at, Blocks.STONE);
        }
        for (BlockPos at : List.of(WHEEL.west(), WHEEL.east(), WHEEL.north(), WHEEL.south()))
            if (!parts.contains(at)) h.setBlock(at, Blocks.STONE);
        h.getLevel().addFreshEntity(new SuperGlueEntity(h.getLevel(), SuperGlueEntity.span(h.absolutePos(new BlockPos(2, 3, 4)), h.absolutePos(new BlockPos(10, 7, 9)))));
        h.runAtTickTime(25, () -> {
            AbstractBogeyBlockEntity engine = h.getBlockEntity(WHEEL); SteamHoseLinks.connect(engine, h.getBlockEntity(BOILER));
            var cc = new CarriageContraption(Direction.SOUTH);
            try { h.assertTrue(cc.assemble(h.getLevel(), h.absolutePos(WHEEL)), "Native glued carriage assembles"); }
            catch (com.simibubi.create.content.contraptions.AssemblyException e) { throw new IllegalStateException(e); }
            var layout = ((TrainAssembly.LayoutHolder) cc).differentialcreate$layout();
            h.assertTrue(layout != null && layout.drives.size() == 1, "Native assembly hook captured real drivetrain");
            var steam = new CarriageSteamState(layout); steam.water.put(layout.boilers.keySet().iterator().next(), .375); steam.apply(cc, 1, false, true);
            BlockPos localEngine = h.absolutePos(WHEEL).subtract(cc.anchor);
            var transform = new StructureTransform(cc.anchor, Direction.Axis.Y, Rotation.CLOCKWISE_90, Mirror.NONE);
            BlockPos placedEngine = transform.apply(localEngine);
            cc.removeBlocksFromWorld(h.getLevel(), BlockPos.ZERO);
            CarriageContraptionEntity.create(h.getLevel(), cc);
            cc.addBlocksToWorld(h.getLevel(), transform);
            h.runAfterDelay(10, () -> {
                h.assertTrue(SteamBogey.powered(h.getLevel().getBlockEntity(placedEngine)), "Installed engine restored after rotation");
                var moved = h.getLevel().getBlockEntity(placedEngine);
                h.assertTrue(SteamHoseLinks.tank(moved) != null && SteamHoseLinks.tank(moved).boiler.isActive(), "Rotated hose and multiblock controller register a working boiler");
                h.assertTrue(((SteamHoseLinks.TankLinks) SteamHoseLinks.tank(moved)).differentialcreate$feedWater() == .375, "Fractional paid water remains after rotated disassembly");
                h.succeed();
            });
        });
    }
    private static void onRail(GameTestHelper h, Consumer<Train> check) {
        onRail(h, fixture -> {}, check);
    }
    private static void onRail(GameTestHelper h, Consumer<GameTestHelper> customise, Consumer<Train> check) {
        onRail(h, customise, check, false);
    }
    private static void onRail(GameTestHelper h, Consumer<GameTestHelper> customise, Consumer<Train> check, boolean nativePadHeight) {
        Set<BlockPos> parts = structure(h, 4000, false);
        for (int x = 2; x <= 10; x++) for (int z = 4; z <= 9; z++) {
            var at = new BlockPos(x, 3, z); if (!parts.contains(at)) h.setBlock(at, Blocks.STONE);
        }
        for (BlockPos at : List.of(WHEEL.west(), WHEEL.east(), WHEEL.north(), WHEEL.south())) if (!parts.contains(at)) h.setBlock(at, Blocks.STONE);
        customise.accept(h);
        h.getLevel().addFreshEntity(new SuperGlueEntity(h.getLevel(), SuperGlueEntity.span(h.absolutePos(new BlockPos(2, 3, 4)), h.absolutePos(new BlockPos(10, 7, 9)))));
        h.runAtTickTime(25, () -> {
            if (h.getLevel().getBlockEntity(h.absolutePos(WHEEL)) instanceof AbstractBogeyBlockEntity engine && SteamBogey.powered(engine))
                SteamHoseLinks.connect(engine, h.getBlockEntity(BOILER));
            ((FluidTankBlockEntity) h.getBlockEntity(RESERVE)).getControllerBE().getTankInventory().setFluid(new FluidStack(Fluids.WATER, 4000));
            h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(CHEST), null).insertItem(0, new ItemStack(Items.COAL, 16), false);
            var cc = new CarriageContraption(Direction.SOUTH);
            try { h.assertTrue(cc.assemble(h.getLevel(), h.absolutePos(WHEEL)), "Rail fixture assembles through native API"); }
            catch (com.simibubi.create.content.contraptions.AssemblyException e) { throw new IllegalStateException(e); }
            cc.removeBlocksFromWorld(h.getLevel(), BlockPos.ZERO);
            var graph = new TrackGraph(); Vec3 origin = Vec3.atCenterOf(h.absolutePos(WHEEL)).subtract(0, nativePadHeight ? 1.5 : 0, 20);
            var a = new DiscoveredLocation(h.getLevel(), origin).withNormal(new Vec3(0, 1, 0)).materialA(TrackMaterial.ANDESITE);
            var b = new DiscoveredLocation(h.getLevel(), origin.add(0, 0, 2048)).withNormal(new Vec3(0, 1, 0)).materialA(TrackMaterial.ANDESITE);
            graph.createNodeIfAbsent(a); graph.createNodeIfAbsent(b); graph.connectNodes(h.getLevel(), a, b, null);
            var na = graph.locateNode(a); var nb = graph.locateNode(b); var edge = graph.getConnection(Couple.create(na, nb));
            double spacing = AllBlocks.SMALL_BOGEY.get().getWheelPointSpacing();
            var bogey = new CarriageBogey(AllBlocks.SMALL_BOGEY.get(), false, new CompoundTag(),
                new TravellingPoint(na, nb, edge, 20 + spacing / 2, false), new TravellingPoint(na, nb, edge, 20 - spacing / 2, false));
            var carriage = new Carriage(bogey, null, 0);
            var train = new Train(UUID.randomUUID(), null, graph, List.of(carriage), List.of(), false, 0);
            carriage.setContraption(h.getLevel(), cc);
            try { check.accept(train); }
            finally { carriage.forEachPresentEntity(e -> e.discard()); }
        });
    }
    private static void withPlatform(GameTestHelper h, boolean powered, BiConsumer<Train, StationBlockEntity> check) {
        onRail(h, fixture -> { if (!powered) unpower(fixture); }, t -> {
            var track = WHEEL.below().south(); var blockPos = new BlockPos(0, 4, 5);
            h.setBlock(track, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.ZO));
            h.setBlock(blockPos, AllBlocks.TRACK_STATION.getDefaultState());
            var block = (StationBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(blockPos));
            var parked = station(h, t, 21); parked.blockEntityPos = block.getBlockPos();
            var binding = new CompoundTag(); binding.putUUID("Id", parked.id); binding.putBoolean("Ortho", true);
            binding.putBoolean("TargetDirection", false);
            binding.put("TargetTrack", NbtUtils.writeBlockPos(h.absolutePos(track).subtract(block.getBlockPos())));
            block.edgePoint.read(binding, h.getLevel().registryAccess(), false);
            Create.RAILWAYS.trackNetworks.put(t.graph.id, t.graph); Create.RAILWAYS.addTrain(t);
            try {
                block.edgePoint.tick();
                h.assertTrue(block.getStation() == parked && block.getAssemblyDirection() == Direction.NORTH, "Native station bound to the fixture's real assembly pad");
                t.setCurrentStation(parked); parked.reserveFor(t); t.tick(h.getLevel());
                check.accept(t, block);
            } finally {
                h.setBlock(blockPos, Blocks.AIR); Create.RAILWAYS.removeTrain(t.id); Create.RAILWAYS.trackNetworks.remove(t.graph.id);
            }
        }, true);
    }
    private static GlobalStation station(GameTestHelper h, Train train, double position) {
        var lead = train.carriages.getFirst().getLeadingPoint(); var station = new GlobalStation();
        station.setType(EdgePointType.STATION); station.setId(UUID.randomUUID());
        station.blockEntityPos = h.absolutePos(BlockPos.ZERO); station.blockEntityDimension = h.getLevel().dimension();
        station.setLocation(Couple.create(lead.node1.getLocation(), lead.node2.getLocation()), position);
        train.graph.addPoint(EdgePointType.STATION, station); return station;
    }
}
