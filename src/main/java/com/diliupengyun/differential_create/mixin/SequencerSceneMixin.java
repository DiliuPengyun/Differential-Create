package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.bearing.IBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.Instruction;
import com.simibubi.create.content.kinetics.transmission.sequencer.InstructionSpeedModifiers;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.KineticsScenes;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.TickingInstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Preserve the native moves and cue times, while driving them from actual output travel. */
@Mixin(KineticsScenes.class)
public abstract class SequencerSceneMixin {
    @WrapOperation(method = "sequencedGearshift", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;rotateBearing(Lnet/minecraft/core/BlockPos;FI)V"))
    private static void differentialcreate$runMove(CreateSceneBuilder.WorldInstructions world, BlockPos bearing,
        float degrees, int duration, Operation<Void> original, SceneBuilder builder, SceneBuildingUtil util) {
        builder.addInstruction(scene -> {
            var level = scene.getWorld();
            var pos = util.grid().at(3, 1, 2);
            if (!(level.getBlockEntity(pos) instanceof SequencedGearshiftBlockEntity sequencer)) return;
            var program = sequencer.getInstructions();
            program.clear();
            var direction = Math.signum(degrees) == Math.signum(sequencer.getSpeed())
                ? InstructionSpeedModifiers.FORWARD : InstructionSpeedModifiers.BACK;
            program.add(new Instruction(SequencerInstructions.TURN_ANGLE, direction, Math.round(Math.abs(degrees))));
            program.add(new Instruction(SequencerInstructions.END));
            PonderKinetics.of(level).sequence(level, pos);
        });
        // The paired section instruction below also animates the bearing head.
    }

    @WrapOperation(method = "sequencedGearshift", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;rotateSection(Lnet/createmod/ponder/api/element/ElementLink;DDDI)V"))
    private static void differentialcreate$followOutput(CreateSceneBuilder.WorldInstructions world,
        ElementLink<WorldSectionElement> link, double x, double y, double z, int duration, Operation<Void> original,
        SceneBuilder builder, SceneBuildingUtil util) {
        builder.addInstruction(new TickingInstruction(false, duration) {
            private double start;
            private Vec3 rotation;
            private float bearingStart;
            @Override protected void firstTick(PonderScene scene) {
                super.firstTick(scene);
                var section = scene.resolve(link);
                rotation = section == null ? Vec3.ZERO : section.getAnimatedRotation();
                var be = (KineticBlockEntity) scene.getWorld().getBlockEntity(util.grid().at(1, 1, 2));
                Double angle = be == null ? null : PonderKinetics.of(scene.getWorld()).angle(be, 1);
                start = angle == null ? 0 : angle;
                bearingStart = be instanceof IBearingBlockEntity bearing ? bearing.getInterpolatedAngle(0) : 0;
            }
            @Override public void tick(PonderScene scene) {
                super.tick(scene);
                var be = (KineticBlockEntity) scene.getWorld().getBlockEntity(util.grid().at(1, 1, 2));
                if (be == null) return;
                Double angle = PonderKinetics.of(scene.getWorld()).angle(be, 1);
                double travel = angle == null ? 0 : angle - start;
                var section = scene.resolve(link);
                if (section != null) section.setAnimatedRotation(rotation.add(travel, 0, 0), false);
                if (be instanceof IBearingBlockEntity bearing) bearing.setAngle(bearingStart + (float) travel);
            }
        });
    }
}
