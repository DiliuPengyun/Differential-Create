package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.portable.DockingSettingBehaviour;
import com.diliupengyun.differential_create.kinetics.portable.PortableEndpoint;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter.ScrollOptionSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Native icon boards, vanilla defaults, and existing saved options for both endpoint types. */
final class DockingSettingsAudit {
    private static PortableStorageInterfaceBlockEntity fixture(boolean fluid) {
        var world = new PonderLevel(BlockPos.ZERO, Minecraft.getInstance().level);
        world.setBlock(BlockPos.ZERO, (fluid ? AllBlocks.PORTABLE_FLUID_INTERFACE : AllBlocks.PORTABLE_STORAGE_INTERFACE).getDefaultState(), 3);
        return (PortableStorageInterfaceBlockEntity) world.getBlockEntity(BlockPos.ZERO);
    }

    static void verify() {
        for (boolean fluid : new boolean[]{false, true}) {
            var port = fixture(fluid);
            var state = ((PortableEndpoint) port).differentialcreate$docking();
            var output = port.getBehaviour(DockingSettingBehaviour.OUTPUT);
            var release = port.getBehaviour(DockingSettingBehaviour.RELEASE);
            require(output.getValue() == 0 && release.getValue() == 0 && state.outputMode == 0 && state.releaseMode == 0,
                "Fresh portable interfaces must default to connected signal and automatic idle release");
            for (var setting : new DockingSettingBehaviour<?>[]{output, release}) {
                var board = setting.createBoard(Minecraft.getInstance().player, null);
                require(board.formatter() instanceof ScrollOptionSettingsFormatter && setting.onlyVisibleWithWrench(),
                    "Portable settings must use the native wrench-only icon selector");
            }
            var outputSlot = (ValueBoxTransform.Sided) output.getSlotPositioning();
            var releaseSlot = (ValueBoxTransform.Sided) release.getSlotPositioning();
            for (var facing : Direction.values()) for (var face : Direction.values()) {
                var block = port.getBlockState().setValue(BlockStateProperties.FACING, facing);
                outputSlot.fromSide(face); releaseSlot.fromSide(face);
                boolean active = face.getAxis() != facing.getAxis();
                require(outputSlot.shouldRender(port.getLevel(), BlockPos.ZERO, block) == active
                    && releaseSlot.shouldRender(port.getLevel(), BlockPos.ZERO, block) == active,
                    "Wrench slots must stay off the docking and rear connection faces");
                if (!active) continue;
                var left = outputSlot.getLocalOffset(port.getLevel(), BlockPos.ZERO, block);
                var right = releaseSlot.getLocalOffset(port.getLevel(), BlockPos.ZERO, block);
                require(left.distanceTo(right) + 1e-6 >= (outputSlot.getScale() + releaseSlot.getScale()) / 2,
                    "Native option boxes and pointer targets must not overlap");
                require(outputSlot.testHit(port.getLevel(), BlockPos.ZERO, block, left)
                    && !releaseSlot.testHit(port.getLevel(), BlockPos.ZERO, block, left)
                    && releaseSlot.testHit(port.getLevel(), BlockPos.ZERO, block, right)
                    && !outputSlot.testHit(port.getLevel(), BlockPos.ZERO, block, right),
                    "Each wrench option must be selectable independently on every active face");
            }
            output.setValue(2); release.setValue(1);
            var tag = new CompoundTag();
            var registries = port.getLevel().registryAccess();
            output.write(tag, registries, false); release.write(tag, registries, false);
            output.read(new CompoundTag(), registries, false); release.read(new CompoundTag(), registries, false);
            require(state.outputMode == 0 && state.releaseMode == 0, "Old saves without settings must use vanilla defaults");
            output.read(tag, registries, false); release.read(tag, registries, false);
            require(state.outputMode == 2 && state.releaseMode == 1 && output.getValue() == 2 && release.getValue() == 1,
                "Saved optional settings must survive the UI replacement");
        }
        DifferentialCreate.LOGGER.info("DC_DOCKING_SETTINGS_PASS: native icon menus, item/fluid defaults and saved options");
    }

    static ValueSettingsScreen screen(boolean release) {
        var port = fixture(false);
        var setting = port.getBehaviour(release ? DockingSettingBehaviour.RELEASE : DockingSettingBehaviour.OUTPUT);
        setting.setValue(1);
        return new ValueSettingsScreen(BlockPos.ZERO, setting.createBoard(Minecraft.getInstance().player, null),
            setting.getValueSettings(), value -> {}, 0) {
                @Override protected void saveAndClose(double x, double y) { onClose(); } // Preview never sends a packet.
            };
    }

    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
