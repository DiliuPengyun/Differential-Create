package com.diliupengyun.differential_create.kinetics.portable;

import java.util.List;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.*;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Two independently saved wrench settings shared by every portable endpoint subclass. */
public final class DockingSettingBehaviour extends ScrollValueBehaviour {
    public static final BehaviourType<DockingSettingBehaviour> OUTPUT = new BehaviourType<>(), RELEASE = new BehaviourType<>();
    private final boolean output;
    public DockingSettingBehaviour(PortableStorageInterfaceBlockEntity owner, boolean output) {
        super(Component.translatable("gui.differentialcreate.portable." + (output ? "output" : "release")), owner, new Slot(output));
        this.output = output;
        between(0, output ? 2 : 1); requiresWrench();
        withFormatter(index -> Component.translatable(key(index)).getString());
        withCallback(index -> apply());
    }
    @Override public BehaviourType<?> getType() { return output ? OUTPUT : RELEASE; }
    private String key(int index) { return "gui.differentialcreate.portable." + (output ? "output." : "release.") + index; }
    private DockingState state() { return ((PortableEndpoint) blockEntity).differentialcreate$docking(); }
    private void apply() {
        var state = state();
        if (output) state.outputMode = value;
        else {
            boolean changed = state.releaseMode != value;
            state.releaseMode = value;
            if (changed && value == 0) state.renew();
        }
        state().changed();
    }
    @Override public void initialize() { apply(); }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean packet) { tag.putInt(output ? "DCPortOutput" : "DCPortRelease", value); }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean packet) {
        value = Math.clamp(tag.getInt(output ? "DCPortOutput" : "DCPortRelease"), 0, output ? 2 : 1);
        if (output) state().outputMode = value; else state().releaseMode = value;
    }
    @Override public ValueSettingsBoard createBoard(Player player, BlockHitResult hit) {
        return new ValueSettingsBoard(label, output ? 2 : 1, 1, List.of(label),
            new ValueSettingsFormatter(settings -> Component.translatable(key(settings.value()))));
    }
    @Override public String getClipboardKey() { return output ? "PortableDockingOutput" : "PortableDockingRelease"; }
    private static final class Slot extends ValueBoxTransform.Sided {
        private final boolean output;
        Slot(boolean output) { this.output = output; }
        @Override protected Vec3 getSouthLocation() { return new Vec3(output ? .3 : .7, .5, 16.5 / 16); }
        @Override protected boolean isSideActive(BlockState state, Direction face) {
            return !state.hasProperty(BlockStateProperties.FACING) || face.getAxis() != state.getValue(BlockStateProperties.FACING).getAxis();
        }
    }
}
