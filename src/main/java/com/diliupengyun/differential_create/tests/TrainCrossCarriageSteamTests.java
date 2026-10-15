package com.diliupengyun.differential_create.tests;

import java.util.*;
import com.diliupengyun.differential_create.trains.*;
import com.diliupengyun.differential_create.kinetics.BoilerAdmission;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

/** Native capture of four separate hose-fed carriages, shared payment, readback and world rebuild. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainCrossCarriageSteamTests {
    private TrainCrossCarriageSteamTests() {}
    @GameTestGenerator public static Collection<TestFunction> cases() {
        String name = "train_four_cross_carriage_hoses";
        if (!name.matches(System.getProperty("differentialcreate.trains.select", ".*"))) return List.of();
        return List.of(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test", 200, 0, true,
            TrainCrossCarriageSteamTests::fourHoses));
    }
    private static void fourHoses(GameTestHelper h) {
        var captured = new ArrayList<CarriageContraption>();
        TrainTestFixture.create(h, "flat", false, false, setup -> {
            for (int i = 0; i < 4; i++) {
                var at = TrainTestFixture.WHEEL.south(9 + i * 2);
                setup.setBlock(at, AllBlocks.SMALL_BOGEY.getDefaultState().setValue(AbstractBogeyBlock.AXIS, Direction.Axis.Z));
                setup.setBlock(at.above(), AllBlocks.RAILWAY_CASING.getDefaultState());
                setup.getLevel().addFreshEntity(new SuperGlueEntity(setup.getLevel(), SuperGlueEntity.span(setup.absolutePos(at), setup.absolutePos(at.above()))));
                SteamBogey.install(setup.getBlockEntity(at));
            }
            setup.runAfterDelay(24, () -> {
                for (int i = 0; i < 4; i++) {
                    var at = TrainTestFixture.WHEEL.south(9 + i * 2);
                    SteamHoseLinks.connect(setup.getBlockEntity(at), setup.getBlockEntity(TrainTestFixture.BOILER.east().south()));
                    var cc = new CarriageContraption(Direction.SOUTH);
                    try { h.assertTrue(cc.assemble(h.getLevel(), h.absolutePos(at)), "Separate powered carriage captures natively"); }
                    catch (com.simibubi.create.content.contraptions.AssemblyException e) { throw new IllegalStateException(e); }
                    var layout = ((TrainAssembly.LayoutHolder) cc).differentialcreate$layout();
                    h.assertTrue(layout.motors.size() == 1 && layout.boilers.isEmpty(), "A remote hose retains its motor without duplicating the boiler");
                    captured.add(cc);
                }
                for (var cc : captured) cc.removeBlocksFromWorld(h.getLevel(), BlockPos.ZERO);
            });
        }, original -> {
            var carriages = new ArrayList<Carriage>(); carriages.add(original.train.carriages.getFirst());
            var point = carriages.getFirst().getLeadingPoint();
            for (int i = 0; i < 4; i++) {
                var cc = captured.get(i); double at = 20 - 9 - i * 2;
                var bogey = new CarriageBogey(AllBlocks.SMALL_BOGEY.get(), false,
                    cc.getBlocks().get(BlockPos.ZERO).nbt().getCompound("BogeyData").copy(),
                    new TravellingPoint(point.node1, point.node2, point.edge, at + 1, false),
                    new TravellingPoint(point.node1, point.node2, point.edge, at - 1, false));
                carriages.add(new Carriage(bogey, null, 0));
            }
            unload(carriages.getFirst(), h);
            original.close();
            original.train = new Train(UUID.randomUUID(), null, original.graph, carriages, List.of(9, 2, 2, 2), false, 0);
            for (int i = 1; i < carriages.size(); i++) carriages.get(i).setContraption(h.getLevel(), captured.get(i - 1));
            Create.RAILWAYS.trackNetworks.put(original.graph.id, original.graph); Create.RAILWAYS.addTrain(original.train);
            for (var c : carriages) { c.updateContraptionAnchors(); c.manageEntities(h.getLevel()); }
            double area = TrainPhysics.frontalArea(original.train);
            var leadingProjection = TrainPhysics.carriage(carriages.getFirst()).layout.frontalProjection;
            var exposed = new HashSet<Long>();
            for (int i = 1; i < carriages.size(); i++) exposed.addAll(TrainPhysics.carriage(carriages.get(i)).layout.frontalProjection);
            exposed.removeAll(leadingProjection);
            h.assertTrue(area == leadingProjection.size() + exposed.size(),
                "Four narrow independent carriages add only the parts outside the leading silhouette");
            var network = TrainSteamConnections.resolve(original.train);
            h.assertTrue(network.supplies.size() == 1 && network.supplies.getFirst().connections.size() == 5,
                "All four remote hoses share the original boiler, counted once");
            for (int i = 1; i < carriages.size(); i++) {
                var state = TrainPhysics.carriage(carriages.get(i));
                h.assertTrue(state.layout.drives.size() == 1 && state.boilers.isEmpty(), "Remote wheel has one drive and no copied boiler inventory");
                h.assertTrue(TrainSteamConnections.resolve(List.of(state)).source(state, BlockPos.ZERO) == null,
                    "An engine cannot borrow a boiler from another Train");
            }
            int initialWater = original.water(); double paid = 0;
            var supply = network.supplies.getFirst();
            for (int tick = 0; tick < 20; tick++) {
                original.tick(1, false);
                paid += BoilerAdmission.demand(supply.carriage.boilers.get(supply.boiler.pos()));
            }
            double remainder = supply.carriage.water.get(supply.boiler.pos());
            h.assertTrue(Math.abs(initialWater - original.water() - paid - remainder) < 1e-8,
                "Shared demand debits train water once and preserves its sub-mB remainder");
            var boiler = supply.carriage.boilers.get(supply.boiler.pos());
            h.assertTrue(boiler.attachedEngines == 5, "The boiler sees every remote consumer");
            double power = 0;
            for (var c : carriages) for (var e : TrainPhysics.carriage(c).engines.values()) {
                h.assertTrue(e.rpm() > 0 && e.power > 0, "Every connected carriage receives actual steam and rotor motion"); power += e.power;
            }
            double limit = SourcePhysics.steamCurve(1024, SourcePhysics.thermalSteamLevel(boiler, supply.boiler.size()), 1, 1).maximumPower()
                * TrainMechanics.TORQUE_SCALE * 2 * Math.PI / 60;
            h.assertTrue(Math.abs(power - limit) < 1e-6, "Five engines share the one-level power budget instead of each claiming a complete boiler");
            original.reload();
            h.assertTrue(TrainPhysics.frontalArea(original.train) == area, "Native train readback preserves whole-train shadowing");
            var restored = TrainSteamConnections.resolve(original.train);
            h.assertTrue(restored.supplies.size() == 1 && restored.supplies.getFirst().connections.size() == 5,
                "Native Train save/read preserves all remote boiler bindings");
            original.tick(1, false);
            original.train.carriages.forEach(c -> unload(c, h));
            h.assertTrue(TrainPhysics.frontalArea(original.train) == area, "Native entity unload preserves whole-train shadowing");
            h.assertTrue(original.train.carriages.stream().allMatch(c -> c.anyAvailableEntity() == null), "Native unload clears every carriage entity");
            TrainPhysics.manual(original.train, 1, false); TrainPhysics.advance(original.train);
            h.assertTrue(TrainPhysics.carriage(original.train.carriages.getLast()).engines.get(BlockPos.ZERO).power > 0,
                "Remote supply does not depend on loaded carriage entities");
            original.reload();
            original.train.speed = 0;
            h.runAfterDelay(5, () -> {
                for (var c : original.train.carriages) c.manageEntities(h.getLevel());
                // Choose an exact straight station pose after the deliberate entity unload.
                for (var c : original.train.carriages) {
                    // Recreate the small template's outlying carriages through Create's saved-entity path.
                    if (c.anyAvailableEntity() == null) {
                        try {
                            var create = Carriage.DimensionalCarriageEntity.class.getDeclaredMethod("createEntity", net.minecraft.world.level.Level.class, boolean.class);
                            create.setAccessible(true); create.invoke(c.getDimensional(h.getLevel()), h.getLevel(), false);
                        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                    }
                    var entity = c.anyAvailableEntity();
                    h.assertTrue(entity != null, "Native reload provides a carriage for disassembly: index="
                        + original.train.carriages.indexOf(c) + " point=" + c.getLeadingPoint().getPosition(original.train.graph));
                    entity.yaw = entity.prevYaw = entity.getInitialYaw();
                    entity.pitch = entity.prevPitch = 0;
                }
                h.assertTrue(original.train.disassemble(Direction.SOUTH, h.absolutePos(TrainTestFixture.WHEEL).north()),
                    "The native Train disassembles all five carriages");
                for (int i = 0; i < 4; i++) {
                    var at = h.absolutePos(TrainTestFixture.WHEEL.south(9 + i * 2));
                    var motor = h.getLevel().getBlockEntity(at);
                    h.assertTrue(SteamBogey.powered(motor) && SteamHoseLinks.tank(motor) != null,
                        "Disassembled remote bogey preserves a real world hose endpoint: " + at);
                    var cc = new CarriageContraption(Direction.SOUTH);
                    try { h.assertTrue(cc.assemble(h.getLevel(), at), "A remote bogey reassembles natively"); }
                    catch (com.simibubi.create.content.contraptions.AssemblyException e) { throw new IllegalStateException(e); }
                    h.assertTrue(((TrainAssembly.LayoutHolder) cc).differentialcreate$layout().motors.size() == 1,
                        "Reassembly retains the remote motor");
                    h.getLevel().removeBlock(at, false);
                    h.getLevel().removeBlock(at.above(), false);
                }
                original.finish();
            });
        });
    }
    private static void unload(Carriage carriage, GameTestHelper h) {
        try {
            var unload = Carriage.DimensionalCarriageEntity.class.getDeclaredMethod("removeAndSaveEntity", CarriageContraptionEntity.class, boolean.class);
            unload.setAccessible(true);
            var entity = carriage.anyAvailableEntity();
            if (entity != null) unload.invoke(carriage.getDimensional(h.getLevel()), entity, false);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}
