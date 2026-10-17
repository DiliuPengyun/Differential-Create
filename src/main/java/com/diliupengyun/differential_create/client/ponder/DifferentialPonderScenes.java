package com.diliupengyun.differential_create.client.ponder;

import java.util.Set;

import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.kinetics.ContraptionGeometry;
import com.diliupengyun.differential_create.kinetics.BoilerAdmission;
import com.diliupengyun.differential_create.kinetics.LoadFactor;
import com.diliupengyun.differential_create.kinetics.LoadGaugeScale;
import com.diliupengyun.differential_create.kinetics.SpeedGaugeRangeBehaviour;
import com.diliupengyun.differential_create.trains.GearLeverBlock;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import com.simibubi.create.content.contraptions.mounted.CartAssembleRailType;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlock;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.gauge.GaugeBlock;
import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity;
import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlock;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.MinecartElement;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/** Construction is scripted; shaft motion is integrated by the shared torque solver. */
public final class DifferentialPonderScenes {
    private static final BlockPos INPUT = new BlockPos(0, 1, 2), CONTROL = new BlockPos(2, 1, 2), OUTPUT = new BlockPos(4, 1, 2);
    private static final BlockPos TRAIN_BOGEY = new BlockPos(3, 2, 4), TRAIN_REAR_BOGEY = new BlockPos(7, 2, 4);
    private static final BlockPos TRAIN_BOILER = new BlockPos(6, 4, 3), TRAIN_FUEL = new BlockPos(5, 3, 3);
    private static final BlockPos TRAIN_WATER = new BlockPos(5, 3, 5);
    private static final BlockPos TRAIN_CONTROLS = new BlockPos(2, 3, 4), TRAIN_LEVER = new BlockPos(2, 3, 3);
    private DifferentialPonderScenes() {}

    private static CreateSceneBuilder start(SceneBuilder builder, SceneBuildingUtil util, PonderLesson lesson) {
        var scene = new CreateSceneBuilder(builder);
        lesson.title(scene);
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).useExplicitSources());
        scene.configureBasePlate(0, 0, 5);
        scene.world().setBlocks(util.select().fromTo(-1, 1, -1, 7, 6, 7), Blocks.AIR.defaultBlockState(), false);
        scene.showBasePlate();
        scene.idle(5);
        return scene;
    }

    private static void block(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos pos, BlockState state) {
        // A revealing section must own the block exclusively, including while it fades in.
        // An existing owner can still be fading in, before it merges into the base section.
        scene.addInstruction(s -> s.forEachVisible(WorldSectionElement.class,
            section -> section.erase(util.select().position(pos))));
        scene.world().setBlock(pos, state, false);
        scene.world().showSection(util.select().position(pos), Direction.DOWN);
    }

    private static void replaceBlock(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos pos, BlockState state) {
        // Follow Create's hide / replace / reveal sequence; the outgoing section reads the live world.
        scene.world().hideSection(util.select().position(pos), Direction.UP);
        scene.idle(15);
        block(scene, util, pos, state);
        scene.idle(15);
    }

    private static BlockState axis(BlockState state, Axis axis) {
        if (state.getBlock() instanceof SequencedGearshiftBlock)
            state = state.setValue(SequencedGearshiftBlock.VERTICAL, axis.isVertical());
        if (state.hasProperty(BlockStateProperties.AXIS)) return state.setValue(BlockStateProperties.AXIS, axis);
        if (axis.isHorizontal() && state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS))
            return state.setValue(BlockStateProperties.HORIZONTAL_AXIS, axis);
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            && state.getBlock() instanceof com.simibubi.create.content.kinetics.base.IRotate rotate) {
            if (rotate.getRotationAxis(state) == axis) return state;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                var candidate = state.setValue(BlockStateProperties.HORIZONTAL_FACING, direction);
                if (rotate.getRotationAxis(candidate) == axis) return candidate;
            }
        }
        if (state.hasProperty(BlockStateProperties.FACING)
            && state.getBlock() instanceof com.simibubi.create.content.kinetics.base.IRotate rotate) {
            if (rotate.getRotationAxis(state) == axis) return state;
            for (Direction direction : Direction.values()) {
                var candidate = state.setValue(BlockStateProperties.FACING, direction);
                if (rotate.getRotationAxis(candidate) == axis) return candidate;
            }
        }
        return state;
    }

    private static void drive(CreateSceneBuilder scene, SceneBuildingUtil util, BlockState control, BlockState output) {
        drive(scene, util, control, output, 1);
    }

    private static void drive(CreateSceneBuilder scene, SceneBuildingUtil util, BlockState control, BlockState output, int height) {
        for (int x = 1; x <= 3; x += 2) block(scene, util, new BlockPos(x, height, 2),
            axis(AllBlocks.SHAFT.getDefaultState(), Axis.X));
        block(scene, util, INPUT.above(height - 1), motor(Direction.EAST));
        block(scene, util, CONTROL.above(height - 1), axis(control, Axis.X));
        block(scene, util, OUTPUT.above(height - 1), axis(output, Axis.X));
        scene.world().setKineticSpeed(util.select().everywhere(), 0);
        scene.idle(15);
    }

    private static BlockState motor(Direction facing) {
        return AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, facing);
    }

    /** Scripted placement skips neighbour updates; let Create select the connected rack models. */
    private static void connectGantryShafts(CreateSceneBuilder scene, Selection shafts) {
        scene.addInstruction(s -> {
            var world = s.getWorld();
            for (var pos : shafts) {
                var state = world.getBlockState(pos);
                if (!AllBlocks.GANTRY_SHAFT.has(state)) continue;
                for (var direction : Direction.values())
                    state = state.updateShape(direction, world.getBlockState(pos.relative(direction)), world,
                        pos, pos.relative(direction));
                world.setBlock(pos, state, 2 | 16);
                if (world.getBlockEntity(pos) != null) world.getBlockEntity(pos).setBlockState(state);
            }
            s.forEach(WorldSectionElement.class, WorldSectionElement::queueRedraw);
        });
    }

    /** Change only the drive command; inertia, load and contact torque determine the ramp. */
    private static void target(CreateSceneBuilder scene, Selection selection, float to) {
        scene.world().setKineticSpeed(selection, to);
        scene.idle(40);
    }

    public static void dynamics(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.DYNAMICS);
        BlockPos input = INPUT.above(2), control = CONTROL.above(2), output = OUTPUT.above(2);
        drive(scene, util, AllBlocks.FLYWHEEL.getDefaultState(), AllBlocks.MECHANICAL_PRESS.getDefaultState(), 3);
        block(scene, util, OUTPUT, AllBlocks.DEPOT.getDefaultState());
        scene.world().createItemOnBeltLike(OUTPUT, Direction.UP, new ItemStack(Items.COPPER_INGOT, 4));
        scene.world().modifyBlockEntity(output, MechanicalPressBlockEntity.class,
            press -> press.getPressingBehaviour().start(PressingBehaviour.Mode.BELT));
        target(scene, util.select().position(input), 64);
        scene.effects().rotationSpeedIndicator(control);
        PonderLesson.DYNAMICS.show(scene, util, control, 0);
        scene.idle(120);

        scene.world().modifyBlockEntity(output, MechanicalPressBlockEntity.class,
            press -> press.getPressingBehaviour().start(PressingBehaviour.Mode.BELT));
        target(scene, util.select().position(input), 0);
        PonderLesson.DYNAMICS.show(scene, util, output, 1);
        scene.idle(120);

        BlockPos upperCog = new BlockPos(2, 4, 3);
        BlockPos gearedShaft = new BlockPos(3, 4, 3);
        BlockPos gearedPress = new BlockPos(4, 4, 3);
        BlockPos gearedDepot = new BlockPos(4, 2, 3);
        BlockPos gearedSupport = new BlockPos(4, 1, 3);
        replaceBlock(scene, util, control, axis(AllBlocks.LARGE_COGWHEEL.getDefaultState(), Axis.X));
        block(scene, util, upperCog, axis(AllBlocks.COGWHEEL.getDefaultState(), Axis.X));
        block(scene, util, gearedShaft, axis(AllBlocks.SHAFT.getDefaultState(), Axis.X));
        block(scene, util, gearedSupport, Blocks.SMOOTH_STONE.defaultBlockState());
        block(scene, util, gearedPress, axis(AllBlocks.MECHANICAL_PRESS.getDefaultState(), Axis.X));
        block(scene, util, gearedDepot, AllBlocks.DEPOT.getDefaultState());
        scene.world().createItemOnBeltLike(gearedDepot, Direction.UP, new ItemStack(Items.COPPER_INGOT, 4));
        scene.world().modifyBlockEntity(gearedPress, MechanicalPressBlockEntity.class,
            press -> press.getPressingBehaviour().start(PressingBehaviour.Mode.BELT));
        // Keep the main-shaft press running as the 1x reference; the new press
        // sits on the 2x small gear, so its ingots are consumed at a higher rate.
        scene.world().modifyBlockEntity(output, MechanicalPressBlockEntity.class,
            press -> press.getPressingBehaviour().start(PressingBehaviour.Mode.BELT));
        scene.rotateCameraY(90);
        target(scene, util.select().position(input), 32);
        scene.effects().rotationSpeedIndicator(upperCog);
        PonderLesson.DYNAMICS.show(scene, util, upperCog, 2, PonderPalette.GREEN);
        scene.idle(120);

        scene.overlay().showOutline(PonderPalette.RED, new Object(), util.select().fromTo(1, 3, 2, 2, 3, 2), 80);
        PonderLesson.DYNAMICS.show(scene, util, new BlockPos(1, 3, 2), 3, PonderPalette.RED);
        scene.markAsFinished();
    }

    public static void clutch(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.CLUTCH);
        drive(scene, util, AllBlocks.CLUTCH.getDefaultState(), AllBlocks.FLYWHEEL.getDefaultState());
        block(scene, util, CONTROL.north(), AllBlocks.ANALOG_LEVER.getDefaultState());
        lever(scene, util, 0);
        target(scene, util.select().position(INPUT), 64);
        scene.effects().indicateSuccess(CONTROL);
        PonderLesson.CLUTCH.show(scene, util, CONTROL, 0);

        scene.overlay().showControls(util.vector().topOf(CONTROL.north()), Pointing.DOWN, 35).rightClick();
        lever(scene, util, 10);
        scene.effects().indicateRedstone(CONTROL.north());
        scene.idle(25);
        PonderLesson.CLUTCH.show(scene, util, CONTROL.north(), 1, PonderPalette.MEDIUM);

        lever(scene, util, 15);
        scene.effects().indicateRedstone(CONTROL.north());
        scene.world().modifyBlock(CONTROL, s -> s.setValue(BlockStateProperties.POWERED, true), false);
        target(scene, util.select().position(INPUT), 0);
        PonderLesson.CLUTCH.show(scene, util, OUTPUT, 2, PonderPalette.RED);

        replaceBlock(scene, util, CONTROL, axis(ModContent.BRASS_CLUTCH.getDefaultState(), Axis.X));
        lever(scene, util, 0);
        target(scene, util.select().position(INPUT), 64);
        scene.overlay().showFilterSlotInput(util.vector().blockSurface(CONTROL, Direction.SOUTH), Direction.SOUTH, 60);
        scene.overlay().showControls(util.vector().blockSurface(CONTROL, Direction.SOUTH), Pointing.UP, 40).rightClick();
        PonderLesson.CLUTCH.show(scene, util, CONTROL, 3, PonderPalette.BLUE);
        scene.markAsFinished();
    }

    private static void lever(CreateSceneBuilder scene, SceneBuildingUtil util, int signal) {
        scene.world().modifyBlockEntityNBT(util.select().position(CONTROL.north()),
            com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity.class, tag -> tag.putInt("State", signal));
        scene.world().modifyBlockEntity(CONTROL, com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity.class,
            clutch -> ((com.diliupengyun.differential_create.kinetics.IClutchDynamics) clutch).differentialcreate$setRedstoneSignal(signal));
    }

    public static void ratio(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.RATIO);
        BlockPos topCog = CONTROL.above();
        BlockPos topMotor = topCog.south();
        block(scene, util, new BlockPos(1, 1, 2), axis(AllBlocks.SHAFT.getDefaultState(), Axis.X));
        block(scene, util, CONTROL, axis(AllBlocks.ROTATION_SPEED_CONTROLLER.getDefaultState(), Axis.X));
        block(scene, util, new BlockPos(3, 1, 2), axis(AllBlocks.SHAFT.getDefaultState(), Axis.X));
        block(scene, util, OUTPUT, axis(AllBlocks.FLYWHEEL.getDefaultState(), Axis.X));
        block(scene, util, topCog, axis(AllBlocks.LARGE_COGWHEEL.getDefaultState(), Axis.Z));
        block(scene, util, topMotor, motor(Direction.NORTH));
        scene.world().setKineticSpeed(util.select().everywhere(), 0);
        scene.idle(10);

        ratioSetting(scene, 16);
        target(scene, util.select().position(topMotor), 32);
        scene.effects().rotationSpeedIndicator(CONTROL);
        PonderLesson.RATIO.show(scene, util, CONTROL, 0);

        scene.overlay().showFilterSlotInput(util.vector().blockSurface(CONTROL, Direction.NORTH), Direction.NORTH, 60);
        scene.overlay().showControls(util.vector().blockSurface(CONTROL, Direction.NORTH), Pointing.UP, 35).scroll();
        ratioSetting(scene, -16);
        scene.idle(25);
        ratioSetting(scene, 0);
        scene.idle(20);
        PonderLesson.RATIO.show(scene, util, CONTROL, 1, PonderPalette.MEDIUM);

        ratioSetting(scene, 32);
        scene.idle(30);
        scene.effects().rotationSpeedIndicator(OUTPUT);
        PonderLesson.RATIO.show(scene, util, topCog, 2, PonderPalette.GREEN);

        scene.overlay().showControls(util.vector().blockSurface(CONTROL, Direction.NORTH), Pointing.UP, 40)
            .rightClick().whileCTRL();
        ratioSetting(scene, 24);
        scene.idle(20);
        PonderLesson.RATIO.show(scene, util, CONTROL, 3, PonderPalette.BLUE);
        scene.markAsFinished();
    }

    private static void ratioSetting(CreateSceneBuilder scene, int raw) {
        scene.world().modifyBlockEntity(CONTROL,
            com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity.class,
            controller -> controller.targetSpeed.setValue(raw));
    }

    public static void loadGauge(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.LOAD_GAUGE);
        drive(scene, util, AllBlocks.FLYWHEEL.getDefaultState(),
            AllBlocks.STRESSOMETER.getDefaultState()
                .setValue(GaugeBlock.AXIS_ALONG_FIRST_COORDINATE, true)
                .setValue(GaugeBlock.FACING, Direction.UP));
        block(scene, util, OUTPUT.north(), Blocks.COMPARATOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        load(scene, Double.NaN);
        target(scene, util.select().position(INPUT), 64);
        load(scene, 0.35);
        PonderLesson.LOAD_GAUGE.show(scene, util, OUTPUT, 0);

        load(scene, 1.0);
        scene.effects().indicateSuccess(OUTPUT);
        scene.effects().indicateRedstone(OUTPUT.north());
        PonderLesson.LOAD_GAUGE.show(scene, util, OUTPUT, 1, PonderPalette.MEDIUM);
        load(scene, Double.POSITIVE_INFINITY);
        scene.idle(30);

        target(scene, util.select().position(INPUT), 0);
        load(scene, Double.NaN);
        PonderLesson.LOAD_GAUGE.show(scene, util, OUTPUT.north(), 2, PonderPalette.RED);

        scene.overlay().showControls(util.vector().topOf(OUTPUT), Pointing.DOWN, 40)
            .withItem(AllItems.GOGGLES.asStack());
        PonderLesson.LOAD_GAUGE.show(scene, util, OUTPUT, 3, PonderPalette.BLUE);
        scene.markAsFinished();
    }

    private static void load(CreateSceneBuilder scene, double fraction) {
        var reading = Double.isNaN(fraction) ? LoadFactor.Reading.NO_POWER
            : new LoadFactor.Reading(LoadFactor.Status.AVAILABLE, fraction);
        scene.world().modifyBlockEntity(OUTPUT, StressGaugeBlockEntity.class, gauge -> {
            gauge.dialTarget = LoadGaugeScale.position(reading);
            gauge.color = LoadGaugeScale.color(reading);
        });
        int signal = LoadGaugeScale.signal(reading);
        scene.world().modifyBlock(OUTPUT.north(), s -> s.setValue(BlockStateProperties.POWERED, signal > 0), false);
        scene.world().modifyBlockEntity(OUTPUT.north(), net.minecraft.world.level.block.entity.ComparatorBlockEntity.class,
            comparator -> comparator.setOutputSignal(signal));
    }

    public static void speedGauge(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.SPEED_GAUGE);
        drive(scene, util, AllBlocks.FLYWHEEL.getDefaultState(),
            AllBlocks.SPEEDOMETER.getDefaultState()
                .setValue(GaugeBlock.AXIS_ALONG_FIRST_COORDINATE, true)
                .setValue(GaugeBlock.FACING, Direction.UP));
        block(scene, util, OUTPUT.north(), Blocks.COMPARATOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        target(scene, util.select().position(INPUT), 128);
        scene.effects().indicateSuccess(OUTPUT);
        PonderLesson.SPEED_GAUGE.show(scene, util, OUTPUT, 0);

        scene.overlay().showControls(util.vector().blockSurface(OUTPUT, Direction.NORTH), Pointing.RIGHT, 40)
            .rightClick().withItem(AllItems.WRENCH.asStack());
        scene.world().modifyBlockEntity(OUTPUT, SpeedGaugeBlockEntity.class, gauge -> {
            if (gauge.getBehaviour(SpeedGaugeRangeBehaviour.TYPE) instanceof SpeedGaugeRangeBehaviour range)
                range.setValue(0);
        });
        scene.idle(20);
        PonderLesson.SPEED_GAUGE.show(scene, util, OUTPUT, 1, PonderPalette.BLUE);

        scene.effects().indicateRedstone(OUTPUT.north());
        scene.overlay().showControls(util.vector().topOf(OUTPUT), Pointing.DOWN, 40)
            .withItem(AllItems.GOGGLES.asStack());
        PonderLesson.SPEED_GAUGE.show(scene, util, OUTPUT.north(), 2, PonderPalette.GREEN);
        scene.markAsFinished();
    }

    private static void pipes(CreateSceneBuilder scene, SceneBuildingUtil util) {
        block(scene, util, INPUT, AllBlocks.FLUID_TANK.getDefaultState());
        block(scene, util, OUTPUT, AllBlocks.FLUID_TANK.getDefaultState());
        block(scene, util, new BlockPos(1, 1, 2), axis(AllBlocks.GLASS_FLUID_PIPE.getDefaultState(), Axis.X));
        block(scene, util, CONTROL, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
        block(scene, util, new BlockPos(3, 1, 2), axis(AllBlocks.GLASS_FLUID_PIPE.getDefaultState(), Axis.X));
        fill(scene, INPUT, 6000); fill(scene, OUTPUT, 1000);
        block(scene, util, CONTROL.south(), axis(AllBlocks.COGWHEEL.getDefaultState(), Axis.X));
        block(scene, util, CONTROL.south().west(), motor(Direction.EAST));
        scene.world().setKineticSpeed(util.select().position(CONTROL.south().west()), -64);
        scene.idle(15);
    }

    private static void fill(CreateSceneBuilder scene, BlockPos pos, int amount) {
        scene.world().modifyBlockEntity(pos, FluidTankBlockEntity.class,
            tank -> tank.getTankInventory().setFluid(new FluidStack(Fluids.WATER, amount)));
    }

    private static void flow(CreateSceneBuilder scene, SceneBuildingUtil util, boolean forward) {
        for (int i = 0; i < 4; i++) {
            int x = forward ? i : 4 - i;
            scene.overlay().showBigLine(PonderPalette.BLUE, util.vector().centerOf(x, 1, 2),
                util.vector().centerOf(x + (forward ? 1 : -1), 1, 2), 16);
            scene.idle(8);
        }
        fill(scene, INPUT, forward ? 4500 : 6000);
        fill(scene, OUTPUT, forward ? 2500 : 1000);
    }

    public static void pumpDirection(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.PUMP_DIRECTION);
        pipes(scene, util);
        flow(scene, util, true);
        PonderLesson.PUMP_DIRECTION.show(scene, util, CONTROL, 0);

        target(scene, util.select().position(CONTROL.south().west()), 64);
        flow(scene, util, false);
        PonderLesson.PUMP_DIRECTION.show(scene, util, CONTROL, 1, PonderPalette.MEDIUM);

        scene.overlay().showControls(util.vector().topOf(CONTROL), Pointing.DOWN, 35)
            .rightClick().withItem(AllItems.WRENCH.asStack());
        scene.world().modifyBlock(CONTROL, s -> s.setValue(PumpBlock.FACING, Direction.WEST), true);
        flow(scene, util, true);
        PonderLesson.PUMP_DIRECTION.show(scene, util, CONTROL, 2, PonderPalette.GREEN);

        target(scene, util.select().position(CONTROL.south().west()), 0);
        PonderLesson.PUMP_DIRECTION.show(scene, util, INPUT, 3);
        scene.markAsFinished();
    }

    public static void pumpNetwork(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.PUMP_NETWORK);
        pipes(scene, util);
        flow(scene, util, true);
        PonderLesson.PUMP_NETWORK.show(scene, util, CONTROL, 0);

        BlockPos pipeEast = new BlockPos(3, 1, 2);
        replaceBlock(scene, util, pipeEast, AllBlocks.FLUID_VALVE.getDefaultState()
            .setValue(FluidValveBlock.FACING, Direction.UP)
            .setValue(FluidValveBlock.AXIS_ALONG_FIRST_COORDINATE, false)
            .setValue(FluidValveBlock.ENABLED, false));
        scene.effects().indicateRedstone(pipeEast);
        scene.idle(20);
        PonderLesson.PUMP_NETWORK.show(scene, util, pipeEast, 1, PonderPalette.RED);

        replaceBlock(scene, util, pipeEast, AllBlocks.SMART_FLUID_PIPE.getDefaultState()
            .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        flow(scene, util, true);
        PonderLesson.PUMP_NETWORK.show(scene, util, pipeEast, 2, PonderPalette.GREEN);
        scene.markAsFinished();
    }

    public static void bearing(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.BEARING);
        BlockPos motorPos = new BlockPos(2, 1, 2);
        BlockPos bearingPos = new BlockPos(2, 2, 2);
        block(scene, util, motorPos, motor(Direction.UP));
        block(scene, util, bearingPos, AllBlocks.MECHANICAL_BEARING.getDefaultState()
            .setValue(BlockStateProperties.FACING, Direction.UP));

        Selection armSel = util.select().fromTo(1, 3, 2, 3, 3, 2);
        scene.world().setBlocks(armSel, Blocks.OAK_PLANKS.defaultBlockState(), false);
        ElementLink<WorldSectionElement> arm = scene.world().showIndependentSection(armSel, Direction.DOWN);
        scene.world().configureCenterOfRotation(arm, util.vector().centerOf(bearingPos));
        scene.effects().superGlue(new BlockPos(2, 3, 2), Direction.EAST, true);
        scene.effects().superGlue(new BlockPos(2, 3, 2), Direction.WEST, true);
        scene.idle(15);

        scene.overlay().showControls(util.vector().blockSurface(bearingPos, Direction.WEST), Pointing.LEFT, 40)
            .rightClick();
        coupleSection(scene, util, bearingPos, arm,
            Set.of(new ContraptionGeometry.Cell(-1, 0, 0), new ContraptionGeometry.Cell(0, 0, 0),
                new ContraptionGeometry.Cell(1, 0, 0)), 420);
        target(scene, util.select().position(motorPos), 64);
        PonderLesson.BEARING.show(scene, util, bearingPos, 0);

        PonderLesson.BEARING.show(scene, util, bearingPos.above(), 1, PonderPalette.GREEN);

        target(scene, util.select().position(motorPos), 0);
        PonderLesson.BEARING.show(scene, util, bearingPos, 2, PonderPalette.BLUE);
        scene.markAsFinished();
    }

    public static void structureLoad(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.STRUCTURE_LOAD);
        BlockPos shaftAnchor = new BlockPos(1, 1, 2);
        block(scene, util, INPUT, motor(Direction.EAST));
        for (int x = 1; x <= 4; x++)
            block(scene, util, new BlockPos(x, 1, 2), axis(AllBlocks.GANTRY_SHAFT.getDefaultState(), Axis.X));
        connectGantryShafts(scene, util.select().fromTo(1, 1, 2, 4, 1, 2));

        BlockPos carriagePos = new BlockPos(1, 2, 2);
        BlockPos toolPos = new BlockPos(1, 2, 1);
        scene.world().setBlock(carriagePos, AllBlocks.GANTRY_CARRIAGE.getDefaultState()
            .setValue(BlockStateProperties.FACING, Direction.UP)
            .setValue(com.simibubi.create.content.contraptions.gantry.GantryCarriageBlock.AXIS_ALONG_FIRST_COORDINATE, false), false);
        scene.world().setBlock(toolPos, AllBlocks.MECHANICAL_HARVESTER.getDefaultState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), false);
        ElementLink<WorldSectionElement> rig = scene.world()
            .showIndependentSection(util.select().fromTo(1, 2, 1, 1, 2, 2), Direction.DOWN);

        BlockPos crop1 = new BlockPos(3, 2, 1), crop2 = new BlockPos(4, 2, 1);
        block(scene, util, crop1.below(), Blocks.FARMLAND.defaultBlockState());
        block(scene, util, crop2.below(), Blocks.FARMLAND.defaultBlockState());
        block(scene, util, crop1, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        block(scene, util, crop2, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        scene.idle(15);

        translateSection(scene, util, shaftAnchor, rig,
            Set.of(new ContraptionGeometry.Cell(0, 1, 0), new ContraptionGeometry.Cell(0, 1, -1)),
            Direction.EAST, 320);
        harvestAlongSection(scene, rig, shaftAnchor, toolPos, Direction.EAST, 320);
        scene.world().setKineticSpeed(util.select().position(INPUT), 6);
        PonderLesson.STRUCTURE_LOAD.show(scene, util, carriagePos, 0);

        PonderLesson.STRUCTURE_LOAD.show(scene, util, crop1, 1, PonderPalette.GREEN);

        scene.world().setKineticSpeed(util.select().position(INPUT), -6);
        PonderLesson.STRUCTURE_LOAD.show(scene, util, crop2, 2, PonderPalette.BLUE);
        scene.world().setKineticSpeed(util.select().position(INPUT), 0);
        scene.markAsFinished();
    }

    public static void continuousWork(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.CONTINUOUS_WORK);
        BlockPos input = INPUT.above(2), control = CONTROL.above(2), output = OUTPUT.above(2);
        drive(scene, util, AllBlocks.FLYWHEEL.getDefaultState(), AllBlocks.MECHANICAL_PRESS.getDefaultState(), 3);
        block(scene, util, OUTPUT, AllBlocks.DEPOT.getDefaultState());
        scene.world().createItemOnBeltLike(OUTPUT, Direction.UP, new ItemStack(Items.COPPER_INGOT));
        scene.world().modifyBlockEntity(output, MechanicalPressBlockEntity.class,
            press -> press.getPressingBehaviour().start(PressingBehaviour.Mode.BELT));
        target(scene, util.select().position(input), 64);
        PonderLesson.CONTINUOUS_WORK.show(scene, util, output, 0);

        scene.world().modifyBlockEntity(output, MechanicalPressBlockEntity.class,
            press -> press.getPressingBehaviour().start(PressingBehaviour.Mode.BELT));
        target(scene, util.select().position(input), 16);
        target(scene, util.select().position(input), 0);
        PonderLesson.CONTINUOUS_WORK.show(scene, util, output, 1, PonderPalette.MEDIUM);

        scene.overlay().showControls(util.vector().topOf(OUTPUT), Pointing.DOWN, 40)
            .withItem(AllItems.COPPER_SHEET.asStack());
        scene.effects().indicateSuccess(OUTPUT);
        scene.world().removeItemsFromBelt(OUTPUT);
        scene.world().createItemOnBeltLike(OUTPUT, Direction.UP, AllItems.COPPER_SHEET.asStack());
        PonderLesson.CONTINUOUS_WORK.show(scene, util, control, 2, PonderPalette.GREEN);
        scene.markAsFinished();
    }

    public static void backtank(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.BACKTANK);
        BlockPos copperTank = new BlockPos(1, 1, 2), copperMotor = new BlockPos(1, 2, 2);
        BlockPos netheriteTank = new BlockPos(3, 1, 2), netheriteMotor = new BlockPos(3, 2, 2);
        block(scene, util, copperTank, AllBlocks.COPPER_BACKTANK.getDefaultState());
        block(scene, util, copperMotor, motor(Direction.DOWN));
        block(scene, util, netheriteTank, AllBlocks.NETHERITE_BACKTANK.getDefaultState());
        block(scene, util, netheriteMotor, motor(Direction.DOWN));

        Selection motors = util.select().position(copperMotor).add(util.select().position(netheriteMotor));
        target(scene, motors, 128);
        PonderLesson.BACKTANK.show(scene, util, copperTank, 0);

        target(scene, motors, 32);
        scene.overlay().showControls(util.vector().topOf(copperTank), Pointing.DOWN, 40)
            .withItem(AllItems.COPPER_BACKTANK.asStack());
        PonderLesson.BACKTANK.show(scene, util, netheriteTank, 1, PonderPalette.BLUE);

        scene.effects().indicateSuccess(copperTank);
        scene.effects().indicateSuccess(netheriteTank);
        scene.world().modifyBlockEntity(copperTank, com.simibubi.create.content.equipment.armor.BacktankBlockEntity.class,
            tank -> tank.setAirLevel(com.simibubi.create.content.equipment.armor.BacktankUtil.maxAir(0)));
        scene.world().modifyBlock(netheriteTank, s -> s.setValue(BlockStateProperties.WATERLOGGED, true), false);
        PonderLesson.BACKTANK.show(scene, util, copperTank, 2, PonderPalette.GREEN);
        scene.markAsFinished();
    }

    public static void ejector(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.EJECTOR_WORK);
        BlockPos motorPos = new BlockPos(4, 1, 3);
        block(scene, util, INPUT, AllBlocks.DEPOT.getDefaultState());
        block(scene, util, CONTROL, AllBlocks.DEPOT.getDefaultState());
        block(scene, util, OUTPUT, AllBlocks.WEIGHTED_EJECTOR.getDefaultState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        block(scene, util, motorPos, motor(Direction.NORTH));
        scene.world().modifyBlockEntity(OUTPUT, com.simibubi.create.content.logistics.depot.EjectorBlockEntity.class,
            ejector -> ejector.setTarget(4, 0));

        target(scene, util.select().position(motorPos), 64);
        PonderLesson.EJECTOR_WORK.show(scene, util, OUTPUT, 0);

        target(scene, util.select().position(motorPos), 0);
        ItemStack stack = new ItemStack(Items.COPPER_INGOT);
        scene.overlay().showControls(util.vector().topOf(OUTPUT), Pointing.DOWN, 35).withItem(stack);
        scene.effects().indicateSuccess(OUTPUT);
        scene.world().createItemOnBeltLike(OUTPUT, Direction.EAST, stack);
        PonderLesson.EJECTOR_WORK.show(scene, util, OUTPUT, 1, PonderPalette.GREEN);

        scene.overlay().showOutline(PonderPalette.OUTPUT, new Object(), util.select().position(CONTROL), 60);
        scene.world().modifyBlockEntity(OUTPUT, com.simibubi.create.content.logistics.depot.EjectorBlockEntity.class,
            ejector -> ejector.setTarget(2, 0));
        target(scene, util.select().position(motorPos), 64);
        PonderLesson.EJECTOR_WORK.show(scene, util, CONTROL, 2, PonderPalette.BLUE);
        scene.markAsFinished();
    }

    public static void fan(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.FAN_WORK);
        scene.configureBasePlate(0, 0, 6);
        scene.scaleSceneView(1.1f);
        BlockPos motorPos = new BlockPos(2, 2, 5);
        BlockPos fanPos = new BlockPos(2, 2, 4);
        BlockPos lavaPos = new BlockPos(2, 2, 2);
        BlockPos depotPos = new BlockPos(2, 1, 1);
        block(scene, util, motorPos.below(), Blocks.ANDESITE.defaultBlockState());
        block(scene, util, fanPos.below(), Blocks.ANDESITE.defaultBlockState());
        block(scene, util, motorPos, motor(Direction.NORTH));
        block(scene, util, fanPos, AllBlocks.ENCASED_FAN.getDefaultState()
            .setValue(BlockStateProperties.FACING, Direction.NORTH));
        block(scene, util, lavaPos, Blocks.LAVA.defaultBlockState());
        block(scene, util, lavaPos.east(), Blocks.GLASS.defaultBlockState());
        block(scene, util, lavaPos.west(), Blocks.GLASS.defaultBlockState());
        block(scene, util, lavaPos.below(), Blocks.GLASS.defaultBlockState());
        block(scene, util, depotPos.north().above(), Blocks.GLASS.defaultBlockState());
        block(scene, util, depotPos, AllBlocks.DEPOT.getDefaultState());
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).enableFanProcessing(fanPos));
        scene.idle(20);

        target(scene, util.select().position(motorPos), -64);
        scene.world().createItemOnBeltLike(depotPos, Direction.SOUTH, new ItemStack(Items.GOLD_ORE));
        scene.effects().rotationDirectionIndicator(fanPos);
        PonderLesson.FAN_WORK.caption(scene, 0, PonderPalette.WHITE);

        target(scene, util.select().position(motorPos), -128);
        scene.overlay().showOutline(PonderPalette.GREEN, new Object(), util.select().position(depotPos), 90);
        PonderLesson.FAN_WORK.caption(scene, 1, PonderPalette.GREEN);

        target(scene, util.select().position(motorPos), 0);
        scene.effects().rotationSpeedIndicator(fanPos);
        PonderLesson.FAN_WORK.caption(scene, 2, PonderPalette.MEDIUM);
        scene.markAsFinished();
    }

    public static void sequencer(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.SEQUENCER);
        drive(scene, util, AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState(), AllBlocks.FLYWHEEL.getDefaultState());
        block(scene, util, CONTROL.north(), Blocks.LEVER.defaultBlockState());
        scene.overlay().showControls(util.vector().topOf(CONTROL), Pointing.DOWN, 35).rightClick();
        scene.world().modifyBlockEntity(CONTROL,
            com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity.class, sequencer -> {
                var program = sequencer.getInstructions();
                program.clear();
                program.add(new com.simibubi.create.content.kinetics.transmission.sequencer.Instruction(
                    com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions.TURN_ANGLE, 360));
                program.add(new com.simibubi.create.content.kinetics.transmission.sequencer.Instruction(
                    com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions.TURN_ANGLE, 360));
                program.add(new com.simibubi.create.content.kinetics.transmission.sequencer.Instruction(
                    com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions.TURN_ANGLE,
                    com.simibubi.create.content.kinetics.transmission.sequencer.InstructionSpeedModifiers.BACK, 180));
                program.add(new com.simibubi.create.content.kinetics.transmission.sequencer.Instruction(
                    com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions.END));
            });
        target(scene, util.select().position(INPUT), 64);

        scene.world().toggleRedstonePower(util.select().position(CONTROL.north()));
        scene.effects().indicateRedstone(CONTROL.north());
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).sequence(s.getWorld(), CONTROL));
        PonderLesson.SEQUENCER.show(scene, util, CONTROL, 0);

        scene.idle(60);
        scene.effects().indicateSuccess(OUTPUT);
        PonderLesson.SEQUENCER.show(scene, util, OUTPUT, 1, PonderPalette.GREEN);

        scene.world().toggleRedstonePower(util.select().position(CONTROL.north()));
        scene.idle(5);
        scene.world().toggleRedstonePower(util.select().position(CONTROL.north()));
        scene.effects().indicateRedstone(CONTROL.north());
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).sequence(s.getWorld(), CONTROL));
        PonderLesson.SEQUENCER.show(scene, util, CONTROL.north(), 2, PonderPalette.BLUE);
        scene.markAsFinished();
    }

    public static void steam(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.STEAM_POWER);
        boiler(scene, util, new BlockPos(3, 2, 2), 2, 2, 2);
        BlockPos enginePos = new BlockPos(2, 3, 2);
        BlockPos shaftPos = new BlockPos(0, 3, 2);
        for (int z = 2; z <= 3; z++) {
            BlockPos engine = new BlockPos(2, 3, z), shaft = new BlockPos(0, 3, z);
            block(scene, util, engine, AllBlocks.STEAM_ENGINE.getDefaultState()
                .setValue(SteamEngineBlock.FACE, AttachFace.WALL)
                .setValue(SteamEngineBlock.FACING, Direction.WEST));
            block(scene, util, shaft, AllBlocks.POWERED_SHAFT.getDefaultState().setValue(PoweredShaftBlock.AXIS, Axis.Z));
            scene.world().modifyBlockEntity(shaft, PoweredShaftBlockEntity.class, be -> {
                be.initialTicks = 0;
                be.enginePos = shaft.subtract(engine);
                be.movementDirection = 1;
                be.capacityKey = AllBlocks.STEAM_ENGINE.get();
            });
            scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).useSteamBoiler(s.getWorld(), shaft));
        }
        block(scene, util, new BlockPos(0, 3, 4), axis(AllBlocks.FLYWHEEL.getDefaultState(), Axis.Z));
        BlockPos water = new BlockPos(4, 2, 0), pump = new BlockPos(4, 2, 1);
        block(scene, util, water, AllBlocks.FLUID_TANK.getDefaultState());
        fill(scene, water, 6000);
        block(scene, util, pump, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.SOUTH));
        block(scene, util, pump.west(), axis(AllBlocks.COGWHEEL.getDefaultState(), Axis.Z));
        block(scene, util, pump.west().north(), motor(Direction.SOUTH));
        scene.world().setKineticSpeed(util.select().position(pump.west().north()), -64);
        scene.idle(40);
        scene.effects().rotationSpeedIndicator(shaftPos);
        PonderLesson.STEAM_POWER.show(scene, util, new BlockPos(3, 3, 2), 0);

        PonderLesson.STEAM_POWER.show(scene, util, enginePos, 1, PonderPalette.MEDIUM);
        scene.idle(65);

        scene.world().setBlocks(util.select().fromTo(3, 1, 2, 4, 1, 3),
            AllBlocks.BLAZE_BURNER.getDefaultState().setValue(
                com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HEAT_LEVEL,
                com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel.SMOULDERING), false);
        scene.world().modifyBlockEntity(new BlockPos(3, 2, 2), FluidTankBlockEntity.class,
            tank -> {
                tank.boiler.activeHeat = 0; tank.boiler.passiveHeat = false;
                BoilerAdmission.supply(tank.boiler, 0, 0);
                tank.boiler.read(tank.boiler.write(), tank.getTotalTankSize());
            });
        scene.idle(25);
        PonderLesson.STEAM_POWER.show(scene, util, shaftPos, 2, PonderPalette.BLUE);
        scene.markAsFinished();
    }

    private static void boiler(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos origin, int width, int height) {
        boiler(scene, util, origin, width, height, 1);
    }

    private static void boiler(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos origin, int width, int height, int engines) {
        for (int x = 0; x < width; x++) for (int z = 0; z < width; z++)
            block(scene, util, origin.offset(x, -1, z), AllBlocks.BLAZE_BURNER.getDefaultState()
                .setValue(com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HEAT_LEVEL,
                    com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel.KINDLED));
        boilerTank(scene, util, origin, width, height, engines, width * width);
    }

    private static void boilerTank(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos origin,
        int width, int height, int engines, int heat) {
        for (int x = 0; x < width; x++) for (int z = 0; z < width; z++) for (int y = 0; y < height; y++) {
            BlockPos pos = origin.offset(x, y, z);
            block(scene, util, pos, AllBlocks.FLUID_TANK.getDefaultState()
                .setValue(com.simibubi.create.content.fluids.tank.FluidTankBlock.TOP, y == height - 1)
                .setValue(com.simibubi.create.content.fluids.tank.FluidTankBlock.BOTTOM, y == 0)
                .setValue(com.simibubi.create.content.fluids.tank.FluidTankBlock.SHAPE, engines > 0
                    ? com.simibubi.create.content.fluids.tank.FluidTankBlock.Shape.PLAIN
                    : com.simibubi.create.content.fluids.tank.FluidTankBlock.Shape.WINDOW));
            scene.world().modifyBlockEntity(pos, FluidTankBlockEntity.class, tank -> {
                if (!pos.equals(origin)) tank.setController(origin);
                else {
                    tank.setWidth(width); tank.setHeight(height);
                    tank.applyFluidTankSize(width * width * height);
                    tank.getTankInventory().setFluid(new FluidStack(Fluids.WATER, width * width * height * 4000));
                    tank.boiler.activeHeat = heat;
                    tank.boiler.attachedEngines = engines;
                    BoilerAdmission.supply(tank.boiler, 10, 10);
                    tank.boiler.read(tank.boiler.write(), tank.getTotalTankSize());
                }
            });
        }
        scene.world().modifyBlockEntity(origin, FluidTankBlockEntity.class, tank -> tank.setWindows(engines == 0));
    }

    public static void minecart(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.MINECART_DYNAMICS);
        for (int x = 0; x <= 4; x++) {
            BlockState rail = x == 2
                ? AllBlocks.CART_ASSEMBLER.getDefaultState()
                    .setValue(CartAssemblerBlock.RAIL_SHAPE, RailShape.EAST_WEST)
                    .setValue(CartAssemblerBlock.RAIL_TYPE, CartAssembleRailType.CONTROLLER_RAIL)
                : AllBlocks.CONTROLLER_RAIL.getDefaultState()
                    .setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, RailShape.EAST_WEST)
                    .setValue(BlockStateProperties.POWER, 15);
            block(scene, util, new BlockPos(x, 1, 2), rail);
        }
        ElementLink<MinecartElement> cart = scene.special()
            .createCart(util.vector().topOf(4, 0, 2), 0, Minecart::new);
        ElementLink<MinecartElement> trailing = scene.special()
            .createCart(util.vector().topOf(3, 0, 2), 0, MinecartChest::new);
        Selection loadSel = util.select().fromTo(3, 2, 2, 4, 2, 2);
        scene.world().setBlocks(loadSel, AllBlocks.ANDESITE_CASING.getDefaultState(), false);
        ElementLink<WorldSectionElement> contraption = scene.world().showIndependentSection(loadSel, Direction.DOWN);
        scene.idle(15);

        moveCarts(scene, cart, trailing, contraption, -1, 40);
        scene.idle(40);
        PonderLesson.MINECART_DYNAMICS.show(scene, util, CONTROL, 0);

        moveCarts(scene, cart, trailing, contraption, -2, 60);
        scene.idle(60);
        PonderLesson.MINECART_DYNAMICS.show(scene, util, new BlockPos(1, 1, 2), 1, PonderPalette.MEDIUM);

        PonderLesson.MINECART_DYNAMICS.show(scene, util, INPUT, 2, PonderPalette.GREEN);
        scene.markAsFinished();
    }

    public static void crusher(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.CRUSHER_WORK);
        BlockPos leftWheel = new BlockPos(1, 2, 2), rightWheel = new BlockPos(3, 2, 2);
        BlockPos leftMotor = new BlockPos(1, 2, 3), rightMotor = new BlockPos(3, 2, 3);
        BlockPos gap = new BlockPos(2, 2, 2);
        block(scene, util, leftWheel, axis(AllBlocks.CRUSHING_WHEEL.getDefaultState(), Axis.Z));
        block(scene, util, rightWheel, axis(AllBlocks.CRUSHING_WHEEL.getDefaultState(), Axis.Z));
        block(scene, util, leftMotor, motor(Direction.NORTH));
        block(scene, util, rightMotor, motor(Direction.NORTH));

        scene.world().setKineticSpeed(util.select().position(leftMotor), -32);
        target(scene, util.select().position(rightMotor), 32);
        scene.effects().rotationDirectionIndicator(leftWheel);
        scene.effects().rotationDirectionIndicator(rightWheel);
        PonderLesson.CRUSHER_WORK.show(scene, util, gap, 0);

        target(scene, util.select().position(rightMotor), -16);
        PonderLesson.CRUSHER_WORK.show(scene, util, rightWheel, 1, PonderPalette.MEDIUM);

        target(scene, util.select().position(rightMotor), 32);
        scene.overlay().showControls(util.vector().topOf(gap), Pointing.DOWN, 35)
            .withItem(new ItemStack(Items.GOLD_ORE, 64));
        scene.effects().indicateSuccess(gap);
        PonderLesson.CRUSHER_WORK.show(scene, util, gap, 2, PonderPalette.GREEN);
        scene.markAsFinished();
    }

    public static void docking(SceneBuilder builder, SceneBuildingUtil util, boolean fluid) {
        var scene = start(builder, util, PonderLesson.PORTABLE_DOCKING);
        BlockState interfaceState = (fluid ? AllBlocks.PORTABLE_FLUID_INTERFACE : AllBlocks.PORTABLE_STORAGE_INTERFACE)
            .getDefaultState();
        BlockPos stationPos = new BlockPos(0, 3, 2);
        BlockPos shaftAnchor = new BlockPos(3, 1, 2);
        BlockPos carriagePos = new BlockPos(3, 2, 2);
        BlockPos movingInterface = new BlockPos(3, 3, 2);
        BlockPos storagePos = new BlockPos(4, 3, 2);

        block(scene, util, INPUT, AllBlocks.ANDESITE_CASING.getDefaultState());
        block(scene, util, stationPos.below(), AllBlocks.ANDESITE_CASING.getDefaultState());
        block(scene, util, stationPos, interfaceState.setValue(BlockStateProperties.FACING, Direction.EAST));
        for (int x = 1; x <= 3; x++)
            block(scene, util, new BlockPos(x, 1, 2), axis(AllBlocks.GANTRY_SHAFT.getDefaultState(), Axis.X));
        connectGantryShafts(scene, util.select().fromTo(1, 1, 2, 3, 1, 2));
        block(scene, util, OUTPUT, motor(Direction.WEST));

        scene.world().setBlock(storagePos,
            fluid ? AllBlocks.FLUID_TANK.getDefaultState() : Blocks.BARREL.defaultBlockState(), false);
        scene.world().setBlock(movingInterface, interfaceState.setValue(BlockStateProperties.FACING, Direction.WEST), false);
        scene.world().setBlock(carriagePos, AllBlocks.GANTRY_CARRIAGE.getDefaultState()
            .setValue(BlockStateProperties.FACING, Direction.UP)
            .setValue(com.simibubi.create.content.contraptions.gantry.GantryCarriageBlock.AXIS_ALONG_FIRST_COORDINATE, false), false);
        ElementLink<WorldSectionElement> contraption = scene.world()
            .showIndependentSection(util.select().fromTo(carriagePos, storagePos), Direction.DOWN);
        scene.idle(10);

        var cells = Set.of(new ContraptionGeometry.Cell(0, 1, 0), new ContraptionGeometry.Cell(0, 2, 0),
            new ContraptionGeometry.Cell(1, 2, 0));
        translateSection(scene, util, shaftAnchor, contraption, cells, Direction.WEST, 410);
        scene.world().setKineticSpeed(util.select().position(OUTPUT), 16);
        scene.idle(32);
        attachmentLoad(scene, shaftAnchor, cells, Direction.WEST, 1024);
        scene.world().setKineticSpeed(util.select().position(OUTPUT), 0);
        scene.idle(15);

        Selection both = util.select().position(stationPos).add(util.select().position(movingInterface));
        float[] gap = {1};
        scene.addInstruction(s -> gap[0] = (float) (movingInterface.getX()
            + s.resolve(contraption).getAnimatedOffset().x - stationPos.getX() - 1));
        scene.world().modifyBlockEntityNBT(both, PortableStorageInterfaceBlockEntity.class, nbt -> {
            nbt.putFloat("Distance", gap[0]);
            nbt.putFloat("Timer", 12);
        });
        scene.effects().indicateSuccess(stationPos);
        PonderLesson.PORTABLE_DOCKING.show(scene, util, stationPos, 0);

        scene.world().modifyBlockEntityNBT(both, PortableStorageInterfaceBlockEntity.class, nbt -> nbt.putFloat("Timer", 2));
        scene.idle(15);
        attachmentLoad(scene, shaftAnchor, cells, Direction.WEST, 0);
        scene.world().setKineticSpeed(util.select().position(OUTPUT), -5);
        PonderLesson.PORTABLE_DOCKING.show(scene, util, new BlockPos(2, 2, 2), 1, PonderPalette.GREEN);

        attachmentLoad(scene, shaftAnchor, cells, Direction.WEST, 256);
        scene.world().setKineticSpeed(util.select().position(OUTPUT), 0);
        scene.overlay().showControls(util.vector().blockSurface(stationPos, Direction.NORTH), Pointing.RIGHT, 40)
            .rightClick().withItem(AllItems.WRENCH.asStack());
        PonderLesson.PORTABLE_DOCKING.show(scene, util, stationPos, 2, PonderPalette.BLUE);
        scene.markAsFinished();
    }

    private static CreateSceneBuilder trainStart(SceneBuilder builder, SceneBuildingUtil util, PonderLesson lesson) {
        var scene = new CreateSceneBuilder(builder);
        lesson.title(scene);
        scene.addInstruction(s -> {
            var bounds = s.getWorld().getBounds();
            s.getWorld().setBounds(new net.minecraft.world.level.levelgen.structure.BoundingBox(
                bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), Math.max(5, bounds.maxY()), bounds.maxZ()));
        });
        scene.configureBasePlate(1, 0, 9);
        scene.scaleSceneView(.75f);
        scene.setSceneOffsetY(-1);
        scene.showBasePlate();
        scene.world().showSection(util.select().layer(1), Direction.DOWN);
        // Keep the native track and station, but build a chassis sized for its actual equipment.
        // Clear the hidden template before revealing anything; its side skirts obscure the upgrade.
        scene.world().setBlocks(util.select().fromTo(0, 2, 0, 10, 7, 8), Blocks.AIR.defaultBlockState(), false);
        var deck = Blocks.POLISHED_DEEPSLATE_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP);
        for (int x = 1; x <= 8; x++) for (int z = 3; z <= 5; z++)
            if (x != TRAIN_BOGEY.getX()) scene.world().setBlock(new BlockPos(x, 2, z), deck, false);
        for (var pos : new BlockPos[]{TRAIN_BOGEY, TRAIN_REAR_BOGEY})
            scene.world().setBlock(pos, axis(AllBlocks.SMALL_BOGEY.getDefaultState(), Axis.X), false);
        scene.world().setBlock(TRAIN_CONTROLS, AllBlocks.TRAIN_CONTROLS.getDefaultState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST), false);
        scene.world().setBlock(TRAIN_LEVER, ModContent.GEAR_LEVER.getDefaultState(), false);
        scene.world().showSection(util.select().fromTo(1, 2, 3, 8, 2, 5), Direction.DOWN);
        scene.world().showSection(util.select().position(TRAIN_CONTROLS), Direction.DOWN);
        scene.world().showSection(util.select().position(TRAIN_LEVER), Direction.DOWN);
        scene.idle(20);
        return scene;
    }

    private static void upgrade(CreateSceneBuilder scene) {
        scene.world().modifyBlockEntity(TRAIN_BOGEY, AbstractBogeyBlockEntity.class, SteamBogey::install);
    }

    private static void trainBoiler(CreateSceneBuilder scene, SceneBuildingUtil util) {
        // A broad, low boiler sits above the rear bogey; both heaters remain visible below it.
        for (int z : new int[]{3, 5}) block(scene, util, new BlockPos(7, 3, z),
            AllBlocks.BLAZE_BURNER.getDefaultState().setValue(
                com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HEAT_LEVEL,
                com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel.KINDLED));
        boilerTank(scene, util, TRAIN_BOILER, 3, 2, 0, 2);
        block(scene, util, TRAIN_FUEL, Blocks.BARREL.defaultBlockState());
        scene.world().modifyBlockEntity(TRAIN_FUEL, net.minecraft.world.level.block.entity.BarrelBlockEntity.class,
            storage -> storage.setItem(0, new ItemStack(Items.COAL, 64)));
        // Mounted boilers are excluded from ordinary fluid cargo, just as vaults are excluded from fuel.
        block(scene, util, TRAIN_WATER, AllBlocks.FLUID_TANK.getDefaultState());
        fill(scene, TRAIN_WATER, 6000);
        scene.idle(15);
    }

    private static void connectTrainBoiler(CreateSceneBuilder scene, BlockPos bogeyPos, BlockPos boilerPos) {
        scene.addInstruction(s -> {
            if (!(s.getWorld().getBlockEntity(boilerPos) instanceof FluidTankBlockEntity tank)) return;
            com.diliupengyun.differential_create.trains.SteamHoseLinks.connect(s.getWorld().getBlockEntity(bogeyPos), tank,
                new com.diliupengyun.differential_create.trains.SteamHosePort(Direction.WEST,
                    new net.minecraft.world.phys.Vec3(0, .5, .5)));
            tank.boiler.read(tank.boiler.write(), tank.getTotalTankSize());
            s.forEach(WorldSectionElement.class, WorldSectionElement::queueRedraw);
            s.addElement(new PonderHoseElement(bogeyPos));
        });
    }

    public static void trainPower(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = trainStart(builder, util, PonderLesson.TRAIN_POWER);
        BlockPos bogeyPos = TRAIN_BOGEY;
        BlockPos boilerPos = TRAIN_BOILER;
        trainBoiler(scene, util);

        scene.overlay().showControls(util.vector().topOf(bogeyPos), Pointing.DOWN, 40)
            .rightClick().withItem(AllBlocks.STEAM_ENGINE.asStack());
        upgrade(scene);
        scene.effects().indicateSuccess(bogeyPos);
        scene.idle(40);
        scene.overlay().showOutline(PonderPalette.WHITE, new Object(), util.select().position(bogeyPos), 90);
        PonderLesson.TRAIN_POWER.caption(scene, 0, PonderPalette.WHITE);

        ItemStack hose = ModContent.STEAM_HOSE.asStack();
        scene.overlay().showControls(util.vector().blockSurface(boilerPos, Direction.WEST), Pointing.RIGHT, 30)
            .rightClick().withItem(hose);
        scene.idle(15);
        scene.overlay().showControls(util.vector().topOf(bogeyPos), Pointing.DOWN, 30)
            .rightClick().withItem(hose);
        scene.overlay().showLine(PonderPalette.GREEN, util.vector().centerOf(boilerPos),
            util.vector().centerOf(bogeyPos), 60);
        connectTrainBoiler(scene, bogeyPos, boilerPos);
        scene.idle(30);
        scene.overlay().showOutline(PonderPalette.GREEN, new Object(), util.select().fromTo(6, 4, 3, 8, 5, 5), 90);
        PonderLesson.TRAIN_POWER.caption(scene, 1, PonderPalette.GREEN);

        scene.overlay().showControls(util.vector().topOf(bogeyPos), Pointing.DOWN, 40)
            .rightClick().withItem(ModContent.CONDENSER_PIPE.asStack());
        scene.world().modifyBlockEntity(bogeyPos, AbstractBogeyBlockEntity.class, SteamBogey::installCondenser);
        scene.idle(40);
        scene.overlay().showOutline(PonderPalette.BLUE, new Object(), util.select().position(bogeyPos), 90);
        PonderLesson.TRAIN_POWER.caption(scene, 2, PonderPalette.BLUE);

        scene.overlay().showOutline(PonderPalette.MEDIUM, new Object(), util.select().fromTo(5, 3, 3, 8, 5, 5), 90);
        PonderLesson.TRAIN_POWER.caption(scene, 3, PonderPalette.MEDIUM);
        scene.markAsFinished();
    }

    public static void trainDriving(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = trainStart(builder, util, PonderLesson.TRAIN_DRIVING);
        trainBoiler(scene, util);
        upgrade(scene);
        BlockPos controlsPos = TRAIN_CONTROLS;
        BlockPos leverPos = TRAIN_LEVER;
        BlockPos stationPos = new BlockPos(4, 1, 1);
        connectTrainBoiler(scene, TRAIN_BOGEY, TRAIN_BOILER);

        ElementLink<WorldSectionElement> train = scene.world()
            .makeSectionIndependent(util.select().fromTo(1, 2, 3, 8, 5, 5));
        scene.overlay().showControls(util.vector().topOf(controlsPos), Pointing.DOWN, 40).rightClick();
        scene.world().toggleControls(controlsPos);
        scene.world().modifyBlock(leverPos, s -> s.setValue(GearLeverBlock.GEAR, 2), false);
        scene.idle(40);
        scene.overlay().showOutline(PonderPalette.WHITE, new Object(), util.select().position(controlsPos), 90);
        PonderLesson.TRAIN_DRIVING.caption(scene, 0, PonderPalette.WHITE);

        moveTrain(scene, train, 0, .02, 40);
        scene.idle(40);
        scene.world().modifyBlock(leverPos, s -> s.setValue(GearLeverBlock.GEAR, 0), false);
        moveTrain(scene, train, .02, .01, 100);
        PonderLesson.TRAIN_DRIVING.caption(scene, 1, PonderPalette.MEDIUM);

        scene.world().modifyBlock(leverPos, s -> s.setValue(GearLeverBlock.GEAR, 6), false);
        moveTrain(scene, train, .01, 0, 20);
        scene.idle(20);
        moveTrain(scene, train, 0, -.05, 40);
        scene.idle(40);
        moveTrain(scene, train, -.05, 0, 40);
        scene.world().animateTrainStation(stationPos, true);
        scene.overlay().showOutline(PonderPalette.GREEN, new Object(), util.select().position(stationPos), 90);
        PonderLesson.TRAIN_DRIVING.caption(scene, 2, PonderPalette.GREEN);
        scene.effects().indicateSuccess(stationPos);

        scene.overlay().showControls(util.vector().topOf(controlsPos), Pointing.DOWN, 40)
            .withItem(AllItems.SCHEDULE.asStack());
        scene.idle(40);
        PonderLesson.TRAIN_DRIVING.caption(scene, 3, PonderPalette.BLUE);
        scene.markAsFinished();
    }

    /** Scripted finite acceleration; wheels consume exactly the same distance as the carriage each tick. */
    private static void moveTrain(CreateSceneBuilder scene, ElementLink<WorldSectionElement> train,
        double fromSpeed, double toSpeed, int duration) {
        scene.addInstruction(new net.createmod.ponder.foundation.instruction.PonderInstruction() {
            private int elapsed;
            @Override public boolean isComplete() { return elapsed > duration; }
            @Override public void reset(net.createmod.ponder.foundation.PonderScene s) { elapsed = 0; }
            @Override public void tick(net.createmod.ponder.foundation.PonderScene s) {
                elapsed++;
                var section = s.resolve(train);
                if (section == null) return;
                double distance = elapsed > duration ? 0
                    : fromSpeed + (toSpeed - fromSpeed) * (elapsed - .5) / duration;
                section.setAnimatedOffset(section.getAnimatedOffset().add(distance, 0, 0), false);
                for (var bogey : new BlockPos[]{TRAIN_BOGEY, TRAIN_REAR_BOGEY})
                    if (s.getWorld().getBlockEntity(bogey) instanceof AbstractBogeyBlockEntity be)
                        be.animate((float) -distance);
            }
        });
    }

    /** Both carts and their cargo share a smooth start/stop path. */
    private static void moveCarts(CreateSceneBuilder scene, ElementLink<MinecartElement> first,
        ElementLink<MinecartElement> second, ElementLink<WorldSectionElement> cargo, double distance, int duration) {
        scene.addInstruction(new net.createmod.ponder.foundation.instruction.PonderInstruction() {
            private int elapsed;
            private double previous;
            @Override public boolean isComplete() { return elapsed >= duration; }
            @Override public void reset(net.createmod.ponder.foundation.PonderScene s) { elapsed = 0; previous = 0; }
            @Override public void tick(net.createmod.ponder.foundation.PonderScene s) {
                double t = ++elapsed / (double) duration;
                double progress = t * t * (3 - 2 * t);
                double step = distance * (progress - previous);
                previous = progress;
                for (var link : java.util.List.of(first, second)) {
                    var cart = s.resolve(link);
                    if (cart != null) cart.setPositionOffset(cart.getPositionOffset().add(step, 0, 0), false);
                }
                var section = s.resolve(cargo);
                if (section != null) section.setAnimatedOffset(section.getAnimatedOffset().add(step, 0, 0), false);
            }
        });
    }

    private static void attachmentLoad(CreateSceneBuilder scene, BlockPos actuator,
        Set<ContraptionGeometry.Cell> cells, Direction direction, double load) {
        int axisIndex = direction.getAxis().ordinal();
        scene.addInstruction(s -> {
            var shape = ContraptionGeometry.measure(cells);
            PonderKinetics.of(s.getWorld()).attachment(s.getWorld(), actuator,
                shape.linearInertia(), load, shape.linearDrag(axisIndex));
        });
    }

    /** Harvest when the moving tool's native active area reaches the crop, not at a caption deadline. */
    private static void harvestAlongSection(CreateSceneBuilder scene, ElementLink<WorldSectionElement> moving,
        BlockPos actuator, BlockPos tool, Direction facing, int duration) {
        scene.addInstruction(new net.createmod.ponder.foundation.instruction.PonderInstruction() {
            private int elapsed;
            private double previousDistance, progress;
            private BlockPos cutting;
            @Override public boolean isComplete() { return elapsed >= duration; }
            @Override public void reset(net.createmod.ponder.foundation.PonderScene s) {
                elapsed = 0; previousDistance = progress = 0; cutting = null;
            }
            @Override public void tick(net.createmod.ponder.foundation.PonderScene s) {
                elapsed++;
                var section = s.resolve(moving);
                if (section == null) return;
                var activeArea = net.minecraft.world.phys.Vec3.atCenterOf(tool).add(section.getAnimatedOffset())
                    .add(net.minecraft.world.phys.Vec3.atLowerCornerOf(facing.getNormal()).scale(.45));
                double distance = section.getAnimatedOffset().dot(net.minecraft.world.phys.Vec3.atLowerCornerOf(facing.getNormal()));
                double travelled = distance - previousDistance; previousDistance = distance;
                var crop = BlockPos.containing(activeArea);
                var state = s.getWorld().getBlockState(crop);
                double load = 0;
                if (travelled > 0 && state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) == 7 && elapsed < duration) {
                    if (!crop.equals(cutting)) { cutting = crop; progress = 0; }
                    else progress += travelled;
                    double stroke = com.diliupengyun.differential_create.kinetics.ContraptionActorWork.FEED_DISTANCE;
                    double rated = com.simibubi.create.api.stress.BlockStressValues.getImpact(AllBlocks.MECHANICAL_HARVESTER.get());
                    if (rated <= 0) rated = 2;
                    load = rated * com.diliupengyun.differential_create.kinetics.ContraptionActorWork.CYCLE_TRAVEL
                        / (1200 * stroke / (2 * Math.PI * ContraptionGeometry.LINEAR_RADIUS));
                    if (progress >= stroke) {
                        s.getWorld().addBlockDestroyEffects(crop, state);
                        s.getWorld().setBlock(crop, state.setValue(CropBlock.AGE, 0), 3);
                        s.forEach(WorldSectionElement.class, WorldSectionElement::queueRedraw);
                        cutting = null; load = 0;
                    }
                } else {
                    cutting = null; progress = 0;
                }
                PonderKinetics.of(s.getWorld()).setAttachmentLoad(actuator, load);
            }
        });
    }

    /** Bind a displayed linear section to a separately integrated mounted rotor. */
    private static void translateSection(CreateSceneBuilder scene, SceneBuildingUtil util,
        BlockPos actuator, ElementLink<WorldSectionElement> moving,
        Set<ContraptionGeometry.Cell> cells, Direction direction, int duration) {
        attachmentLoad(scene, actuator, cells, direction, 0);
        scene.addInstruction(new net.createmod.ponder.foundation.instruction.PonderInstruction() {
            private int elapsed;
            private Double baseAngle;
            @Override public boolean isComplete() { return elapsed >= duration; }
            @Override public void reset(net.createmod.ponder.foundation.PonderScene s) {
                elapsed = 0;
                baseAngle = null;
                var section = s.resolve(moving);
                if (section != null) section.setAnimatedOffset(util.vector().of(0, 0, 0), true);
            }
            @Override public void tick(net.createmod.ponder.foundation.PonderScene s) {
                elapsed++;
                if (!(s.getWorld().getBlockEntity(actuator) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity be)) return;
                var section = s.resolve(moving);
                if (section == null) return;
                Double angle = PonderKinetics.of(s.getWorld()).outputAngle(be, 1);
                double current = angle == null ? 0 : angle;
                if (baseAngle == null) baseAngle = current;
                double travel = Math.toRadians(current - baseAngle) * ContraptionGeometry.LINEAR_RADIUS;
                section.setAnimatedOffset(util.vector().of(
                    direction.getStepX() * travel,
                    direction.getStepY() * travel,
                    direction.getStepZ() * travel), false);
            }
        });
    }

    /** Bind a displayed section to a separately integrated mounted rotor. */
    private static void coupleSection(CreateSceneBuilder scene, SceneBuildingUtil util,
        BlockPos bearing, ElementLink<WorldSectionElement> moving,
        java.util.Set<com.diliupengyun.differential_create.kinetics.ContraptionGeometry.Cell> cells,
        int duration) {
        scene.addInstruction(s -> {
            var shape = com.diliupengyun.differential_create.kinetics.ContraptionGeometry.measure(cells);
            PonderKinetics.of(s.getWorld()).attachment(s.getWorld(), bearing,
                shape.rotationInertia()[1], 0, shape.rotationDrag()[1]);
        });
        scene.addInstruction(new net.createmod.ponder.foundation.instruction.PonderInstruction() {
            private int elapsed;
            @Override public boolean isComplete() { return elapsed >= duration; }
            @Override public void reset(net.createmod.ponder.foundation.PonderScene s) { elapsed = 0; }
            @Override public void tick(net.createmod.ponder.foundation.PonderScene s) {
                elapsed++;
                if (!(s.getWorld().getBlockEntity(bearing) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity be)) return;
                var section = s.resolve(moving);
                if (section != null) {
                    Double angle = PonderKinetics.of(s.getWorld()).outputAngle(be, 1);
                    section.setAnimatedRotation(util.vector().of(0, angle == null ? 0 : angle, 0), false);
                    if (be instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity bearingEntity)
                        bearingEntity.setAngle(angle == null ? 0 : angle.floatValue());
                }
            }
        });
    }
}
