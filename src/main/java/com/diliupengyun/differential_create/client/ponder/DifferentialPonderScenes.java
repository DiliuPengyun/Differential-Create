package com.diliupengyun.differential_create.client.ponder;

import java.util.Set;

import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.kinetics.ContraptionGeometry;
import com.diliupengyun.differential_create.kinetics.SpeedGaugeRangeBehaviour;
import com.diliupengyun.differential_create.trains.GearLeverBlock;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import com.simibubi.create.content.contraptions.mounted.CartAssembleRailType;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.gauge.GaugeBlock;
import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlock;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.element.MinecartElement;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.entity.Entity;
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
        // Tall machines need headroom at 16:9; low benches can use the closer view.
        scene.scaleSceneView(switch (lesson.id) {
            case "dynamics", "crusher_work", "steam_power" -> 0.9f;
            case "portable_docking" -> 1.1f;
            default -> 1.3f;
        });
        scene.world().setBlocks(util.select().fromTo(-1, 1, -1, 7, 6, 7), Blocks.AIR.defaultBlockState(), false);
        scene.showBasePlate();
        scene.idle(5);
        return scene;
    }

    private static void block(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos pos, BlockState state) {
        block(scene, util, pos, state, Direction.DOWN);
    }

    private static void block(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos pos, BlockState state,
        Direction reveal) {
        // A revealing section must own the block exclusively, including while it fades in.
        // An existing owner can still be fading in, before it merges into the base section.
        scene.addInstruction(s -> s.forEachVisible(WorldSectionElement.class,
            section -> section.erase(util.select().position(pos))));
        scene.world().setBlock(pos, state, false);
        scene.world().showSection(util.select().position(pos), reveal);
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

    /** Commands refer to the stage shaft; gearing translates them to the off-stage source. */
    private record Drive(BlockPos source, float shaftPerSource) {
        void setSpeed(CreateSceneBuilder scene, SceneBuildingUtil util, float shaftRpm) {
            scene.world().setKineticSpeed(util.select().position(source), shaftRpm / shaftPerSource);
        }
    }

    private static Drive drive(CreateSceneBuilder scene, SceneBuildingUtil util, BlockState control, BlockState output) {
        return drive(scene, util, control, output, 1);
    }

    private static Drive drive(CreateSceneBuilder scene, SceneBuildingUtil util, BlockState control, BlockState output, int height) {
        return drive(scene, util, control, output, height, Direction.EAST);
    }

    private static Drive drive(CreateSceneBuilder scene, SceneBuildingUtil util, BlockState control, BlockState output,
        int height, Direction sourceSide) {
        boolean fromEast = sourceSide == Direction.EAST;
        BlockPos inlet = (fromEast ? OUTPUT : INPUT).above(height - 1);
        BlockPos machine = (fromEast ? INPUT : OUTPUT).above(height - 1);
        Drive input = driveInput(scene, util, inlet, sourceSide);
        scene.idle(10);
        block(scene, util, inlet.relative(sourceSide.getOpposite()), axis(AllBlocks.SHAFT.getDefaultState(), Axis.X));
        scene.idle(5);
        block(scene, util, CONTROL.above(height - 1), axis(control, Axis.X));
        scene.idle(5);
        block(scene, util, machine.relative(sourceSide), axis(AllBlocks.SHAFT.getDefaultState(), Axis.X));
        scene.idle(5);
        block(scene, util, machine, axis(output, Axis.X));
        scene.world().setKineticSpeed(util.select().everywhere(), 0);
        scene.idle(15);
        return input;
    }

    /** Native inlet: a low large cog beside the base plate meshes with the upper small cog. */
    private static Drive driveInput(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos pos, Direction outward) {
        return driveInput(scene, util, pos, outward, outward.getAxis() == Axis.X ? Direction.NORTH : Direction.WEST);
    }

    private static Drive driveInput(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos pos, Direction outward,
        Direction lowerOffset) {
        BlockPos topCog = pos.relative(outward);
        BlockPos input = new BlockPos(topCog.getX(), 0, topCog.getZ()).relative(lowerOffset);
        // Storyboard replacement instructions ignore blocks outside the template.
        // Extend only the editable bounds; the visible base plate stays unchanged.
        scene.addInstruction(s -> s.getWorld().setBounds(
            net.createmod.catnip.math.BBHelper.encapsulate(
                net.createmod.catnip.math.BBHelper.encapsulate(s.getWorld().getBounds(), input), topCog)));
        // Create's clutch and gauge templates place the source at y=0, offset
        // diagonally from a small cog at y=1. Taller shafts use a real cog ladder.
        block(scene, util, input, axis(AllBlocks.LARGE_COGWHEEL.getDefaultState(), outward.getAxis()), Direction.UP);
        for (int y = 1; y <= pos.getY(); y++)
            block(scene, util, new BlockPos(topCog.getX(), y, topCog.getZ()),
                axis(AllBlocks.COGWHEEL.getDefaultState(), outward.getAxis()));
        block(scene, util, pos, axis(AllBlocks.SHAFT.getDefaultState(), outward.getAxis()));
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).boundary(s.getWorld(), input, 0));
        return new Drive(input, pos.getY() % 2 == 0 ? 2 : -2);
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
    private static void target(CreateSceneBuilder scene, SceneBuildingUtil util, Drive input, float to) {
        input.setSpeed(scene, util, to);
        scene.idle(40);
    }

    public static void dynamics(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.DYNAMICS);
        BlockPos control = CONTROL.above(2);
        Drive input = drive(scene, util, AllBlocks.FLYWHEEL.getDefaultState(), AllBlocks.MECHANICAL_PRESS.getDefaultState(), 3);
        block(scene, util, INPUT, AllBlocks.DEPOT.getDefaultState());
        scene.idle(15);

        ItemStack ingots = new ItemStack(Items.COPPER_INGOT, 4);

        // Feed the native depot callbacks. A second scripted processor would
        // handle the same contact again or restart a stroke already in progress.
        loadPressBatch(scene, ingots, INPUT);
        scene.overlay().showControls(util.vector().blockSurface(INPUT, Direction.NORTH), Pointing.RIGHT, 30)
            .withItem(ingots);
        PonderLesson.DYNAMICS.showFor(scene, util, control, 0, PonderPalette.WHITE, 130);
        input.setSpeed(scene, util, 64);
        scene.effects().rotationSpeedIndicator(control);
        scene.idle(140);

        // Leave the original workpieces in place. Physically removing the input
        // shaft makes the change from driven work to stored energy visible.
        BlockPos inputShaft = control.east();
        scene.overlay().showControls(util.vector().topOf(inputShaft), Pointing.DOWN, 20)
            .withItem(AllItems.WRENCH.asStack()).rightClick().whileSneaking();
        scene.world().hideSection(util.select().position(inputShaft), Direction.UP);
        scene.idle(15);
        scene.world().setBlock(inputShaft, Blocks.AIR.defaultBlockState(), false);
        PonderLesson.DYNAMICS.showFor(scene, util, control, 1, PonderPalette.WHITE, 130);
        scene.overlay().showOutline(PonderPalette.RED, new Object(), util.select().position(inputShaft), 130);
        scene.idle(140);
        awaitPressBatch(scene, INPUT);
        scene.overlay().showControls(util.vector().blockSurface(INPUT, Direction.NORTH), Pointing.RIGHT, 25)
            .rightClick();
        scene.idle(10);
        scene.world().modifyBlockEntity(INPUT, com.simibubi.create.content.logistics.depot.DepotBlockEntity.class,
            depot -> depot.getBehaviour(com.simibubi.create.content.logistics.depot.DepotBehaviour.TYPE).clearContent());
        scene.idle(15);

        BlockPos upperCog = new BlockPos(2, 4, 3);
        BlockPos gearedShaft = new BlockPos(1, 4, 3);
        BlockPos gearedPress = new BlockPos(0, 4, 3);
        BlockPos gearedDepot = new BlockPos(0, 2, 3);
        BlockPos gearedSupport = new BlockPos(0, 1, 3);
        replaceBlock(scene, util, control, axis(AllBlocks.LARGE_COGWHEEL.getDefaultState(), Axis.X));
        block(scene, util, upperCog, axis(AllBlocks.COGWHEEL.getDefaultState(), Axis.X));
        block(scene, util, gearedShaft, axis(AllBlocks.SHAFT.getDefaultState(), Axis.X));
        block(scene, util, gearedSupport, Blocks.SMOOTH_STONE.defaultBlockState());
        block(scene, util, gearedPress, axis(AllBlocks.MECHANICAL_PRESS.getDefaultState(), Axis.X));
        block(scene, util, gearedDepot, AllBlocks.DEPOT.getDefaultState());
        block(scene, util, inputShaft, axis(AllBlocks.SHAFT.getDefaultState(), Axis.X));
        target(scene, util, input, 32);
        scene.effects().rotationSpeedIndicator(upperCog);
        PonderLesson.DYNAMICS.show(scene, util, upperCog, 2, PonderPalette.GREEN);

        // Explain the meshing gears from the side first, then turn toward the
        // depots for the comparison so both workpieces and heads remain visible.
        scene.rotateCameraY(-45);
        scene.idle(25);
        PonderLesson.DYNAMICS.showFor(scene, util, gearedDepot, 3, PonderPalette.GREEN, 150);
        scene.overlay().showOutline(PonderPalette.WHITE, new Object(), util.select().position(INPUT), 150);
        loadPressBatch(scene, ingots.copyWithCount(2), INPUT, gearedDepot);
        scene.idle(150);
        awaitPressBatch(scene, INPUT, gearedDepot);
        scene.idle(15);
        scene.markAsFinished();
    }

    /** Initial workpieces and collecting the previous finished batch are operator actions. */
    private static void loadPressBatch(CreateSceneBuilder scene, ItemStack input, BlockPos... depots) {
        scene.addInstruction(s -> {
            for (var pos : depots)
                if (s.getWorld().getBlockEntity(pos) instanceof com.simibubi.create.content.logistics.depot.DepotBlockEntity tray) {
                    var depot = tray.getBehaviour(com.simibubi.create.content.logistics.depot.DepotBehaviour.TYPE);
                    depot.clearContent();
                    var item = new com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack(input.copy());
                    item.beltPosition = item.prevBeltPosition = .5f;
                    item.angle = 200;
                    depot.setHeldItem(item);
                }
        });
    }

    private static void awaitPressBatch(CreateSceneBuilder scene, BlockPos... depots) {
        scene.addInstruction(new PonderAwait(s -> {
            for (var pos : depots)
                if (!(s.getWorld().getBlockEntity(pos) instanceof com.simibubi.create.content.logistics.depot.DepotBlockEntity tray)
                    || !AllItems.COPPER_SHEET.isIn(tray.getHeldItem())
                    || !(s.getWorld().getBlockEntity(pos.above(2)) instanceof com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity press)
                    || press.getPressingBehaviour().running) return false;
            return true;
        }, 700));
    }

    public static void clutch(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.CLUTCH);
        Drive input = drive(scene, util, AllBlocks.CLUTCH.getDefaultState(),
            AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
        block(scene, util, CONTROL.north(), AllBlocks.ANALOG_LEVER.getDefaultState());
        lever(scene, util, 0);
        target(scene, util, input, 64);
        scene.effects().indicateSuccess(CONTROL);
        PonderLesson.CLUTCH.show(scene, util, CONTROL, 0);

        scene.overlay().showControls(util.vector().topOf(CONTROL.north()), Pointing.DOWN, 35).rightClick();
        PonderLesson.CLUTCH.showFor(scene, util, CONTROL.north(), 1, PonderPalette.MEDIUM, 120);
        lever(scene, util, 15);
        scene.effects().indicateRedstone(CONTROL.north());
        scene.world().modifyBlock(CONTROL, s -> s.setValue(BlockStateProperties.POWERED, true), false);
        scene.idle(130);
        input.setSpeed(scene, util, 16);
        PonderLesson.CLUTCH.show(scene, util, INPUT, 2, PonderPalette.RED);

        replaceBlock(scene, util, CONTROL, axis(ModContent.BRASS_CLUTCH.getDefaultState(), Axis.X));
        lever(scene, util, 0);
        scene.idle(40);
        scene.overlay().showFilterSlotInput(util.vector().blockSurface(CONTROL, Direction.SOUTH), Direction.SOUTH, 60);
        scene.overlay().showControls(util.vector().blockSurface(CONTROL, Direction.SOUTH), Pointing.UP, 40).rightClick();
        scene.world().modifyBlockEntity(CONTROL, com.diliupengyun.differential_create.content.BrassClutchBlockEntity.class,
            brass -> brass.getBehaviour(com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour.TYPE).setValue(50));
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
        block(scene, util, new BlockPos(1, 1, 2), axis(AllBlocks.SHAFT.getDefaultState(), Axis.X));
        block(scene, util, CONTROL, axis(AllBlocks.ROTATION_SPEED_CONTROLLER.getDefaultState(), Axis.X));
        block(scene, util, new BlockPos(3, 1, 2), axis(AllBlocks.SHAFT.getDefaultState(), Axis.X));
        block(scene, util, OUTPUT, axis(AllBlocks.COGWHEEL.getDefaultState(), Axis.X));
        block(scene, util, topCog, axis(AllBlocks.LARGE_COGWHEEL.getDefaultState(), Axis.Z));
        block(scene, util, topCog.south(), axis(AllBlocks.SHAFT.getDefaultState(), Axis.Z));
        Drive topInput = driveInput(scene, util, topCog.south(2), Direction.SOUTH);
        scene.world().setKineticSpeed(util.select().everywhere(), 0);
        scene.idle(10);

        ratioSetting(scene, 16);
        target(scene, util, topInput, 32);
        scene.effects().rotationSpeedIndicator(CONTROL);
        PonderLesson.RATIO.show(scene, util, CONTROL, 0);

        scene.overlay().showFilterSlotInput(util.vector().blockSurface(CONTROL, Direction.NORTH), Direction.NORTH, 60);
        scene.overlay().showControls(util.vector().blockSurface(CONTROL, Direction.NORTH), Pointing.UP, 35).scroll();
        PonderLesson.RATIO.showFor(scene, util, CONTROL, 1, PonderPalette.MEDIUM, 100);
        ratioSetting(scene, -16);
        scene.idle(110);
        PonderLesson.RATIO.showFor(scene, util, CONTROL, 2, PonderPalette.RED, 100);
        ratioSetting(scene, 0);
        scene.idle(110);

        PonderLesson.RATIO.showFor(scene, util, topCog, 3, PonderPalette.GREEN, 120);
        ratioSetting(scene, 32);
        scene.effects().rotationSpeedIndicator(OUTPUT);
        scene.idle(130);

        scene.overlay().showControls(util.vector().blockSurface(CONTROL, Direction.NORTH), Pointing.UP, 40)
            .rightClick().whileCTRL();
        ratioSetting(scene, 24);
        scene.idle(20);
        PonderLesson.RATIO.showFor(scene, util, CONTROL, 4, PonderPalette.BLUE, 120);
        scene.idle(130);
        scene.markAsFinished();
    }

    private static void ratioSetting(CreateSceneBuilder scene, int raw) {
        scene.world().modifyBlockEntity(CONTROL,
            com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity.class,
            controller -> controller.targetSpeed.setValue(raw));
    }

    public static void loadGauge(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.LOAD_GAUGE);
        Drive input = drive(scene, util,
            AllBlocks.STRESSOMETER.getDefaultState()
                .setValue(GaugeBlock.AXIS_ALONG_FIRST_COORDINATE, true)
                .setValue(GaugeBlock.FACING, Direction.UP),
            AllBlocks.ENCASED_FAN.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
        block(scene, util, CONTROL.north(), Blocks.COMPARATOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        target(scene, util, input, 128);
        PonderLesson.LOAD_GAUGE.show(scene, util, CONTROL, 0);

        scene.effects().indicateSuccess(CONTROL);
        scene.effects().indicateRedstone(CONTROL.north());
        PonderLesson.LOAD_GAUGE.show(scene, util, CONTROL, 1, PonderPalette.MEDIUM);

        target(scene, util, input, 0);
        PonderLesson.LOAD_GAUGE.show(scene, util, CONTROL.north(), 2, PonderPalette.RED);

        scene.overlay().showControls(util.vector().topOf(CONTROL), Pointing.DOWN, 40)
            .withItem(AllItems.GOGGLES.asStack());
        PonderLesson.LOAD_GAUGE.show(scene, util, CONTROL, 3, PonderPalette.BLUE);
        scene.markAsFinished();
    }

    public static void speedGauge(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.SPEED_GAUGE);
        Drive input = drive(scene, util, AllBlocks.SHAFT.getDefaultState(),
            AllBlocks.SPEEDOMETER.getDefaultState()
                .setValue(GaugeBlock.AXIS_ALONG_FIRST_COORDINATE, true)
                .setValue(GaugeBlock.FACING, Direction.UP));
        block(scene, util, INPUT.north(), Blocks.COMPARATOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        target(scene, util, input, 128);
        scene.effects().indicateSuccess(INPUT);
        PonderLesson.SPEED_GAUGE.show(scene, util, INPUT, 0);

        scene.overlay().showControls(util.vector().blockSurface(INPUT, Direction.NORTH), Pointing.RIGHT, 40)
            .rightClick().withItem(AllItems.WRENCH.asStack());
        PonderLesson.SPEED_GAUGE.showFor(scene, util, INPUT, 1, PonderPalette.BLUE, 120);
        scene.world().modifyBlockEntity(INPUT, SpeedGaugeBlockEntity.class, gauge -> {
            if (gauge.getBehaviour(SpeedGaugeRangeBehaviour.TYPE) instanceof SpeedGaugeRangeBehaviour range)
                range.setValue(0);
        });
        scene.idle(130);

        scene.markAsFinished();
    }

    private static Drive pipes(CreateSceneBuilder scene, SceneBuildingUtil util, float initialSpeed,
                               int sourceWater, int receiverWater, int tankHeight) {
        pipeTank(scene, util, INPUT, tankHeight, sourceWater);
        pipeTank(scene, util, OUTPUT, tankHeight, receiverWater);
        block(scene, util, CONTROL.west(), axis(AllBlocks.GLASS_FLUID_PIPE.getDefaultState(), Axis.X));
        block(scene, util, CONTROL, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
        block(scene, util, new BlockPos(3, 1, 2), axis(AllBlocks.GLASS_FLUID_PIPE.getDefaultState(), Axis.X));
        block(scene, util, CONTROL.south(), axis(AllBlocks.COGWHEEL.getDefaultState(), Axis.X));
        block(scene, util, CONTROL.south().east(), axis(AllBlocks.SHAFT.getDefaultState(), Axis.X));
        Drive input = driveInput(scene, util, CONTROL.south().east(2), Direction.EAST);
        input.setSpeed(scene, util, initialSpeed);
        scene.idle(15);
        return input;
    }

    private static void pipeTank(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos origin,
                                 int height, int initialWater) {
        for (int y = 0; y < height; y++) {
            BlockPos pos = origin.above(y);
            block(scene, util, pos, AllBlocks.FLUID_TANK.getDefaultState()
                .setValue(com.simibubi.create.content.fluids.tank.FluidTankBlock.BOTTOM, y == 0)
                .setValue(com.simibubi.create.content.fluids.tank.FluidTankBlock.TOP, y == height - 1));
            if (y > 0) scene.world().modifyBlockEntity(pos, FluidTankBlockEntity.class, tank -> tank.setController(origin));
        }
        scene.world().modifyBlockEntity(origin, FluidTankBlockEntity.class, tank -> {
            tank.setWidth(1); tank.setHeight(height); tank.applyFluidTankSize(height);
            tank.setWindows(true);
        });
        fill(scene, origin, initialWater);
    }

    private static void fill(CreateSceneBuilder scene, BlockPos pos, int amount) {
        scene.world().modifyBlockEntity(pos, FluidTankBlockEntity.class,
            tank -> tank.getTankInventory().setFluid(new FluidStack(Fluids.WATER, amount)));
    }

    public static void pumpDirection(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.PUMP_DIRECTION);
        // Keep both footprints and the plumbing. A taller native window makes
        // the real waterline visible above the half-height pipe connection.
        // Leave enough accessible water for coasting now that the redundant
        // shaft-reversal phase no longer fills the receiving tank a second time.
        Drive input = pipes(scene, util, 0, 10000, 6000, 2);
        PonderLesson.PUMP_DIRECTION.showFor(scene, util, CONTROL, 0, PonderPalette.WHITE, 140);
        scene.idle(10);
        input.setSpeed(scene, util, -64);
        scene.idle(140);

        scene.overlay().showControls(util.vector().topOf(CONTROL), Pointing.DOWN, 35)
            .rightClick().withItem(AllItems.WRENCH.asStack());
        PonderLesson.PUMP_DIRECTION.showFor(scene, util, CONTROL, 1, PonderPalette.GREEN, 120);
        scene.world().modifyBlock(CONTROL, s -> s.setValue(PumpBlock.FACING, Direction.WEST), true);
        scene.idle(130);

        PonderLesson.PUMP_DIRECTION.showFor(scene, util, CONTROL, 2, PonderPalette.WHITE, 100);
        input.setSpeed(scene, util, 0);
        scene.idle(110);
        scene.markAsFinished();
    }

    public static void pumpNetwork(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.PUMP_NETWORK);
        BlockPos window = CONTROL.east();
        // No valve is needed to keep the introductory pipe dry: its source is
        // initially empty. Filling that source is the only scripted fluid edit.
        Drive input = pipes(scene, util, 0, 0, 0, 1);
        scene.overlay().showOutline(PonderPalette.WHITE, new Object(), util.select().position(window), 100);
        PonderLesson.PUMP_NETWORK.showFor(scene, util, window, 0, PonderPalette.WHITE, 100);
        scene.idle(110);

        PonderLesson.PUMP_NETWORK.showFor(scene, util, window, 1, PonderPalette.BLUE, 140);
        scene.overlay().showControls(util.vector().topOf(INPUT), Pointing.DOWN, 35)
            .rightClick().withItem(new ItemStack(Items.WATER_BUCKET));
        fill(scene, INPUT, 6000);
        input.setSpeed(scene, util, -8);
        scene.idle(150);

        PonderLesson.PUMP_NETWORK.showFor(scene, util, OUTPUT, 2, PonderPalette.GREEN, 100);
        scene.idle(110);
        scene.markAsFinished();
    }

    public static void bearing(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.BEARING);
        Drive inputPos = driveInput(scene, util, OUTPUT, Direction.EAST);
        BlockPos bearingPos = new BlockPos(2, 2, 2);
        block(scene, util, OUTPUT.west(), axis(AllBlocks.SHAFT.getDefaultState(), Axis.X));
        block(scene, util, bearingPos.below(), axis(AllBlocks.GEARBOX.getDefaultState(), Axis.Z));
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
        PonderLesson.BEARING.showFor(scene, util, bearingPos, 0, PonderPalette.WHITE, 120);
        inputPos.setSpeed(scene, util, 64);
        scene.idle(130);

        PonderLesson.BEARING.showFor(scene, util, bearingPos.above(), 1, PonderPalette.GREEN, 110);
        inputPos.setSpeed(scene, util, 32);
        scene.idle(120);

        PonderLesson.BEARING.showFor(scene, util, bearingPos, 2, PonderPalette.BLUE, 100);
        inputPos.setSpeed(scene, util, 0);
        scene.idle(110);
        scene.markAsFinished();
    }

    public static void structureLoad(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.STRUCTURE_LOAD);
        BlockPos shaftAnchor = new BlockPos(1, 1, 2);
        Drive input = driveInput(scene, util, INPUT, Direction.WEST);
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

        translateSection(scene, shaftAnchor, rig, Direction.EAST);
        input.setSpeed(scene, util, 6);
        PonderLesson.STRUCTURE_LOAD.show(scene, util, carriagePos, 0);

        PonderLesson.STRUCTURE_LOAD.show(scene, util, crop1, 1, PonderPalette.GREEN);

        input.setSpeed(scene, util, -6);
        PonderLesson.STRUCTURE_LOAD.show(scene, util, shaftAnchor, 2, PonderPalette.BLUE);
        input.setSpeed(scene, util, 0);
        scene.markAsFinished();
    }

    public static void continuousWork(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.CONTINUOUS_WORK);
        BlockPos control = CONTROL.above(2), output = INPUT.above(2);
        Drive input = drive(scene, util, AllBlocks.FLYWHEEL.getDefaultState(), AllBlocks.MECHANICAL_PRESS.getDefaultState(), 3);
        block(scene, util, INPUT, AllBlocks.DEPOT.getDefaultState());
        ItemStack ingot = new ItemStack(Items.COPPER_INGOT);
        ItemStack sheet = new ItemStack(AllItems.COPPER_SHEET.get());

        // The press consumes the ingot on the contact tick and leaves the sheet on the
        // depot; the item change must follow the shaft travel, not a scripted flip.
        PonderPressing.run(scene, 80, output, INPUT, ingot, sheet, 4, 3, 5);
        scene.overlay().showControls(util.vector().blockSurface(INPUT, Direction.NORTH), Pointing.RIGHT, 30)
            .withItem(ingot);
        PonderLesson.CONTINUOUS_WORK.showFor(scene, util, output, 0, PonderPalette.WHITE, 120);
        input.setSpeed(scene, util, 64);
        scene.effects().rotationSpeedIndicator(control);
        scene.idle(130);

        // Place a new ingot for each case, while its explanatory caption is visible.
        PonderPressing.run(scene, 260, output, INPUT, ingot, sheet, 4, 3, 5);
        PonderLesson.CONTINUOUS_WORK.showFor(scene, util, output, 1, PonderPalette.MEDIUM, 120);
        input.setSpeed(scene, util, 32);
        scene.idle(130);

        PonderLesson.CONTINUOUS_WORK.showFor(scene, util, control, 2, PonderPalette.GREEN, 150);
        scene.overlay().showControls(util.vector().topOf(INPUT), Pointing.DOWN, 40).withItem(ingot);
        PonderPressing.run(scene, 260, output, INPUT, ingot, sheet, 4, 3, 5);
        input.setSpeed(scene, util, 0);
        scene.idle(160);
        scene.markAsFinished();
    }

    public static void ejector(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.EJECTOR_WORK);
        BlockPos inputPos = new BlockPos(4, 1, 4);
        block(scene, util, INPUT, AllBlocks.DEPOT.getDefaultState());
        block(scene, util, CONTROL, AllBlocks.DEPOT.getDefaultState());
        block(scene, util, OUTPUT, AllBlocks.WEIGHTED_EJECTOR.getDefaultState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        block(scene, util, inputPos.north(), axis(AllBlocks.SHAFT.getDefaultState(), Axis.Z));
        Drive input = driveInput(scene, util, inputPos, Direction.SOUTH);
        scene.world().modifyBlockEntity(OUTPUT, com.simibubi.create.content.logistics.depot.EjectorBlockEntity.class,
            ejector -> ejector.setTarget(4, 0));

        PonderLesson.EJECTOR_WORK.showFor(scene, util, OUTPUT, 0, PonderPalette.WHITE, 120);
        input.setSpeed(scene, util, 64);
        scene.idle(130);

        target(scene, util, input, 0);
        ItemStack stack = new ItemStack(Items.COPPER_INGOT);
        scene.overlay().showControls(util.vector().topOf(OUTPUT), Pointing.DOWN, 35).withItem(stack);
        scene.effects().indicateSuccess(OUTPUT);
        scene.world().createItemOnBeltLike(OUTPUT, Direction.EAST, stack);
        PonderLesson.EJECTOR_WORK.show(scene, util, OUTPUT, 1, PonderPalette.GREEN);

        scene.overlay().showOutline(PonderPalette.OUTPUT, new Object(), util.select().position(CONTROL), 60);
        scene.world().modifyBlockEntity(OUTPUT, com.simibubi.create.content.logistics.depot.EjectorBlockEntity.class,
            ejector -> ejector.setTarget(2, 0));
        target(scene, util, input, 64);
        PonderLesson.EJECTOR_WORK.showFor(scene, util, CONTROL, 2, PonderPalette.BLUE, 120);
        scene.world().createItemOnBeltLike(OUTPUT, Direction.EAST, stack);
        scene.idle(130);
        scene.markAsFinished();
    }

    public static void fan(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.FAN_WORK);
        scene.configureBasePlate(0, 0, 6);
        scene.scaleSceneView(1.1f);
        BlockPos inputPos = new BlockPos(2, 2, 5);
        BlockPos fanPos = new BlockPos(2, 2, 4);
        BlockPos lavaPos = new BlockPos(2, 2, 2);
        BlockPos depotPos = new BlockPos(2, 1, 1);
        block(scene, util, inputPos.below(), Blocks.ANDESITE.defaultBlockState());
        block(scene, util, fanPos.below(), Blocks.ANDESITE.defaultBlockState());
        Drive input = driveInput(scene, util, inputPos, Direction.SOUTH);
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

        target(scene, util, input, -64);
        scene.world().createItemOnBeltLike(depotPos, Direction.SOUTH, new ItemStack(Items.GOLD_ORE));
        scene.effects().rotationDirectionIndicator(fanPos);
        PonderLesson.FAN_WORK.showFor(scene, util, depotPos, 0, PonderPalette.WHITE, 220);
        scene.idle(230);

        PonderLesson.FAN_WORK.showFor(scene, util, fanPos, 1, PonderPalette.GREEN, 220);
        input.setSpeed(scene, util, -128);
        scene.world().removeItemsFromBelt(depotPos);
        scene.overlay().showControls(util.vector().topOf(depotPos), Pointing.DOWN, 35)
            .withItem(new ItemStack(Items.GOLD_ORE));
        scene.world().createItemOnBeltLike(depotPos, Direction.SOUTH, new ItemStack(Items.GOLD_ORE));
        scene.overlay().showOutline(PonderPalette.GREEN, new Object(), util.select().position(depotPos), 210);
        scene.idle(230);

        PonderLesson.FAN_WORK.showFor(scene, util, fanPos, 2, PonderPalette.MEDIUM, 120);
        input.setSpeed(scene, util, 0);
        scene.idle(130);
        scene.markAsFinished();
    }

    public static void sequencer(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.SEQUENCER);
        scene.configureBasePlate(0, 0, 6);
        scene.scaleSceneView(1.05f);
        BlockPos control = CONTROL.above(), output = OUTPUT.above();
        Drive input = drive(scene, util, AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState(),
            AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST), 2, Direction.WEST);
        // An asymmetric arm makes both the commanded angle and the held position visible.
        Selection armSelection = util.select().fromTo(output.east(), output.east().above());
        scene.world().setBlocks(armSelection, Blocks.OAK_PLANKS.defaultBlockState(), false);
        scene.world().setBlock(output.east().above(), Blocks.WHITE_WOOL.defaultBlockState(), false);
        ElementLink<WorldSectionElement> arm = scene.world().showIndependentSection(armSelection, Direction.DOWN);
        scene.world().configureCenterOfRotation(arm, util.vector().centerOf(output));
        scene.effects().superGlue(output.east(), Direction.UP, true);
        coupleSection(scene, util, output, arm,
            Set.of(new ContraptionGeometry.Cell(1, 0, 0), new ContraptionGeometry.Cell(1, 1, 0)), Axis.X, 660);
        block(scene, util, control.north(), Blocks.LEVER.defaultBlockState()
            .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        scene.overlay().showControls(util.vector().topOf(control), Pointing.DOWN, 35).rightClick();
        scene.world().modifyBlockEntity(control,
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
        target(scene, util, input, 16);

        scene.world().toggleRedstonePower(util.select().position(control.north()));
        scene.effects().indicateRedstone(control.north());
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).sequence(s.getWorld(), control));
        PonderLesson.SEQUENCER.show(scene, util, control, 0);

        scene.idle(20);
        scene.effects().indicateSuccess(output);
        PonderLesson.SEQUENCER.showFor(scene, util, output, 1, PonderPalette.GREEN, 200);
        scene.idle(210);
        scene.addInstruction(new PonderAwait(s -> s.getWorld().getBlockEntity(control)
            instanceof com.diliupengyun.differential_create.kinetics.ISequencerDynamics sequencer
            && sequencer.differentialcreate$motion().index < 0
            && sequencer.differentialcreate$motion().settled(), 400));
        scene.idle(15);

        scene.world().toggleRedstonePower(util.select().position(control.north()));
        scene.idle(5);
        scene.world().toggleRedstonePower(util.select().position(control.north()));
        scene.effects().indicateRedstone(control.north());
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).sequence(s.getWorld(), control));
        PonderLesson.SEQUENCER.show(scene, util, control.north(), 2, PonderPalette.BLUE);
        scene.idle(120);
        scene.addInstruction(new PonderAwait(s -> s.getWorld().getBlockEntity(control)
            instanceof com.diliupengyun.differential_create.kinetics.ISequencerDynamics sequencer
            && sequencer.differentialcreate$motion().index < 0
            && sequencer.differentialcreate$motion().settled(), 400));
        scene.idle(25);
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
        block(scene, util, new BlockPos(0, 3, 4), axis(AllBlocks.LARGE_COGWHEEL.getDefaultState(), Axis.Z));
        BlockPos water = new BlockPos(4, 2, 0), pump = new BlockPos(4, 2, 1);
        block(scene, util, water, AllBlocks.FLUID_TANK.getDefaultState());
        fill(scene, water, 6000);
        block(scene, util, pump, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.SOUTH));
        block(scene, util, pump.west(), axis(AllBlocks.COGWHEEL.getDefaultState(), Axis.Z));
        Drive pumpInput = driveInput(scene, util, pump.west().north(), Direction.NORTH);
        PonderLesson.STEAM_POWER.showFor(scene, util, new BlockPos(3, 3, 2), 0, PonderPalette.WHITE, 130);
        scene.world().setBlocks(util.select().fromTo(3, 1, 2, 4, 1, 3),
            AllBlocks.BLAZE_BURNER.getDefaultState().setValue(
                com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HEAT_LEVEL,
                com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel.KINDLED), false);
        pumpInput.setSpeed(scene, util, -64);
        scene.effects().rotationSpeedIndicator(shaftPos);
        scene.idle(140);

        PonderLesson.STEAM_POWER.show(scene, util, enginePos, 1, PonderPalette.MEDIUM);
        scene.idle(65);

        // Removing the heat source is a real player operation. Letting a fueled
        // burner expire would leave a smouldering blaze and passive steam power.
        scene.world().hideSection(util.select().fromTo(3, 1, 2, 4, 1, 3), Direction.DOWN);
        scene.idle(20);
        scene.world().setBlocks(util.select().fromTo(3, 1, 2, 4, 1, 3),
            Blocks.AIR.defaultBlockState(), false);
        PonderLesson.STEAM_POWER.showFor(scene, util, shaftPos, 2, PonderPalette.BLUE, 120);
        scene.idle(130);
        scene.markAsFinished();
    }

    private static void boiler(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos origin, int width, int height) {
        boiler(scene, util, origin, width, height, 1);
    }

    private static void boiler(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos origin, int width, int height, int engines) {
        for (int x = 0; x < width; x++) for (int z = 0; z < width; z++)
            block(scene, util, origin.offset(x, -1, z), AllBlocks.BLAZE_BURNER.getDefaultState()
                .setValue(com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HEAT_LEVEL,
                    com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel.NONE));
        boilerTank(scene, util, origin, width, height, engines, 0);
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
                    .setValue(com.simibubi.create.content.redstone.rail.ControllerRailBlock.BACKWARDS, false)
                    .setValue(BlockStateProperties.POWER, 1);
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

        PonderLesson.MINECART_DYNAMICS.showFor(scene, util, CONTROL, 0, PonderPalette.WHITE, 110);
        moveCarts(scene, cart, trailing, contraption, -1, 40);
        scene.idle(120);

        PonderLesson.MINECART_DYNAMICS.showFor(scene, util, new BlockPos(1, 1, 2), 1, PonderPalette.MEDIUM, 100);
        moveCarts(scene, cart, trailing, contraption, -2, 60);
        scene.idle(110);

        PonderLesson.MINECART_DYNAMICS.showFor(scene, util, INPUT, 2, PonderPalette.GREEN, 120);
        for (int x : new int[]{0, 1, 3, 4}) {
            BlockPos rail = new BlockPos(x, 1, 2);
            scene.world().modifyBlock(rail, state -> state.setValue(
                com.simibubi.create.content.redstone.rail.ControllerRailBlock.BACKWARDS, true), false);
        }
        moveCarts(scene, cart, trailing, contraption, 2, 100);
        scene.idle(130);
        scene.markAsFinished();
    }

    public static void crusher(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = start(builder, util, PonderLesson.CRUSHER_WORK);
        BlockPos leftWheel = new BlockPos(1, 2, 2), rightWheel = new BlockPos(3, 2, 2);
        BlockPos leftInput = new BlockPos(1, 2, 4), rightInput = new BlockPos(3, 2, 4);
        BlockPos gap = new BlockPos(2, 2, 2);
        // This lesson only covers the mod's delta; the vanilla entry teaches the basics.
        block(scene, util, leftWheel, axis(AllBlocks.CRUSHING_WHEEL.getDefaultState(), Axis.Z));
        block(scene, util, rightWheel, axis(AllBlocks.CRUSHING_WHEEL.getDefaultState(), Axis.Z));
        block(scene, util, leftInput.north(), axis(AllBlocks.SHAFT.getDefaultState(), Axis.Z));
        block(scene, util, rightInput.north(), axis(AllBlocks.SHAFT.getDefaultState(), Axis.Z));
        // Put the two low source cogs on opposite sides of their cog ladders.
        // An inward offset would also mesh with the other input and couple the drives.
        Drive leftDrive = driveInput(scene, util, leftInput, Direction.SOUTH, Direction.WEST);
        Drive rightDrive = driveInput(scene, util, rightInput, Direction.SOUTH, Direction.EAST);
        // The invisible controller also needs virtual ticks to accept and process the falling item.
        block(scene, util, gap, AllBlocks.CRUSHING_WHEEL_CONTROLLER.getDefaultState()
            .setValue(com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock.FACING, Direction.DOWN)
            .setValue(com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock.VALID, true));
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).enableCrusherProcessing(gap));
        leftDrive.setSpeed(scene, util, -32);
        rightDrive.setSpeed(scene, util, -16);
        scene.idle(20);
        scene.effects().rotationDirectionIndicator(leftWheel);
        scene.effects().rotationDirectionIndicator(rightWheel);
        PonderLesson.CRUSHER_WORK.showFor(scene, util, rightWheel, 0, PonderPalette.MEDIUM, 130);
        scene.world().createItemEntity(
            util.vector().centerOf(2, 3, 2).add(0, 1.5, 0), util.vector().of(0, -0.4, 0),
            new ItemStack(Items.GOLD_ORE));
        scene.idle(130);

        // The speed difference sets which way the gap feeds.
        PonderLesson.CRUSHER_WORK.showFor(scene, util, gap, 1, PonderPalette.MEDIUM, 70);
        scene.idle(70);
        // The default view puts +X on screen-left; the example caption uses screen sides.
        PonderLesson.CRUSHER_WORK.showFor(scene, util, gap, 2, PonderPalette.MEDIUM, 80);
        scene.idle(80);

        // The feed side is where items have to enter; here it is the top.
        scene.world().createItemEntity(
            util.vector().centerOf(2, 3, 2).add(0, 1.5, 0), util.vector().of(0, -0.4, 0),
            new ItemStack(Items.GOLD_ORE));
        PonderLesson.CRUSHER_WORK.showFor(scene, util, gap, 3, PonderPalette.MEDIUM, 100);
        scene.idle(100);
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
        BlockPos lever = stationPos.above(), comparator = stationPos.north(), wire = comparator.north();

        block(scene, util, INPUT, AllBlocks.ANDESITE_CASING.getDefaultState());
        block(scene, util, stationPos.below(), AllBlocks.ANDESITE_CASING.getDefaultState());
        block(scene, util, stationPos, interfaceState.setValue(BlockStateProperties.FACING, Direction.EAST));
        block(scene, util, lever, Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR));
        for (var pos : new BlockPos[]{comparator, wire})
            block(scene, util, pos.below(), AllBlocks.ANDESITE_CASING.getDefaultState());
        block(scene, util, comparator, Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        block(scene, util, wire, Blocks.REDSTONE_WIRE.defaultBlockState());
        for (int x = 1; x <= 3; x++)
            block(scene, util, new BlockPos(x, 1, 2), AllBlocks.GANTRY_SHAFT.getDefaultState()
                .setValue(com.simibubi.create.content.kinetics.gantry.GantryShaftBlock.FACING, Direction.EAST));
        connectGantryShafts(scene, util.select().fromTo(1, 1, 2, 3, 1, 2));
        Drive input = driveInput(scene, util, OUTPUT, Direction.EAST);

        scene.world().setBlock(storagePos,
            fluid ? AllBlocks.FLUID_TANK.getDefaultState() : Blocks.BARREL.defaultBlockState(), false);
        scene.world().setBlock(movingInterface, interfaceState.setValue(BlockStateProperties.FACING, Direction.WEST), false);
        scene.world().setBlock(carriagePos, AllBlocks.GANTRY_CARRIAGE.getDefaultState()
            .setValue(BlockStateProperties.FACING, Direction.UP)
            .setValue(com.simibubi.create.content.contraptions.gantry.GantryCarriageBlock.AXIS_ALONG_FIRST_COORDINATE, false), false);
        ElementLink<WorldSectionElement> contraption = scene.world()
            .showIndependentSection(util.select().fromTo(carriagePos, storagePos), Direction.DOWN);
        scene.idle(10);

        // Start at the already taught docking position. This lesson is about the
        // optional settings, not a second explanation of how to install a pair.
        dockingSetting(scene, util, stationPos, true, 1);
        translateSection(scene, shaftAnchor, contraption, Direction.WEST);
        scene.addInstruction(s -> PonderSimulation.of(s.getWorld()).dock(s.getWorld(), s.resolve(contraption),
            stationPos, movingInterface, shaftAnchor));
        PonderLesson.PORTABLE_DOCKING.showFor(scene, util, stationPos, 0, PonderPalette.WHITE, 150);
        scene.idle(15);
        input.setSpeed(scene, util, 4);
        scene.idle(100); // Longer than the native idle timeout; there is no cargo to renew it.
        dockingRedstone(scene, util, lever);
        scene.idle(40);

        dockingSetting(scene, util, stationPos, false, 1);
        PonderLesson.PORTABLE_DOCKING.showFor(scene, util, comparator, 1, PonderPalette.GREEN, 120);
        dockingRedstone(scene, util, lever);
        scene.idle(85);
        dockingRedstone(scene, util, lever);
        scene.idle(40);

        dockingSetting(scene, util, stationPos, false, 2);
        PonderLesson.PORTABLE_DOCKING.showFor(scene, util, comparator, 2, PonderPalette.BLUE, 180);
        scene.idle(30); // 0: no connection.
        dockingRedstone(scene, util, lever);
        scene.idle(80); // Real extension/braking passes through 8, then connected gives 15.
        dockingRedstone(scene, util, lever);
        scene.idle(35);
        dockingRedstone(scene, util, lever);
        scene.idle(40);
        scene.markAsFinished();
    }

    private static void dockingSetting(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos pos, boolean release, int option) {
        scene.overlay().showControls(util.vector().blockSurface(pos, Direction.NORTH), Pointing.LEFT, 25)
            .rightClick().withItem(AllItems.WRENCH.asStack());
        scene.addInstruction(s -> ((PortableStorageInterfaceBlockEntity) s.getWorld().getBlockEntity(pos)).getBehaviour(release
            ? com.diliupengyun.differential_create.kinetics.portable.DockingSettingBehaviour.RELEASE
            : com.diliupengyun.differential_create.kinetics.portable.DockingSettingBehaviour.OUTPUT).setValue(option));
    }

    private static void dockingRedstone(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos lever) {
        scene.world().toggleRedstonePower(util.select().position(lever));
        scene.effects().indicateRedstone(lever);
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

        PonderLesson.TRAIN_POWER.captionFor(scene, 0, PonderPalette.WHITE, 130);
        scene.overlay().showControls(util.vector().topOf(bogeyPos), Pointing.DOWN, 40)
            .rightClick().withItem(AllBlocks.STEAM_ENGINE.asStack());
        upgrade(scene);
        scene.effects().indicateSuccess(bogeyPos);
        scene.overlay().showOutline(PonderPalette.WHITE, new Object(), util.select().position(bogeyPos), 130);
        scene.idle(140);

        ItemStack hose = ModContent.STEAM_HOSE.asStack();
        PonderLesson.TRAIN_POWER.captionFor(scene, 1, PonderPalette.GREEN, 130);
        scene.overlay().showControls(util.vector().blockSurface(boilerPos, Direction.WEST), Pointing.RIGHT, 30)
            .rightClick().withItem(hose);
        scene.idle(15);
        scene.overlay().showControls(util.vector().topOf(bogeyPos), Pointing.DOWN, 30)
            .rightClick().withItem(hose);
        scene.overlay().showLine(PonderPalette.GREEN, util.vector().centerOf(boilerPos),
            util.vector().centerOf(bogeyPos), 60);
        connectTrainBoiler(scene, bogeyPos, boilerPos);
        scene.overlay().showOutline(PonderPalette.GREEN, new Object(), util.select().fromTo(6, 4, 3, 8, 5, 5), 130);
        scene.idle(130);

        PonderLesson.TRAIN_POWER.captionFor(scene, 2, PonderPalette.BLUE, 130);
        scene.overlay().showControls(util.vector().topOf(bogeyPos), Pointing.DOWN, 40)
            .rightClick().withItem(ModContent.CONDENSER_PIPE.asStack());
        scene.world().modifyBlockEntity(bogeyPos, AbstractBogeyBlockEntity.class, SteamBogey::installCondenser);
        scene.overlay().showOutline(PonderPalette.BLUE, new Object(), util.select().position(bogeyPos), 130);
        scene.idle(140);

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
        PonderLesson.TRAIN_DRIVING.showFor(scene, util, controlsPos, 0, PonderPalette.WHITE, 120);
        trainCommand(scene, train, .08, false, 70);
        scene.idle(70);
        trainCommand(scene, train, 0, true, 60);
        scene.idle(60);

        scene.world().modifyBlock(leverPos, s -> s.setValue(GearLeverBlock.GEAR, 0), false);
        trainCommand(scene, train, .04, false, 100);
        PonderLesson.TRAIN_DRIVING.caption(scene, 1, PonderPalette.MEDIUM);

        scene.world().modifyBlock(leverPos, s -> s.setValue(GearLeverBlock.GEAR, 6), false);
        trainCommand(scene, train, 0, true, 20);
        scene.idle(20);
        trainCommand(scene, train, .015, false, 40);
        scene.idle(40);
        trainCommand(scene, train, 0, true, 40);
        scene.world().animateTrainStation(stationPos, true);
        scene.overlay().showOutline(PonderPalette.GREEN, new Object(), util.select().position(stationPos), 90);
        PonderLesson.TRAIN_DRIVING.caption(scene, 2, PonderPalette.GREEN);
        scene.effects().indicateSuccess(stationPos);

        scene.overlay().showControls(util.vector().topOf(controlsPos), Pointing.DOWN, 40)
            .rightClick();
        scene.world().toggleControls(controlsPos);
        PonderLesson.TRAIN_DRIVING.caption(scene, 3, PonderPalette.BLUE);
        scene.markAsFinished();
    }

    /** Driver input only; steam, gears, mass, drag and brakes determine displacement. */
    private static void trainCommand(CreateSceneBuilder scene, ElementLink<WorldSectionElement> train,
        double throttle, boolean brake, int duration) {
        scene.addInstruction(s -> {
            var section = s.resolve(train);
            if (section != null) PonderSimulation.of(s.getWorld()).train(s.getWorld(), section, throttle, brake, duration);
        });
    }

    /** Coupled carts receive driver inputs; rail force, mass and coupling determine travel. */
    private static void moveCarts(CreateSceneBuilder scene, ElementLink<MinecartElement> first,
        ElementLink<MinecartElement> second, ElementLink<WorldSectionElement> cargo, double distance, int duration) {
        scene.addInstruction(s -> {
            var a = s.resolve(first); var b = s.resolve(second); var section = s.resolve(cargo);
            if (a == null || b == null || section == null) return;
            var simulation = PonderSimulation.of(s.getWorld());
            var cells = new java.util.HashSet<ContraptionGeometry.Cell>();
            var selection = ((com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor) section).differentialcreate$selection();
            for (var pos : selection) if (!s.getWorld().getBlockState(pos).isAir())
                cells.add(new ContraptionGeometry.Cell(pos.getX(), pos.getY(), pos.getZ()));
            var shape = ContraptionGeometry.measure(cells);
            simulation.cartLoad(section, a, b, shape.linearInertia(), shape.linearDrag(0));
            simulation.cart(a, new net.minecraft.world.phys.Vec3(distance, 0, 0), duration);
            simulation.cart(b, new net.minecraft.world.phys.Vec3(distance, 0, 0), duration);
        });
    }

    /** Bind a displayed linear section to a separately integrated mounted rotor. */
    private static void translateSection(CreateSceneBuilder scene, BlockPos actuator,
        ElementLink<WorldSectionElement> moving, Direction direction) {
        scene.addInstruction(s -> {
            var section = s.resolve(moving);
            if (section != null && s.getWorld().getBlockEntity(actuator) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity be)
                PonderSimulation.of(s.getWorld()).attach(section, be, false, direction.getAxis().ordinal(), direction.getAxisDirection().getStep());
        });
    }

    /** Bind a displayed section to a separately integrated mounted rotor. */
    private static void coupleSection(CreateSceneBuilder scene, SceneBuildingUtil util,
        BlockPos bearing, ElementLink<WorldSectionElement> moving,
        java.util.Set<com.diliupengyun.differential_create.kinetics.ContraptionGeometry.Cell> cells,
        int duration) {
        coupleSection(scene, util, bearing, moving, cells, Axis.Y, duration);
    }

    private static void coupleSection(CreateSceneBuilder scene, SceneBuildingUtil util,
        BlockPos bearing, ElementLink<WorldSectionElement> moving,
        Set<ContraptionGeometry.Cell> cells, Axis rotationAxis, int duration) {
        scene.addInstruction(s -> {
            if (s.getWorld().getBlockEntity(bearing) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity be)
                PonderSimulation.of(s.getWorld()).mounting().bind(s.resolve(moving), be, true, rotationAxis.ordinal(), cells, 1);
        });
        scene.addInstruction(new net.createmod.ponder.foundation.instruction.PonderInstruction() {
            private int elapsed;
            @Override public boolean isComplete() { return elapsed >= duration; }
            @Override public void reset(net.createmod.ponder.foundation.PonderScene s) { elapsed = 0; }
            @Override public void tick(net.createmod.ponder.foundation.PonderScene s) {
                elapsed++;
                if (!(s.getWorld().getBlockEntity(bearing) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity be)) return;
                var section = s.resolve(moving);
                if (section != null && section.isVisible()) {
                    Double angle = PonderKinetics.of(s.getWorld()).outputAngle(be, 1);
                    double degrees = angle == null ? 0 : angle;
                    section.setAnimatedRotation(util.vector().of(rotationAxis == Axis.X ? degrees : 0,
                        rotationAxis == Axis.Y ? degrees : 0, rotationAxis == Axis.Z ? degrees : 0), false);
                    if (be instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity bearingEntity)
                        bearingEntity.setAngle(angle == null ? 0 : angle.floatValue());
                }
            }
        });
    }
}
