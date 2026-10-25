package com.diliupengyun.differential_create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.fluid.PipeScenes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PipeScenes.class)
public abstract class PipeResultSceneMixin {
    @ModifyConstant(method = "valve", constant = @Constant(intValue = 60, ordinal = 1))
    private static int differentialcreate$openingCaption(int duration) { return 160; }

    @ModifyConstant(method = "valve", constant = @Constant(intValue = 50))
    private static int differentialcreate$closingCaption(int duration) { return 220; }

    @Inject(method = "valve", at = @At("HEAD"))
    private static void differentialcreate$prepareValveDrive(net.createmod.ponder.api.scene.SceneBuilder builder,
        net.createmod.ponder.api.scene.SceneBuildingUtil util, CallbackInfo ci) {
        new CreateSceneBuilder(builder).world().setKineticSpeed(util.select().everywhere(), 0);
        builder.addInstruction(scene -> com.diliupengyun.differential_create.client.ponder.PonderKinetics.of(scene.getWorld())
            .boundary(scene.getWorld(), new BlockPos(5, 0, 2), 0));
    }
    @Inject(method = "smart", at = @At("HEAD"))
    private static void differentialcreate$prepareFilterDrive(net.createmod.ponder.api.scene.SceneBuilder builder,
        net.createmod.ponder.api.scene.SceneBuildingUtil util, CallbackInfo ci) {
        new CreateSceneBuilder(builder).world().setKineticSpeed(util.select().everywhere(), 0);
        // Gravity may pass liquid through a stopped pump. Keep the source
        // empty until the filter is set, so setup cannot release unfiltered milk.
        new CreateSceneBuilder(builder).world().modifyBlockEntity(new BlockPos(4, 1, 1),
            com.simibubi.create.content.processing.basin.BasinBlockEntity.class, basin -> {
                var stock = basin.inputTank.getCapability();
                for (int i = 0; i < stock.getTanks(); i++)
                    stock.drain(Integer.MAX_VALUE, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            });
        // The first phase supplies the chocolate tank. A little initial milk
        // gives both receivers comparable visible stock for the final phase.
        // Inventory levels are visual references, not port pressure or eligibility.
        new CreateSceneBuilder(builder).world().modifyBlockEntity(new BlockPos(4, 1, 3),
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity.class, tank -> tank.getTankInventory().setFluid(
                net.neoforged.neoforge.fluids.FluidStack.EMPTY));
        new CreateSceneBuilder(builder).world().modifyBlockEntity(new BlockPos(4, 1, 4),
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity.class, tank -> tank.getTankInventory().setFluid(
                new net.neoforged.neoforge.fluids.FluidStack(net.neoforged.neoforge.common.NeoForgeMod.MILK.get(), 1000)));
        builder.addInstruction(scene -> com.diliupengyun.differential_create.client.ponder.PonderKinetics.of(scene.getWorld())
            .boundary(scene.getWorld(), new BlockPos(1, 0, 5), 0));
    }
    @WrapOperation(method = "smart", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlockEntity(Lnet/minecraft/core/BlockPos;Ljava/lang/Class;Ljava/util/function/Consumer;)V"))
    private static <T extends net.minecraft.world.level.block.entity.BlockEntity> void differentialcreate$deferFilterStock(
        CreateSceneBuilder.WorldInstructions world, BlockPos pos, Class<T> type, java.util.function.Consumer<T> operation,
        Operation<Void> original) {}

    @WrapOperation(method = "smart", at = @At(value = "INVOKE", ordinal = 4,
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private static void differentialcreate$keepSourceFilterWhileConfiguring(CreateSceneBuilder.WorldInstructions world,
        BlockPos pos, BlockState state, boolean particles, Operation<Void> original) {
        // A zero drive command disconnects the shaft; it does not stop the pump.
        // Keep the exhausted chocolate filter blocking milk until both outlet
        // filters have been set by the native bucket interactions.
    }

    @ModifyArg(method = "smart", at = @At(value = "INVOKE", ordinal = 2,
        target = "Lnet/createmod/ponder/api/scene/OverlayInstructions;showText(I)Lnet/createmod/ponder/api/element/TextElementBuilder;"), index = 0)
    private static int differentialcreate$routingReadingTime(int duration) { return 130; }

    @ModifyConstant(method = "smart", constant = @Constant(intValue = 40))
    private static int differentialcreate$showActualRouting(int duration) { return 360; }

    @Inject(method = "smart", at = @At(value = "INVOKE", ordinal = 6,
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;showSection(Lnet/createmod/ponder/api/scene/Selection;Lnet/minecraft/core/Direction;)V"))
    private static void differentialcreate$filterAtBranchEntrance(net.createmod.ponder.api.scene.SceneBuilder builder,
        net.createmod.ponder.api.scene.SceneBuildingUtil util, CallbackInfo ci) {
        var scene = new CreateSceneBuilder(builder);
        // Ordered fluid cannot pass through chocolate trapped ahead of a milk
        // filter. Put the filter directly at the junction, before the bend, and
        // route its downstream pipe around the existing tank footprints.
        // SOUTH keeps both Z-axis ports connected while turning the sloped
        // filter face toward the native camera, so the milk bucket is readable.
        scene.world().setBlock(new BlockPos(1, 1, 4), com.simibubi.create.AllBlocks.SMART_FLUID_PIPE.getDefaultState()
            .setValue(net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock.FACE,
                net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
            .setValue(net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock.FACING, net.minecraft.core.Direction.SOUTH), false);
        scene.world().setBlock(new BlockPos(2, 1, 4), Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(new BlockPos(3, 1, 4), Blocks.AIR.defaultBlockState(), false);
        var pipe = com.simibubi.create.AllBlocks.FLUID_PIPE.getDefaultState()
            .setValue(com.simibubi.create.content.fluids.pipes.FluidPipeBlock.UP, false)
            .setValue(com.simibubi.create.content.fluids.pipes.FluidPipeBlock.DOWN, false)
            .setValue(com.simibubi.create.content.fluids.pipes.FluidPipeBlock.SOUTH, false);
        scene.world().setBlock(new BlockPos(1, 1, 5), pipe
            .setValue(com.simibubi.create.content.fluids.pipes.FluidPipeBlock.NORTH, true)
            .setValue(com.simibubi.create.content.fluids.pipes.FluidPipeBlock.EAST, true)
            .setValue(com.simibubi.create.content.fluids.pipes.FluidPipeBlock.WEST, false), false);
        scene.world().setBlock(new BlockPos(2, 1, 5), com.simibubi.create.AllBlocks.FLUID_PIPE.get().getAxisState(net.minecraft.core.Direction.Axis.X), false);
        scene.world().setBlock(new BlockPos(3, 1, 5), com.simibubi.create.AllBlocks.GLASS_FLUID_PIPE.getDefaultState()
            .setValue(com.simibubi.create.content.fluids.pipes.GlassFluidPipeBlock.AXIS, net.minecraft.core.Direction.Axis.X), false);
        scene.world().setBlock(new BlockPos(4, 1, 5), pipe
            .setValue(com.simibubi.create.content.fluids.pipes.FluidPipeBlock.NORTH, true)
            .setValue(com.simibubi.create.content.fluids.pipes.FluidPipeBlock.EAST, false)
            .setValue(com.simibubi.create.content.fluids.pipes.FluidPipeBlock.WEST, true), false);
        builder.addInstruction(s -> {
            for (int x = 1; x <= 5; x++) s.getWorld().setBlock(new BlockPos(x, 0, 5),
                s.getWorld().getBlockState(new BlockPos(x, 0, 3)), 3);
        });
        scene.world().showSection(util.select().fromTo(1, 0, 5, 5, 0, 5), net.minecraft.core.Direction.NORTH);
        scene.world().showSection(util.select().fromTo(1, 1, 5, 4, 1, 5), net.minecraft.core.Direction.NORTH);
    }

    @WrapOperation(method = "smart", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setFilterData(Lnet/createmod/ponder/api/scene/Selection;Ljava/lang/Class;Lnet/minecraft/world/item/ItemStack;)V"))
    private static void differentialcreate$configureMovedMilkFilter(CreateSceneBuilder.WorldInstructions world,
        net.createmod.ponder.api.scene.Selection selection, Class<?> type, net.minecraft.world.item.ItemStack filter,
        Operation<Void> original, @Local(argsOnly = true) net.createmod.ponder.api.scene.SceneBuildingUtil util) {
        original.call(world, selection.test(new BlockPos(2, 1, 4)) ? util.select().position(1, 1, 4) : selection, type, filter);
    }

    @WrapOperation(method = "smart", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"))
    private static net.minecraft.world.phys.Vec3 differentialcreate$movedMilkFilterHint(net.minecraft.world.phys.Vec3 vec,
        double x, double y, double z, Operation<net.minecraft.world.phys.Vec3> original) {
        return original.call(vec, x == -1 && z == 3 ? -1.75d : x, y, z);
    }

    @Inject(method = "smart", at = @At(value = "INVOKE", ordinal = 4,
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setKineticSpeed(Lnet/createmod/ponder/api/scene/Selection;F)V"))
    private static void differentialcreate$startConfiguredRouting(net.createmod.ponder.api.scene.SceneBuilder builder,
        net.createmod.ponder.api.scene.SceneBuildingUtil util, CallbackInfo ci) {
        var scene = new CreateSceneBuilder(builder);
        scene.overlay().showText(360).independent(0).colored(net.createmod.ponder.api.PonderPalette.GREEN)
            .text("Watch chocolate and milk flow through separate branches into their own tanks.")
            .attachKeyFrame();
        scene.overlay().showOutline(net.createmod.ponder.api.PonderPalette.GREEN, new Object(),
            util.select().fromTo(4, 1, 3, 4, 2, 3), 360);
        scene.overlay().showOutline(net.createmod.ponder.api.PonderPalette.BLUE, new Object(),
            util.select().fromTo(4, 1, 4, 4, 2, 4), 360);
        scene.world().setBlock(new BlockPos(3, 1, 1), com.simibubi.create.AllBlocks.FLUID_PIPE.get()
            .getAxisState(net.minecraft.core.Direction.Axis.X), true);
        scene.world().modifyBlockEntity(new BlockPos(4, 1, 1),
            com.simibubi.create.content.processing.basin.BasinBlockEntity.class, basin -> basin.inputTank.getCapability().fill(
                new net.neoforged.neoforge.fluids.FluidStack(
                    com.simibubi.create.foundation.fluid.FluidHelper.convertToStill(com.simibubi.create.AllFluids.CHOCOLATE.get()), 1000),
                net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE));
    }

    @Inject(method = "smart", at = @At(value = "INVOKE", ordinal = 2,
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setKineticSpeed(Lnet/createmod/ponder/api/scene/Selection;F)V"))
    private static void differentialcreate$fillFilterSource(net.createmod.ponder.api.scene.SceneBuilder builder,
        net.createmod.ponder.api.scene.SceneBuildingUtil util, CallbackInfo ci) {
        // Recreate the legacy template's chocolate/milk stock with current fluid
        // IDs. These are initial inputs; the solver chooses and delivers them.
        new CreateSceneBuilder(builder).world().modifyBlockEntity(new BlockPos(4, 1, 1),
            com.simibubi.create.content.processing.basin.BasinBlockEntity.class, basin -> {
                var stock = basin.inputTank.getCapability();
                var action = net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
                stock.fill(new net.neoforged.neoforge.fluids.FluidStack(
                    com.simibubi.create.foundation.fluid.FluidHelper.convertToStill(com.simibubi.create.AllFluids.CHOCOLATE.get()), 1000), action);
                stock.fill(new net.neoforged.neoforge.fluids.FluidStack(net.neoforged.neoforge.common.NeoForgeMod.MILK.get(), 1000), action);
            });
    }
    @Inject(method = "flow", at = @At("HEAD"))
    private static void differentialcreate$prepareFlowSpeed(net.createmod.ponder.api.scene.SceneBuilder builder,
        net.createmod.ponder.api.scene.SceneBuildingUtil util, CallbackInfo ci) {
        // Normalize the saved template before its inlet enters the stage.
        new CreateSceneBuilder(builder).world().multiplyKineticSpeed(util.select().everywhere(), .25f);
    }
    @WrapOperation(method = "flow", at = @At(value = "INVOKE", ordinal = 0,
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;multiplyKineticSpeed(Lnet/createmod/ponder/api/scene/Selection;F)V"))
    private static void differentialcreate$alreadyPreparedFlow(CreateSceneBuilder.WorldInstructions world,
        net.createmod.ponder.api.scene.Selection selection, float factor, Operation<Void> original) {}

    @Inject(method = "interaction", at = @At("HEAD"))
    private static void differentialcreate$prepareStoppedInlet(net.createmod.ponder.api.scene.SceneBuilder builder,
        net.createmod.ponder.api.scene.SceneBuildingUtil util, CallbackInfo ci) {
        new CreateSceneBuilder(builder).world().setKineticSpeed(util.select().everywhere(), 0);
        builder.addInstruction(scene -> com.diliupengyun.differential_create.client.ponder.PonderKinetics.of(scene.getWorld())
            .boundary(scene.getWorld(), new BlockPos(5, 0, 4), 0));
    }
    @Inject(method = "interaction", at = @At(value = "INVOKE", ordinal = 5,
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private static void differentialcreate$finishWaterExample(net.createmod.ponder.api.scene.SceneBuilder builder,
        net.createmod.ponder.api.scene.SceneBuildingUtil util, CallbackInfo ci,
        @Local(ordinal = 0) net.createmod.ponder.api.element.ElementLink<net.createmod.ponder.api.element.WorldSectionElement> water) {
        var scene = new CreateSceneBuilder(builder);
        scene.world().hideIndependentSection(water, net.minecraft.core.Direction.DOWN);
        scene.idle(15);
        builder.addInstruction(new com.diliupengyun.differential_create.client.ponder.PonderAwait(s ->
            com.diliupengyun.differential_create.kinetics.FluidKinetics.ownedVolume(s.getWorld()) < .01, 600));
    }
    @Inject(method = "interaction", at = @At("TAIL"))
    private static void differentialcreate$waitForHoney(net.createmod.ponder.api.scene.SceneBuilder builder,
        net.createmod.ponder.api.scene.SceneBuildingUtil util, CallbackInfo ci) {
        builder.addInstruction(new com.diliupengyun.differential_create.client.ponder.PonderAwait(s ->
            s.getWorld().getBlockEntity(new BlockPos(3, 1, 2)) instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank
                && com.simibubi.create.AllFluids.HONEY.is(tank.getTankInventory().getFluid().getFluid())
                && tank.getTankInventory().getFluidAmount() == 250, 1200));
    }
    @WrapOperation(method = "valve", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;modifyBlock(Lnet/minecraft/core/BlockPos;Ljava/util/function/UnaryOperator;Z)V"))
    private static void differentialcreate$actualValveStroke(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        java.util.function.UnaryOperator<BlockState> action, boolean particles, Operation<Void> original,
        @Local(argsOnly = true) net.createmod.ponder.api.scene.SceneBuilder builder) {
        boolean open = action.apply(com.simibubi.create.AllBlocks.FLUID_VALVE.getDefaultState())
            .getValue(com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock.ENABLED);
        // The native script used to force ENABLED after a fixed 22 ticks. A
        // finite drive must finish the actual stroke before that result cue.
        builder.idle(open ? 80 : 150);
        builder.addInstruction(new com.diliupengyun.differential_create.client.ponder.PonderAwait(scene ->
            scene.getWorld().getBlockState(pos).getValue(com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock.ENABLED) == open, 200));
    }
    @WrapOperation(method = "interaction", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private static void differentialcreate$actualWorldIntake(CreateSceneBuilder.WorldInstructions world, BlockPos pos,
        BlockState state, boolean particles, Operation<Void> original) {
        if (state.is(Blocks.BEE_NEST) && state.getValue(BeehiveBlock.HONEY_LEVEL) == 0) return;
        // This later air edit was a pre-timed extraction result of the moved well.
        if (pos.equals(new BlockPos(1, 1, 1)) && state.isAir()) return;
        original.call(world, pos, state, particles);
    }
}
