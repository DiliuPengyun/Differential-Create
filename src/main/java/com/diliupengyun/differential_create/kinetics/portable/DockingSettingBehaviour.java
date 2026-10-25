package com.diliupengyun.differential_create.kinetics.portable;

import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.*;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.INamedIconOptions;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import com.simibubi.create.foundation.gui.AllIcons;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Two independently saved wrench settings shared by every portable endpoint subclass. */
public final class DockingSettingBehaviour<E extends Enum<E> & INamedIconOptions> extends ScrollOptionBehaviour<E> {
    public static final BehaviourType<DockingSettingBehaviour<?>> OUTPUT = new BehaviourType<>(), RELEASE = new BehaviourType<>();
    private final boolean output;
    public static DockingSettingBehaviour<Output> output(PortableStorageInterfaceBlockEntity owner) {
        return new DockingSettingBehaviour<>(Output.class, owner, true);
    }
    public static DockingSettingBehaviour<Release> release(PortableStorageInterfaceBlockEntity owner) {
        return new DockingSettingBehaviour<>(Release.class, owner, false);
    }
    private DockingSettingBehaviour(Class<E> options, PortableStorageInterfaceBlockEntity owner, boolean output) {
        super(options, Component.translatable("gui.differentialcreate.portable." + (output ? "output" : "release")), owner, new Slot(output));
        this.output = output;
        requiresWrench();
        withCallback(index -> apply());
    }
    @Override public BehaviourType<?> getType() { return output ? OUTPUT : RELEASE; }
    public enum Release implements INamedIconOptions {
        AUTOMATIC(AllIcons.I_PLAY), REDSTONE(AllIcons.I_PAUSE);
        private final AllIcons icon;
        Release(AllIcons icon) { this.icon = icon; }
        @Override public AllIcons getIcon() { return icon; }
        @Override public String getTranslationKey() { return "gui.differentialcreate.portable.release." + ordinal(); }
    }
    public enum Output implements INamedIconOptions {
        CONNECTED(AllIcons.I_ATTACHED), OCCUPIED(AllIcons.I_CONFIG_LOCKED), PHASE(AllIcons.I_MOVE_GAUGE);
        private final AllIcons icon;
        Output(AllIcons icon) { this.icon = icon; }
        @Override public AllIcons getIcon() { return icon; }
        @Override public String getTranslationKey() { return "gui.differentialcreate.portable.output." + ordinal(); }
    }
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
    @Override public String getClipboardKey() { return output ? "PortableDockingOutput" : "PortableDockingRelease"; }
    private static final class Slot extends ValueBoxTransform.Sided {
        private final boolean output;
        Slot(boolean output) { this.output = output; }
        @Override protected Vec3 getSouthLocation() { return new Vec3(output ? .25 : .75, .5, 16.5 / 16); }
        @Override protected boolean isSideActive(BlockState state, Direction face) {
            return !state.hasProperty(BlockStateProperties.FACING) || face.getAxis() != state.getValue(BlockStateProperties.FACING).getAxis();
        }
    }
}
